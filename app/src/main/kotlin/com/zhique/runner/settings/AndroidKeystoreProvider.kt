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
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val DEFAULT_ALIAS = "zhique-master-aes"
    }
}
