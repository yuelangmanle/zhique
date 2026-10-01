package com.zhique.core.export

import com.zhique.core.project.ProjectRepository
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.rules.TemporaryFolder

/**
 * 端到端出包管线（真实模板壳底版）：注入 → AXML 身份补丁 → v2+v3 签名 →
 * ApkVerifier 校验 → ExportRecord（含证书 SHA-256）写回。
 */
class ExportPipelineTest {

    private val tmp = TemporaryFolder()

    private fun wire(variant: String): Triple<ProjectRepository, KeystoreManager, ExportService> {
        tmp.create()
        val root = tmp.newFolder()
        val repo = ProjectRepository(root)
        val keystore = KeystoreManager(root, com.zhique.core.common.crypto.CryptoStore(softwareKey()))
        val pipeline = ExportPipeline(
            templates = object : TemplateProvider {
                override fun template(v: String): File = if (v == "full") TemplateFixtures.full() else TemplateFixtures.min()
            },
            signer = Signer(),
            workDir = File(root, "work"),
        )
        val version = VersionManager(repo)
        val service = ExportService(repo, version, pipeline, keystore)
        return Triple(repo, keystore, service)
    }

    private fun manifestOf(apk: File): ByteArray =
        ZipFile(apk).use { zip ->
            zip.getInputStream(zip.getEntry("AndroidManifest.xml")).use { it.readBytes() }
        }

    @Test
    fun `min变体端到端出包-身份与签名与资产全对`() {
        val (repo, keystore, service) = wire("min")
        val meta = repo.create("织雀记事本", "<h1>hi</h1>")
        val outcome = service.export(meta.id, "织雀记事本", "min")

        // 校验过的产物 + 指纹与密钥库一致
        assertEquals(keystore.certificateSha256(), outcome.record.certSha256)
        assertEquals(1, outcome.record.versionCode)
        assertTrue(outcome.apk.isFile)

        // manifest 身份补丁生效（签过的包同样可读）
        val info = AxmlReader.readManifest(manifestOf(outcome.apk))
        assertEquals(outcome.record.packageName, info.packageName)
        assertEquals(1L, info.versionCode)
        assertEquals("1.0.1", info.versionName)
        assertEquals("织雀记事本", info.label)
        assertTrue(info.packageName!!.startsWith("com.zhique.export."))

        // 项目资产进了包
        ZipFile(outcome.apk).use { zip ->
            val html = zip.getInputStream(zip.getEntry("assets/project/index.html")).use { it.readBytes() }
            assertEquals("<h1>hi</h1>", html.decodeToString())
            assertNotNull(zip.getEntry("assets/project/project.json"))
        }

        // 记录写回 project.json（含证书 SHA-256）
        val record = repo.meta(meta.id).export
        assertNotNull(record)
        assertEquals(outcome.record, record)
        assertEquals(64, record!!.certSha256.length)
    }

    @Test
    fun `full变体端到端出包-版本自增且包名稳定`() {
        val (repo, keystore, service) = wire("full")
        val meta = repo.create("翻译卡", "<p></p>")
        val v1 = service.export(meta.id, "翻译卡", "full")
        val v2 = service.export(meta.id, "翻译卡", "full")

        assertEquals(1, v1.record.versionCode)
        assertEquals(2, v2.record.versionCode)
        assertEquals(v1.record.packageName, v2.record.packageName)
        assertEquals("1.0.2", v2.record.versionName)
        assertEquals(keystore.certificateSha256(), v2.record.certSha256)
        assertEquals("full", v2.record.variant)
        // 版本号写回
        assertEquals(2, repo.meta(meta.id).export!!.versionCode)
    }

    @Test
    fun `管线失败清理工作残骸`() {
        tmp.create()
        val root = tmp.newFolder()
        val repo = ProjectRepository(root)
        val keystore = KeystoreManager(root, com.zhique.core.common.crypto.CryptoStore(softwareKey()))
        val workDir = File(root, "work")
        // 坏模板：AndroidManifest.xml 非 AXML → 注入成功、补丁失败
        val badTemplate = File(root, "bad-template.apk")
        java.util.zip.ZipOutputStream(badTemplate.outputStream()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("AndroidManifest.xml"))
            zip.write("这不是二进制XML".toByteArray())
            zip.closeEntry()
        }
        val pipeline = ExportPipeline(
            templates = object : TemplateProvider {
                override fun template(v: String): File = badTemplate
            },
            signer = Signer(),
            workDir = workDir,
        )
        val meta = repo.create("坏模板", "<p></p>")
        val e = runCatching {
            ExportService(repo, VersionManager(repo), pipeline, keystore).export(meta.id, "坏模板", "min")
        }
        assertTrue(e.isFailure)
        // unsigned/signed 残骸一律清理
        assertEquals(emptyList(), workDir.listFiles()?.filter { it.name.endsWith(".apk") }.orEmpty())
    }

    @Test
    fun `两个项目导出包名不同可共存`() {
        val (repo, _, service) = wire("min")
        val a = repo.create("计算器", "<p></p>")
        val b = repo.create("计算器", "<p></p>")
        val oa = service.export(a.id, "计算器", "min")
        val ob = service.export(b.id, "计算器", "min")
        assertTrue(oa.record.packageName != ob.record.packageName)
    }

    // ---- launcher 图标注入端到端（M6 偏差③） ----

    @Test
    fun `图标注入端到端-项目默认靛蓝与向导石板色各归位`() {
        val (repo, _, service) = wire("min")
        val injector = AssetInjector()
        val arsc = injector.resourcesArsc(TemplateFixtures.min())!!
        val indigo = ResourceTableReader.entryId(arsc, "mipmap", "icon_indigo")
        val slate = ResourceTableReader.entryId(arsc, "mipmap", "icon_slate")

        val meta = repo.create("深色工具", "<h1></h1>") // 项目 iconColor 默认 #46509F
        // v1：项目默认色 → 靛蓝图标（导出内含 ApkVerifier 校验，抛错即挂）
        val v1 = service.export(meta.id, "深色工具", "min")
        assertEquals(indigo, AxmlReader.readManifest(manifestOf(v1.apk)).iconResId)
        // v2：向导当次选石板色 → 石板图标（版本自增不受影响）
        val v2 = service.export(meta.id, "深色工具", "min", iconColor = "#2E3644")
        assertEquals(slate, AxmlReader.readManifest(manifestOf(v2.apk)).iconResId)
        assertEquals(2, v2.record.versionCode)

        // 系统视角核对：badging 解析出的应用图标文件 == arsc 里该预设资源指向的文件
        assertLauncherIconResolves(v1.apk, "mipmap/icon_indigo")
        assertLauncherIconResolves(v2.apk, "mipmap/icon_slate")
    }

    /** aapt badging 的 application icon 路径须等于 arsc 中 [resource] 指向的资源文件。 */
    private fun assertLauncherIconResolves(apk: File, resource: String) {
        val tools = locateAapt()
        if (tools == null) {
            println("skip badging 断言：未找到 Android SDK build-tools（arsc/manifest 断言已覆盖机制正确性）")
            return
        }
        val (aapt, aapt2) = tools
        val resources = run(aapt2, "dump", "resources", apk.absolutePath)
        val lines = resources.lines()
        val resIdx = lines.indexOfFirst { it.contains(resource) }
        assertTrue(resIdx >= 0 && resIdx + 1 < lines.size, "arsc 中应含 $resource：\n$resources")
        val fileLine = lines[resIdx + 1]
        assertTrue(fileLine.contains("(file) res/"), "arsc $resource 条目下应有 (file) res/… 行：\n$resources")
        val resPath = Regex("""\(file\) (res/\S+?) type=""").find(fileLine)!!.groupValues[1]

        val badging = run(aapt, "d", "badging", apk.absolutePath)
        val iconLine = badging.lineSequence().firstOrNull { it.startsWith("application: ") }
            ?: error("badging 应有 application 行：\n$badging")
        assertTrue(
            iconLine.contains("icon='$resPath'"),
            "launcher 图标应解析到 $resource 的资源文件 $resPath，实际：$iconLine",
        )
    }

    private fun locateAapt(): Pair<String, String>? {
        val sdk = System.getProperty("zhique.android.sdk") ?: return null
        val dir = File(sdk, "build-tools")
        val exe = if (System.getProperty("os.name").lowercase().contains("win")) ".exe" else ""
        val best = dir.listFiles { f -> f.isDirectory && File(f, "aapt$exe").isFile }
            ?.maxByOrNull { it.name } ?: return null
        return File(best, "aapt$exe").absolutePath to File(best, "aapt2$exe").absolutePath
    }

    private fun run(vararg cmd: String): String {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        assertTrue(p.exitValue() == 0, "${cmd[0]} 退出码非 0：\n$out")
        return out
    }
}
