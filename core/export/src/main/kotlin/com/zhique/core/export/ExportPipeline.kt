package com.zhique.core.export

import android.content.Context
import com.android.zipflinger.BytesSource
import com.android.zipflinger.ZipArchive
import java.io.File
import java.util.zip.Deflater

/** 模板底版提供者：variant（min | full）→ 未签名模板 APK。 */
interface TemplateProvider {
    fun template(variant: String): File
}

/** 资产版：织雀 APK 内置 `assets/templates/<variant>.apk`，首次使用解包到缓存。 */
class AssetTemplateProvider(
    private val context: Context,
    private val cacheDir: File,
) : TemplateProvider {

    override fun template(variant: String): File {
        val cached = File(cacheDir, "$variant.apk")
        if (cached.isFile && cached.length() > 0) return cached
        cacheDir.mkdirs()
        context.assets.open("templates/$variant.apk").use { input ->
            cached.outputStream().use { input.copyTo(it) }
        }
        return cached
    }
}

/**
 * 导出管线（M6 Task 6.2）：模板底版 → 资产注入（zipflinger）→ 身份补丁
 * （AXML：包名/版本/应用名）→ v2+v3 签名（apksig）→ ApkVerifier 编程校验。
 * 纯输入输出编排，不含项目状态（状态在 [ExportService]）。
 */
class ExportPipeline(
    private val templates: TemplateProvider,
    private val signer: Signer,
    private val injector: AssetInjector = AssetInjector(),
    private val workDir: File,
) {

    data class Request(
        val variant: String,
        val packageName: String,
        val versionCode: Int,
        val versionName: String,
        val appName: String,
        val projectFiles: Map<String, ByteArray>,
        val signingKey: Signer.Key,
    )

    data class Output(val apk: File, val certSha256: String)

    fun export(request: Request): Output {
        workDir.mkdirs()
        val unsigned = File(workDir, "${request.packageName}-${request.versionCode}-unsigned.apk")
        val signed = File(workDir, "${request.packageName}-${request.versionCode}.apk")
        signed.delete()

        // ① 注入项目资产（旧 assets/project 全删）
        injector.inject(templates.template(request.variant), request.projectFiles, unsigned)
        // ② 身份补丁：从 APK 里取出二进制 manifest → 改写 package/versionCode/versionName/label → 写回
        ZipArchive(unsigned.toPath()).use { zip ->
            val buf = zip.getContent(MANIFEST_ENTRY)
            val manifestBytes = ByteArray(buf.remaining()).also { buf.get(it) }
            zip.delete(MANIFEST_ENTRY)
            zip.add(
                BytesSource(
                    AxmlPatcher.patch(
                        manifestBytes,
                        AxmlPatcher.ManifestPatch(
                            packageName = request.packageName,
                            versionCode = request.versionCode,
                            versionName = request.versionName,
                            label = request.appName,
                        ),
                    ),
                    MANIFEST_ENTRY,
                    Deflater.BEST_SPEED,
                ),
            )
        }
        // ③ v2+v3 签名 → ④ ApkVerifier 编程校验
        signer.sign(unsigned, signed, request.signingKey)
        val certSha256 = signer.verify(signed)
        unsigned.delete()
        return Output(signed, certSha256)
    }

    companion object {
        const val MANIFEST_ENTRY = "AndroidManifest.xml"
    }
}

/** 导出产物（apk 文件 + 写入 project.json 的记录）。 */
data class ExportOutcome(val apk: File, val record: com.zhique.core.project.ExportRecord)

/**
 * 导出编排服务（状态层）：覆盖安装前置校验（[SignatureGuard]，不一致即阻断）
 * → 包名/版本决策（[VersionManager]）→ 管线出包 → ExportRecord（含证书 SHA-256）
 * 写回 project.json。
 */
class ExportService(
    private val repo: com.zhique.core.project.ProjectRepository,
    private val version: VersionManager,
    private val pipeline: ExportPipeline,
    private val keystore: KeystoreManager,
    private val guard: SignatureGuard? = null,
    private val now: () -> Long = System::currentTimeMillis,
    private val injector: AssetInjector = AssetInjector(),
) {

    /**
     * 完整导出。[SignatureMismatchException] 会原样抛出（UI 阻断页的触发源）。
     */
    fun export(projectId: String, appName: String, variant: String): ExportOutcome {
        guard?.verifyBeforeExport(projectId)
        val meta = repo.meta(projectId)
        val pkg = version.packageName(meta)
        val code = version.nextVersionCode(meta)
        val vName = version.versionName(code)
        val metaJson = repo.metaJson(projectId)
        val output = pipeline.export(
            ExportPipeline.Request(
                variant = variant,
                packageName = pkg,
                versionCode = code,
                versionName = vName,
                appName = appName,
                projectFiles = injector.collect(
                    repo.projectDir(projectId),
                    extra = mapOf(AssetInjector.PROJECT_JSON_ENTRY to metaJson),
                ),
                signingKey = keystore.signingKey(),
            ),
        )
        val record = com.zhique.core.project.ExportRecord(
            packageName = pkg,
            versionCode = code,
            versionName = vName,
            at = now(),
            variant = variant,
            certSha256 = output.certSha256,
        )
        repo.recordExport(projectId, record)
        return ExportOutcome(output.apk, record)
    }
}
