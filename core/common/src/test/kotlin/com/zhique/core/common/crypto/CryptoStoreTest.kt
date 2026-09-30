package com.zhique.core.common.crypto

import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class CryptoStoreTest {
    class FakeKeyProvider(val key: SecretKey) : KeyProvider {
        override fun masterKey(): SecretKey = key
    }

    private fun randomKey(): SecretKey =
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun `加密后解密还原`() {
        val kp = FakeKeyProvider(randomKey())
        val store = CryptoStore(kp)
        val ct = store.encrypt("sk-abc-123")
        assertNotEquals("sk-abc-123", ct)
        assertEquals("sk-abc-123", store.decrypt(ct))
    }

    @Test
    fun `同一明文两次加密密文不同`() {
        val store = CryptoStore(FakeKeyProvider(randomKey()))
        assertNotEquals(store.encrypt("x"), store.encrypt("x"))
    }

    @Test
    fun `篡改密文解密失败`() {
        val store = CryptoStore(FakeKeyProvider(randomKey()))
        val ct = store.encrypt("secret")
        val raw = java.util.Base64.getDecoder().decode(ct)
        raw[raw.size - 1] = (raw[raw.size - 1].toInt() xor 0x01).toByte()
        val tampered = java.util.Base64.getEncoder().encodeToString(raw)
        val threw = runCatching { store.decrypt(tampered) }.isFailure
        assertEquals(true, threw)
    }

    @Test
    fun `密钥不同则解密失败`() {
        val store = CryptoStore(FakeKeyProvider(randomKey()))
        val ct = store.encrypt("secret")
        val other = CryptoStore(FakeKeyProvider(randomKey()))
        val threw = runCatching { other.decrypt(ct) }.isFailure
        assertEquals(true, threw)
    }

    @Test
    fun `空字符串与多字节文本往返`() {
        val store = CryptoStore(FakeKeyProvider(randomKey()))
        assertEquals("", store.decrypt(store.encrypt("")))
        val zh = "织雀·API密钥🔐"
        assertEquals(zh, store.decrypt(store.encrypt(zh)))
    }
}
