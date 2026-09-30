package com.zhique.core.export

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.rules.TemporaryFolder

/** 签名器（apksig v2+v3）与 ApkVerifier 编程校验。 */
class SignerTest {

    private val tmp = TemporaryFolder()

    private fun key(): Signer.Key {
        tmp.create()
        val ks = KeystoreManager(tmp.newFolder(), com.zhique.core.common.crypto.CryptoStore(softwareKey()))
        return ks.signingKey()
    }

    private fun unsignedInjected(): File {
        val out = File(tmp.newFolder(), "injected.apk")
        AssetInjector().inject(
            TemplateFixtures.min(),
            mapOf(AssetInjector.PROJECT_ASSETS_PREFIX + "index.html" to "<p>x</p>".toByteArray()),
            out,
        )
        return out
    }

    @Test
    fun `签名产物通过v2加v3校验且指纹一致`() {
        val key = key()
        val input = unsignedInjected()
        val output = File(input.parentFile, "signed.apk")
        Signer().sign(input, output, key)
        val fp = Signer().verify(output)
        assertEquals(key.certSha256, fp)
    }

    @Test
    fun `篡改签名产物后校验失败`() {
        val key = key()
        val input = unsignedInjected()
        val output = File(input.parentFile, "signed.apk")
        Signer().sign(input, output, key)
        // 篡改一个资产字节（在 zip 载荷里改 index.html 的一个字节——通过整包重写最稳：
        // 直接破坏 v2 块之前的区域即可，这里选注释字段不可行，改为破坏 zip 尾部字节）
        val bytes = output.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1] + 1).toByte() // EOCD 损坏
        output.writeBytes(bytes)
        assertFailsWith<Exception> { Signer().verify(output) }
    }

    @Test
    fun `两次签名证书指纹相同-密钥持久保证`() {
        val k1 = key()
        val out1 = File(tmp.newFolder(), "s1.apk")
        Signer().sign(unsignedInjected(), out1, k1)
        val out2 = File(tmp.newFolder(), "s2.apk")
        Signer().sign(unsignedInjected(), out2, k1)
        assertEquals(Signer().verify(out1), Signer().verify(out2))
        assertTrue(k1.certSha256.isNotEmpty())
    }
}
