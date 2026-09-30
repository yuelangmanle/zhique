package com.zhique.core.common.crypto

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 主密钥抽象：生产环境由 Android Keystore 提供，纯 JVM 测试用可注入的假实现。 */
interface KeyProvider {
    fun masterKey(): SecretKey
}

/** AES-256-GCM 加解密，密文格式：Base64(12字节IV + ciphertext+tag)。 */
class CryptoStore(private val kp: KeyProvider) {
    private val random = SecureRandom()

    fun encrypt(plain: String): String {
        val iv = ByteArray(IV_LEN).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, kp.masterKey(), GCMParameterSpec(TAG_BITS, iv))
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().withoutPadding().encodeToString(iv + ct)
    }

    fun decrypt(encoded: String): String {
        val all = Base64.getDecoder().decode(encoded)
        require(all.size > IV_LEN) { "ciphertext too short" }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, kp.masterKey(), GCMParameterSpec(TAG_BITS, all, 0, IV_LEN))
        return String(cipher.doFinal(all, IV_LEN, all.size - IV_LEN), Charsets.UTF_8)
    }

    private companion object {
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_LEN = 12
        const val TAG_BITS = 128
    }
}
