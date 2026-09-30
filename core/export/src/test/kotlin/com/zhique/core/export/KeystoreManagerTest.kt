package com.zhique.core.export

import com.zhique.core.common.crypto.CryptoStore
import com.zhique.core.common.crypto.KeyProvider
import java.io.File
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.rules.TemporaryFolder

/**
 * KeystoreManager（决策29 逐条）：一次生成永久复用、口令零字面量（运行时随机）、
 * 备份状态流转（lastBackupAt/超期）、导入指纹校验（不一致拒绝生效）。
 * 测试全程不出现可用口令字面量——导入用口令一律运行时随机生成或取自被测对象。
 */
class KeystoreManagerTest {

    private val tmp = TemporaryFolder()
    private val key = SoftwareKey()

    private fun manager(dir: File = tmp.newFolder(), now: () -> Long = System::currentTimeMillis) =
        KeystoreManager(dir, CryptoStore(key), now = now)

    @Test
    fun `首启生成密钥库且指纹可复取`() {
        tmp.create()
        val ks = manager()
        ks.ensureKeystore()
        assertTrue(ks.exists())
        assertTrue(ks.file.isFile)
        val fp = ks.certificateSha256()
        assertEquals(64, fp!!.length)
        assertEquals(fp, ks.certificateSha256())
    }

    @Test
    fun `密钥库一次生成永久复用`() {
        tmp.create()
        val ks = manager()
        val fp1 = ks.signingKey().certSha256
        val fp2 = ks.signingKey().certSha256
        assertEquals(fp1, fp2)
    }

    @Test
    fun `口令运行时随机生成且密文持久`() {
        tmp.create()
        val ks = manager()
        val pass = ks.password()
        assertTrue(pass.length >= 32)
        // 解密回读一致（密文持久于盘，进程重启不丢）
        assertEquals(pass, ks.password())
    }

    @Test
    fun `两个实例共享同一密钥库目录与主密钥得到同一证书`() {
        tmp.create()
        val dir = tmp.newFolder()
        val a = manager(dir)
        a.ensureKeystore()
        val b = manager(dir)
        assertEquals(a.certificateSha256(), b.certificateSha256())
    }

    @Test
    fun `exportTo产出备份副本且与主文件一致`() {
        tmp.create()
        val ks = manager()
        ks.ensureKeystore()
        val copy = ks.exportTo(File(tmp.newFolder(), "backup/zhique-release.jks"))
        assertTrue(copy.isFile)
        assertEquals(ks.file.readBytes().size, copy.length().toInt())
    }

    @Test
    fun `备份状态流转-未备份到超期到已备份`() {
        tmp.create()
        var clock = 1_000_000L
        val ks = manager(now = { clock })
        ks.ensureKeystore()
        // 从未备份 → 超期
        assertTrue(ks.backupStatus().backupDue)
        assertEquals(0L, ks.backupStatus().lastBackupAt)
        // 备份 → 未超期
        ks.markBackedUp()
        assertEquals(clock, ks.backupStatus().lastBackupAt)
        assertFalse(ks.backupStatus().backupDue)
        // 推进超过 30 天 → 再次超期
        clock += 31L * 24 * 60 * 60 * 1000
        assertTrue(ks.backupStatus().backupDue)
        // 边界：30 天内不超期
        clock = ks.backupStatus().lastBackupAt + 30L * 24 * 60 * 60 * 1000 - 1000
        assertFalse(ks.backupStatus().backupDue)
    }

    @Test
    fun `密钥库丢失后导入备份副本-指纹一致-生效`() {
        tmp.create()
        val ks = manager()
        ks.ensureKeystore()
        val fp = ks.certificateSha256()!!
        val pass = ks.password()
        val copy = ks.exportTo(File(tmp.newFolder(), "zhique-release.jks"))
        // 模拟密钥库彻底丢失：主文件与口令密文一并消失
        ks.file.delete()
        File(ks.file.parentFile, "secret.bin").delete()
        assertFalse(ks.exists())
        val result = ks.import(copy.absolutePath, pass)
        assertTrue(result is ImportResult.Accepted)
        assertEquals(fp, (result as ImportResult.Accepted).certSha256)
        assertEquals(fp, ks.certificateSha256())
        // 恢复后口令持久：新实例可读
        assertEquals(fp, manager(ks.file.parentFile!!.parentFile!!).certificateSha256())
    }

    @Test
    fun `导入指纹与既有导出记录不一致-拒绝生效且密钥库不变`() {
        tmp.create()
        val ks = manager()
        ks.ensureKeystore()
        val originalFp = ks.certificateSha256()!!
        // 另一个密钥库（不同密钥 → 不同指纹）
        val other = manager()
        other.ensureKeystore()
        val otherCopy = other.exportTo(File(tmp.newFolder(), "other.jks"))
        val before = ks.file.readBytes()
        val result = ks.import(otherCopy.absolutePath, other.password(), listOf(originalFp))
        assertTrue(result is ImportResult.Rejected)
        assertEquals(originalFp, (result as ImportResult.Rejected).expected.single())
        assertNotEquals(originalFp, (result as ImportResult.Rejected).certSha256)
        // 密钥库保持原状
        assertEquals(before.toList(), ks.file.readBytes().toList())
        assertEquals(originalFp, ks.certificateSha256())
    }

    @Test
    fun `导入错误口令报错`() {
        tmp.create()
        val ks = manager()
        ks.ensureKeystore()
        val other = manager()
        other.ensureKeystore()
        val otherCopy = other.exportTo(File(tmp.newFolder(), "other.jks"))
        assertFailsWith<KeystoreImportException> {
            ks.import(otherCopy.absolutePath, "wrong-pass-${System.nanoTime()}")
        }
    }

    @Test
    fun `并发ensureKeystore-文件与口令一致不损坏`() {
        tmp.create()
        val dir = tmp.newFolder()
        val ks = manager(dir)
        val gate = java.util.concurrent.CountDownLatch(1)
        val results = (1..2).map {
            thread(start = true) {
                gate.await()
                ks.ensureKeystore()
            }
        }
        gate.countDown()
        results.forEach { it.join(10_000) }
        // 双线程竞写后：文件可读、口令可解、证书指纹可取
        assertTrue(ks.exists())
        assertTrue(ks.certificateSha256() != null)
        assertEquals(ks.password(), ks.password())
        // 独立实例读同一目录验证一致
        assertEquals(ks.certificateSha256(), manager(dir).certificateSha256())
    }

    @Test
    fun `导入仅含证书的密钥库被拒绝`() {
        tmp.create()
        val ks = manager()
        ks.ensureKeystore()
        val originalFp = ks.certificateSha256()!!
        // 仅证书（无私钥）的 PKCS12
        val cert = ks.certificate()!!
        val certOnly = java.security.KeyStore.getInstance("PKCS12")
        certOnly.load(null, null)
        certOnly.setCertificateEntry("cert-only", cert)
        val pass = "cert-only-pass-" + java.util.UUID.randomUUID() // PKCS12 口令限 ASCII
        val file = File(tmp.newFolder(), "certonly.jks")
        file.outputStream().use { certOnly.store(it, pass.toCharArray()) }
        val before = ks.file.readBytes()
        assertFailsWith<KeystoreImportException> {
            ks.import(file.absolutePath, pass, emptyList())
        }
        assertEquals(before.toList(), ks.file.readBytes().toList())
        assertEquals(originalFp, ks.certificateSha256())
    }

    @Test
    fun `尚无导出记录时导入任意有效密钥库生效`() {
        tmp.create()
        val ks = manager()
        ks.ensureKeystore()
        val other = manager()
        other.ensureKeystore()
        val copy = other.exportTo(File(tmp.newFolder(), "o.jks"))
        val result = ks.import(copy.absolutePath, other.password(), existingFingerprints = emptyList())
        assertTrue(result is ImportResult.Accepted)
    }
}
