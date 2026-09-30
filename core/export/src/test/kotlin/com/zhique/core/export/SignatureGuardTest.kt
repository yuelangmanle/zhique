package com.zhique.core.export

import android.content.pm.PackageInfo
import java.io.File
import android.content.pm.Signature
import com.zhique.core.project.ProjectRepository
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 覆盖安装前置校验（决策29-5）：已装包签名 = 当前密钥库 → 放行；
 * 不一致 → SignatureMismatchException 阻断（UI 阻断页触发源）；未安装 → 放行。
 * Robolectric 以 PackageInfo.signatures 装载签名（真机走 signingInfo，读法已兼容两路）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SignatureGuardTest {

    private val repo = ProjectRepository(RuntimeEnvironment.getApplication().filesDir)

    private fun keystore(): KeystoreManager =
        KeystoreManager(
            File(RuntimeEnvironment.getApplication().filesDir, "ks-" + java.util.UUID.randomUUID()),
            com.zhique.core.common.crypto.CryptoStore(softwareKey()),
        )

    private fun guard(ks: KeystoreManager): SignatureGuard =
        SignatureGuard(
            context = RuntimeEnvironment.getApplication(),
            keystore = ks,
            repo = repo,
            packageNameOf = { it.export?.packageName ?: "" },
        )

    private fun install(pkg: String, certBytes: ByteArray) {
        val info = PackageInfo().apply {
            packageName = pkg
            versionCode = 1
            @Suppress("DEPRECATION")
            signatures = arrayOf(Signature(certBytes))
        }
        shadowOf(RuntimeEnvironment.getApplication().packageManager).installPackage(info)
    }

    private fun record(pkg: String, ks: KeystoreManager, id: String) {
        repo.recordExport(
            id,
            com.zhique.core.project.ExportRecord(pkg, 1, "1.0.1", 0, "min", ks.certificateSha256().orEmpty()),
        )
    }

    @Test
    fun `未安装时放行`() {
        val ks = keystore()
        val meta = repo.create("便签", "<p></p>")
        record("com.zhique.export.untitled", ks, meta.id)
        guard(ks).verifyBeforeExport(meta.id) // 不抛即通过
    }

    @Test
    fun `已装签名与密钥库一致时放行`() {
        val ks = keystore()
        val meta = repo.create("便签", "<p></p>")
        val pkg = "com.zhique.export.note"
        record(pkg, ks, meta.id)
        install(pkg, ks.signingKey().certificate.encoded)
        guard(ks).verifyBeforeExport(meta.id)
    }

    @Test
    fun `已装签名与密钥库不一致时阻断`() {
        val ks = keystore()
        val meta = repo.create("便签", "<p></p>")
        val pkg = "com.zhique.export.note"
        record(pkg, ks, meta.id)
        // 设备上是另一个密钥库签的历史包
        val foreign = keystore()
        install(pkg, foreign.signingKey().certificate.encoded)
        val e = assertFailsWith<SignatureMismatchException> {
            guard(ks).verifyBeforeExport(meta.id)
        }
        assertTrue(e.packageName == pkg)
        val msg = e.message.orEmpty()
        assertTrue(msg.contains("卸载") || msg.contains("恢复"))
    }
}
