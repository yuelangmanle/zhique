package com.zhique.runner.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 「隐私与安全」存储（规格 §7，M9）：应用锁（PIN/生物识别开关）、隐私告知记录。
 * PIN 只存「盐 + SHA-256(盐+PIN)」十六进制——原文与可逆密文都不落盘。
 */
class PrivacyPreferences(private val store: DataStore<Preferences>) {

    data class LockSnapshot(
        val enabled: Boolean,
        val biometric: Boolean,
        val pinSalt: String,
        val pinHash: String,
    )

    // ---- 应用锁 ----

    val lockEnabled: Flow<Boolean> = store.data.map { it[LOCK_ENABLED] ?: false }
    val biometricEnabled: Flow<Boolean> = store.data.map { it[LOCK_BIOMETRIC] ?: false }
    private val pinSalt: Flow<String> = store.data.map { it[LOCK_PIN_SALT] ?: "" }
    private val pinHash: Flow<String> = store.data.map { it[LOCK_PIN_HASH] ?: "" }

    suspend fun lockSnapshot(): LockSnapshot = LockSnapshot(
        enabled = lockEnabled.first(),
        biometric = biometricEnabled.first(),
        pinSalt = pinSalt.first(),
        pinHash = pinHash.first(),
    )

    /** 设置/更换 PIN：生成新盐并落哈希。PIN 须 4–8 位数字。 */
    suspend fun setPin(pin: String) {
        require(pin.length in 4..8 && pin.all { it.isDigit() }) { "PIN 须 4–8 位数字" }
        val salt = newSalt()
        store.edit {
            it[LOCK_PIN_SALT] = salt
            it[LOCK_PIN_HASH] = hashPin(salt, pin)
            it[LOCK_ENABLED] = true
        }
    }

    /** 校验 PIN（无 PIN 时不通过）。 */
    suspend fun verifyPin(pin: String): Boolean {
        val s = lockSnapshot()
        if (s.pinHash.isBlank() || s.pinSalt.isBlank()) return false
        return constantTimeEquals(hashPin(s.pinSalt, pin), s.pinHash)
    }

    suspend fun setBiometric(v: Boolean) = store.edit { it[LOCK_BIOMETRIC] = v }

    /** 关闭应用锁：清除哈希与开关。 */
    suspend fun disableLock() = store.edit {
        it[LOCK_ENABLED] = false
        it[LOCK_PIN_HASH] = ""
        it[LOCK_PIN_SALT] = ""
        it[LOCK_BIOMETRIC] = false
    }

    // ---- 隐私告知记录（首次使用 Agent/云 API 前告知「代码将发送至你配置的服务商」） ----

    /** 记录一条告知确认（ISO 时间戳，最新在前，最多 32 条）。 */
    suspend fun recordNotice(scene: String, timestamp: String) {
        store.edit { prefs ->
            val cur = prefs[NOTICES] ?: ""
            val lines = listOf("$timestamp|$scene").plus(cur.split('\n').filter { it.isNotBlank() })
            prefs[NOTICES] = lines.distinct().take(32).joinToString("\n")
        }
    }

    suspend fun notices(): List<Pair<String, String>> =
        (store.data.first()[NOTICES] ?: "")
            .split('\n')
            .filter { it.isNotBlank() }
            .map { line ->
                val i = line.indexOf('|')
                if (i > 0) line.substring(0, i) to line.substring(i + 1) else line to ""
            }

    suspend fun clearNotices() = store.edit { it[NOTICES] = "" }

    companion object {
        val LOCK_ENABLED = booleanPreferencesKey("lock_enabled")
        val LOCK_BIOMETRIC = booleanPreferencesKey("lock_biometric")
        val LOCK_PIN_SALT = stringPreferencesKey("lock_pin_salt")
        val LOCK_PIN_HASH = stringPreferencesKey("lock_pin_hash")
        val NOTICES = stringPreferencesKey("privacy_notices")

        fun newSalt(): String {
            val b = ByteArray(16)
            SecureRandom().nextBytes(b)
            return b.joinToString("") { "%02x".format(it) }
        }

        fun hashPin(salt: String, pin: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest((salt + pin).toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        /** 恒时比较：防时序侧信道。 */
        private fun constantTimeEquals(a: String, b: String): Boolean {
            if (a.length != b.length) return false
            var r = 0
            for (i in a.indices) r = r or (a[i].code xor b[i].code)
            return r == 0
        }
    }
}
