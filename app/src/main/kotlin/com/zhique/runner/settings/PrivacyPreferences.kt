package com.zhique.runner.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * 「隐私与安全」存储（规格 §7）：应用锁（PIN/生物识别开关）、隐私告知记录、
 * 防爆破状态（失败计数 + 冷却截止，落盘杀进程不清）。
 *
 * PIN 哈希口径（质量审查 Important-3）：
 * - v2（现行）：PBKDF2WithHmacSHA256，[PBKDF2_ITERATIONS]（≥120k）迭代，128 位随机盐，
 *   存「pbkdf2$iterations$salt$hash」版本化串——≤8 位数字 PIN 离线爆破成本抬高 5 个数量级；
 * - v1（历史）：单轮 SHA-256(盐+PIN)——仅作兼容迁移：[verifyPin] 命中旧格式且 PIN 正确时
 *   立即用 v2 重哈希回写（透明升级，下次即为 PBKDF2）。
 * 原文与可逆密文都不落盘；manifest 层 allowBackup=false 兜底（备份不带走盐+哈希）。
 */
class PrivacyPreferences(
    private val store: DataStore<Preferences>,
    /** PBKDF2 计算派发（默认 IO；测试注入 test dispatcher 保确定性）。 */
    private val hashDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
) {

    data class LockSnapshot(
        val enabled: Boolean,
        val biometric: Boolean,
        val pinSalt: String,
        val pinHash: String,
        val failCount: Int,
        val cooldownUntilMs: Long,
    )

    // ---- 应用锁 ----

    val lockEnabled: Flow<Boolean> = store.data.map { it[LOCK_ENABLED] ?: false }
    val biometricEnabled: Flow<Boolean> = store.data.map { it[LOCK_BIOMETRIC] ?: false }
    private val pinSalt: Flow<String> = store.data.map { it[LOCK_PIN_SALT] ?: "" }
    private val pinHash: Flow<String> = store.data.map { it[LOCK_PIN_HASH] ?: "" }
    val failCount: Flow<Int> = store.data.map { it[LOCK_FAIL_COUNT] ?: 0 }
    val cooldownUntilMs: Flow<Long> = store.data.map { it[LOCK_COOLDOWN_UNTIL] ?: 0L }

    suspend fun lockSnapshot(): LockSnapshot = LockSnapshot(
        enabled = lockEnabled.first(),
        biometric = biometricEnabled.first(),
        pinSalt = pinSalt.first(),
        pinHash = pinHash.first(),
        failCount = failCount.first(),
        cooldownUntilMs = cooldownUntilMs.first(),
    )

    /** 设置/更换 PIN：生成新盐并落哈希（PBKDF2 v2 版本化格式），同时清零防爆破状态。 */
    suspend fun setPin(pin: String) {
        require(pin.length in 4..8 && pin.all { it.isDigit() }) { "PIN 须 4–8 位数字" }
        val salt = newSalt()
        store.edit {
            it[LOCK_PIN_SALT] = salt
            it[LOCK_PIN_HASH] = hashPinV2(salt, pin)
            it[LOCK_ENABLED] = true
            it[LOCK_FAIL_COUNT] = 0
            it[LOCK_COOLDOWN_UNTIL] = 0L
        }
    }

    /**
     * 校验 PIN（无 PIN 时不通过）。
     * IO 派发：PBKDF2 120k 迭代 ~100ms 级，不能占主线程。
     * v1 旧哈希命中 → 透明迁移到 v2（重盐重哈希回写）。
     */
    suspend fun verifyPin(pin: String): Boolean = withContext(hashDispatcher) {
        val s = lockSnapshot()
        if (s.pinHash.isBlank() || s.pinSalt.isBlank()) return@withContext false
        val stored = s.pinHash
        val ok = if (stored.startsWith(V2_PREFIX)) {
            constantTimeEquals(verifyV2(stored, pin), stored)
        } else {
            // v1 兼容：单轮 SHA-256(盐+PIN)
            constantTimeEquals(legacyHash(s.pinSalt, pin), stored)
        }
        if (ok && !stored.startsWith(V2_PREFIX)) {
            // 透明迁移：旧哈希首次解锁成功 → PBKDF2 重哈希
            store.edit {
                it[LOCK_PIN_SALT] = newSalt()
                it[LOCK_PIN_HASH] = hashPinV2(it[LOCK_PIN_SALT] ?: "", pin)
            }
        }
        ok
    }

    suspend fun setBiometric(v: Boolean) = store.edit { it[LOCK_BIOMETRIC] = v }

    /** 关闭应用锁：清除哈希与防爆破状态。 */
    suspend fun disableLock() = store.edit {
        it[LOCK_ENABLED] = false
        it[LOCK_PIN_HASH] = ""
        it[LOCK_PIN_SALT] = ""
        it[LOCK_BIOMETRIC] = false
        it[LOCK_FAIL_COUNT] = 0
        it[LOCK_COOLDOWN_UNTIL] = 0L
    }

    /** 防爆破状态落盘（C1）：失败计数 + 冷却截止时间戳，杀进程不清。 */
    suspend fun recordFailure(count: Int, cooldownUntilMs: Long) = store.edit {
        it[LOCK_FAIL_COUNT] = count
        it[LOCK_COOLDOWN_UNTIL] = cooldownUntilMs
    }

    suspend fun clearFailureState() = store.edit {
        it[LOCK_FAIL_COUNT] = 0
        it[LOCK_COOLDOWN_UNTIL] = 0L
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

    /** 测试夹具直写通道（v1 迁移用例构造历史格式）。 */
    internal fun storeInternalForTest(): DataStore<Preferences> = store

    companion object {
        val LOCK_ENABLED = booleanPreferencesKey("lock_enabled")
        val LOCK_BIOMETRIC = booleanPreferencesKey("lock_biometric")
        val LOCK_PIN_SALT = stringPreferencesKey("lock_pin_salt")
        val LOCK_PIN_HASH = stringPreferencesKey("lock_pin_hash")
        val LOCK_FAIL_COUNT = intPreferencesKey("lock_fail_count")
        val LOCK_COOLDOWN_UNTIL = longPreferencesKey("lock_cooldown_until")
        val NOTICES = stringPreferencesKey("privacy_notices")

        /** PBKDF2 迭代次数（质量审查 Important-3：≥120k，OWASP 2023 基线）。 */
        const val PBKDF2_ITERATIONS = 120_000
        const val PBKDF2_KEY_BITS = 256
        const val V2_PREFIX = "pbkdf2$"

        fun newSalt(): String {
            val b = ByteArray(16)
            SecureRandom().nextBytes(b)
            return b.joinToString("") { "%02x".format(it) }
        }

        /** v2 版本化哈希：`pbkdf2$iterations$salt$hexhash`。 */
        fun hashPinV2(salt: String, pin: String, iterations: Int = PBKDF2_ITERATIONS): String =
            "$V2_PREFIX$iterations$salt${pbkdf2(salt, pin, iterations)}"

        /** 校验输入 PIN 是否与 v2 哈希匹配（返回匹配格式的重算串供恒时比较）。 */
        fun verifyV2(stored: String, pin: String): String {
            // 格式：pbkdf2$<iter><salt(32 hex)><hash(64 hex)>。
            // 迭代数与盐的边界不能按「首个非数字」切（盐是 hex、可以全数字），
            // 用定长尾段反推：盐起于 len-96，哈希 64 hex 收尾。
            val body = stored.removePrefix(V2_PREFIX)
            require(body.length > 96) { "v2 哈希格式损坏" }
            val saltStart = body.length - 96
            val iterations = body.substring(0, saltStart).toInt()
            require(iterations in 1..10_000_000) { "v2 迭代数越界" }
            val salt = body.substring(saltStart, saltStart + 32)
            return "$V2_PREFIX$iterations$salt${pbkdf2(salt, pin, iterations)}"
        }

        /** PBKDF2WithHmacSHA256（IO 线程调用）。 */
        fun pbkdf2(salt: String, pin: String, iterations: Int): String {
            val spec = PBEKeySpec(
                pin.toCharArray(),
                salt.toByteArray(Charsets.UTF_8),
                iterations,
                PBKDF2_KEY_BITS,
            )
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            return factory.generateSecret(spec).encoded
                .joinToString("") { "%02x".format(it) }
        }

        /** v1 旧口径（仅兼容迁移）：单轮 SHA-256(盐+PIN)。 */
        fun legacyHash(salt: String, pin: String): String =
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
