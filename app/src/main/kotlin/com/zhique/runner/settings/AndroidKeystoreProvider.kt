package com.zhique.runner.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.zhique.core.common.crypto.KeyProvider
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * 主密钥来自 Android Keystore（系统安全区，规格 §4.4：密钥 AES 加密存储，
 * 主密钥在 Keystore）。密钥不可导出，不可被提取；生成一次长期复用。
 */
class AndroidKeystoreProvider(private val alias: String = DEFAULT_ALIAS) : KeyProvider {

    override fun masterKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // 关闭强制随机化 IV：keystore2 对随机化密钥拒绝任何 caller-IV 操作
                // （真机夜间循环：解密持久化密文即抛 Caller-provided IV not permitted）。
                // IV 由 CryptoStore 用 SecureRandom 生成并随密文存储，随机性不降；
                // 硬件绑定（密钥不出安全区）保留。
                .setRandomizedEncryptionRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val DEFAULT_ALIAS = "zhique-master-aes"
    }
}
