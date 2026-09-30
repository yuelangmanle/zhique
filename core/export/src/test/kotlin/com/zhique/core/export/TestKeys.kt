package com.zhique.core.export

import com.zhique.core.common.crypto.KeyProvider
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** 测试用软件 AES 主密钥（生产为 Android Keystore——见 AndroidKeystoreProvider）。 */
internal class SoftwareKey : KeyProvider {
    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    override fun masterKey(): SecretKey = key
}

internal fun softwareKey(): KeyProvider = SoftwareKey()
