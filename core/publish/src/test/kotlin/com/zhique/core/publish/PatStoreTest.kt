package com.zhique.core.publish

import com.zhique.core.common.crypto.CryptoStore
import com.zhique.core.common.crypto.KeyProvider
import java.io.File
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.rules.TemporaryFolder

/** Task 7.1：PatStore——密文落盘、明文只在解出瞬间存在、清除幂等。 */
class PatStoreTest {

    private val tmp = TemporaryFolder()

    private class SoftwareKey : KeyProvider {
        // 单测内固定一把密钥：同一密文须能被多个 PatStore 实例解出（进程重启模拟）
        private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        override fun masterKey(): SecretKey = key
    }

    private val key = SoftwareKey()

    private fun store(file: File): PatStore = PatStore(file, CryptoStore(key))

    @Test
    fun `保存后密文落盘且解出一致`() {
        tmp.create()
        val f = File(tmp.newFolder(), "publish/pat.enc")
        val pat = "github_pat_" + "K".repeat(20)
        store(f).save(pat)
        assertTrue(f.isFile)
        val onDisk = f.readText()
        assertFalse(pat in onDisk, "明文不得落盘")
        assertEquals(pat, store(f).pat())
    }

    @Test
    fun `未配置或损坏密文返回null而非抛错`() {
        tmp.create()
        val f = File(tmp.newFolder(), "publish/pat.enc")
        assertEquals(null, store(f).pat())
        assertFalse(store(f).hasPat())
        f.parentFile.mkdirs()
        f.writeText("not-a-cipher")
        assertEquals(null, store(f).pat())
    }

    @Test
    fun `覆盖保存取最新值且clear幂等`() {
        tmp.create()
        val f = File(tmp.newFolder(), "publish/pat.enc")
        val s = store(f)
        s.save("github_pat_first0000000000000a")
        s.save("github_pat_second000000000000b")
        assertEquals("github_pat_second000000000000b", s.pat())
        s.clear()
        assertFalse(s.hasPat())
        s.clear() // 幂等
    }

    @Test
    fun `引导常量含最小权限说明与官方创建链接`() {
        assertTrue(PatStore.GUIDE_URL.startsWith("https://github.com/settings/personal-access-tokens"))
        assertTrue("Contents" in PatStore.GUIDE_SCOPES)
        assertTrue("加密" in PatStore.GUIDE_SCOPES)
    }
}
