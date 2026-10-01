package com.zhique.runner.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 质量审查 Important-3：PIN 哈希升级 PBKDF2WithHmacSHA256（≥120k 迭代）
 * + v1 旧哈希透明迁移 + 版本化存储格式。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Pbkdf2PinHashTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newPrivacy() = PrivacyPreferences(
        PreferenceDataStoreFactory.create(
            scope = CoroutineScope(UnconfinedTestDispatcher()),
            produceFile = { File(tmp.newFolder(), "pb-${System.nanoTime()}.preferences_pb") },
        ),
        hashDispatcher = UnconfinedTestDispatcher(),
    )

    @Test
    fun `PBKDF2参数达标且迭代敏感`() {
        assertEquals(120_000, PrivacyPreferences.PBKDF2_ITERATIONS, "≥120k 迭代（OWASP 基线）")
        val t0 = System.nanoTime()
        val h = PrivacyPreferences.hashPinV2(PrivacyPreferences.newSalt(), "123456")
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000
        assertTrue(elapsedMs < 2_000, "单次计算须在秒级内（实测 ${elapsedMs}ms）")
        assertTrue(h.startsWith(PrivacyPreferences.V2_PREFIX), "版本化前缀")
        val parts = h.removePrefix(PrivacyPreferences.V2_PREFIX)
        assertTrue(parts.startsWith("120000"), "迭代数入串：${h.take(16)}…")
        assertEquals(32 + 64 + 6, parts.length, "iter(6)+salt(32)+hash(64)")
    }

    @Test
    fun `同PIN不同盐不同哈希_同PIN同盐确定`() {
        val s1 = PrivacyPreferences.newSalt()
        val s2 = PrivacyPreferences.newSalt()
        assertNotEquals(s1, s2)
        assertNotEquals(PrivacyPreferences.hashPinV2(s1, "1234"), PrivacyPreferences.hashPinV2(s2, "1234"))
        assertEquals(
            PrivacyPreferences.hashPinV2(s1, "1234"),
            PrivacyPreferences.hashPinV2(s1, "1234"),
        )
    }

    @Test
    fun `verifyV2重算恒等于原串_错PIN不等`() {
        val stored = PrivacyPreferences.hashPinV2(PrivacyPreferences.newSalt(), "9876")
        assertEquals(stored, PrivacyPreferences.verifyV2(stored, "9876"))
        assertNotEquals(stored, PrivacyPreferences.verifyV2(stored, "0000"))
    }

    @Test
    fun `setPin落盘v2格式_verify往返`() = runTest {
        val p = newPrivacy()
        p.setPin("24680")
        val snap = p.lockSnapshot()
        assertTrue(snap.pinHash.startsWith(PrivacyPreferences.V2_PREFIX), "新设置 PIN 即 PBKDF2 v2")
        assertFalse(snap.pinHash.contains("24680"), "PIN 不得出现在哈希串")
        assertTrue(p.verifyPin("24680"))
        assertFalse(p.verifyPin("13579"))
    }

    @Test
    fun `v1旧哈希首次解锁成功透明迁移到v2`() = runTest {
        val p = newPrivacy()
        // 手工落一个 v1 旧格式（模拟历史版本用户）
        val salt = PrivacyPreferences.newSalt()
        val legacy = PrivacyPreferences.legacyHash(salt, "7777")
        p.storeInternalForTest().edit { prefs ->
            prefs[PrivacyPreferences.LOCK_ENABLED] = true
            prefs[PrivacyPreferences.LOCK_PIN_SALT] = salt
            prefs[PrivacyPreferences.LOCK_PIN_HASH] = legacy
        }
        assertTrue(p.verifyPin("7777"), "v1 旧哈希正确 PIN 仍通过")
        val after = p.lockSnapshot()
        assertTrue(after.pinHash.startsWith(PrivacyPreferences.V2_PREFIX), "命中旧格式 → 透明迁移 PBKDF2")
        assertNotEquals(salt, after.pinSalt, "迁移重盐")
        assertTrue(p.verifyPin("7777"), "迁移后仍可验证")
        assertFalse(p.verifyPin("0000"))
        // 错 PIN 不触发迁移
        val p2 = newPrivacy()
        val salt2 = PrivacyPreferences.newSalt()
        p2.storeInternalForTest().edit { prefs ->
            prefs[PrivacyPreferences.LOCK_ENABLED] = true
            prefs[PrivacyPreferences.LOCK_PIN_SALT] = salt2
            prefs[PrivacyPreferences.LOCK_PIN_HASH] = PrivacyPreferences.legacyHash(salt2, "8888")
        }
        assertFalse(p2.verifyPin("1111"))
        assertFalse(
            p2.lockSnapshot().pinHash.startsWith(PrivacyPreferences.V2_PREFIX),
            "错 PIN 不得触发迁移",
        )
    }

    @Test
    fun `防爆破状态落盘_roundtrip`() = runTest {
        val p = newPrivacy()
        p.setPin("9999")
        p.recordFailure(3, 123_456_789L)
        val snap = p.lockSnapshot()
        assertEquals(3, snap.failCount)
        assertEquals(123_456_789L, snap.cooldownUntilMs)
        p.clearFailureState()
        assertEquals(0, p.lockSnapshot().failCount)
        assertEquals(0L, p.lockSnapshot().cooldownUntilMs)
    }
}
