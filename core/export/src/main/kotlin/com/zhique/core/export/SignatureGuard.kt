package com.zhique.core.export

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.zhique.core.project.ProjectMeta
import com.zhique.core.project.ProjectRepository

/** 覆盖安装前置校验失败（决策29-5）：已装包签名与当前密钥库不一致。 */
class SignatureMismatchException(
    val packageName: String,
    val keystoreSha256: String,
    val installedSha256: List<String>,
) : IllegalStateException(
    "签名不一致：已安装 $packageName 的签名与当前密钥库不同。" +
        "请先卸载旧包，或恢复与已装版本一致的密钥库后再导出。",
)

/**
 * 覆盖安装前置校验（M6 Task 6.2 · 决策29-5）：导出新版本前用 PackageManager
 * 读取手机上已装 `com.zhique.export.<slug>` 的签名证书与当前密钥库证书比对，
 * **不一致直接抛 [SignatureMismatchException]**（UI 阻断并说明：先卸载旧包
 * 或恢复正确密钥库）——机制上杜绝「更新装不上」。
 *
 * 未安装（首导出/已卸载）→ 放行（全新安装）。
 */
class SignatureGuard(
    private val context: Context,
    private val keystore: KeystoreManager,
    private val repo: ProjectRepository,
    private val packageNameOf: (ProjectMeta) -> String = { it.export?.packageName ?: "" },
) {

    fun verifyBeforeExport(projectId: String) {
        val meta = repo.meta(projectId)
        val keystoreFp = keystore.certificateSha256() ?: keystore.ensureKeystore().let {
            keystore.certificateSha256() ?: throw IllegalStateException("keystore fingerprint unavailable")
        }
        val pkg = packageNameOf(meta)
        if (pkg.isBlank()) return // 尚未定包名（首导出），VersionManager 随后给出
        val info = runCatching {
            context.packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
        }.getOrNull() ?: return // 未安装 → 全新安装，放行
        val installedFps = fingerprintsOf(info)
        if (keystoreFp !in installedFps) {
            throw SignatureMismatchException(pkg, keystoreFp, installedFps)
        }
    }

    private fun fingerprintsOf(info: android.content.pm.PackageInfo): List<String> {
        val fromSigningInfo = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else null
        val signatures = fromSigningInfo ?: @Suppress("DEPRECATION") info.signatures
        return signatures?.map { CertFingerprints.sha256(it.toByteArray()) } ?: emptyList()
    }
}
