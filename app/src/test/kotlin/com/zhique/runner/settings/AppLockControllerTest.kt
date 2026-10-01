package com.zhique.runner.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.rules.TemporaryFolder

/** M9 应用锁控制器：冷启动即锁、前台恢复上锁、PIN/生物解锁、失败冷却。 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppLockControllerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newPrivacy(): PrivacyPreferences = PrivacyPreferences(
        PreferenceDataStoreFactory.create(
            scope = CoroutineScope(UnconfinedTestDispatcher()),
            produceFile = { File(tmp.newFolder(), "lock-${System.nanoTime()}.preferences_pb") },
        ),
        hashDispatcher = UnconfinedTestDispatcher(),
    )

    @Test
    fun `未开启锁_不上锁`() = runTest {
        val c = AppLockController(newPrivacy(), CoroutineScope(UnconfinedTestDispatcher()))
        advanceUntilIdle()
        c.onForeground()
        advanceUntilIdle()
        assertFalse(c.state.value.locked)
    }

    @Test
    fun `冷启动与前台恢复即锁_PIN解锁`() = runTest {
        val prefs = newPrivacy()
        prefs.setPin("246810")
        var clock = 10_000_000L
        val c = AppLockController(prefs, CoroutineScope(UnconfinedTestDispatcher()), nowMs = { clock })
        advanceUntilIdle()
        assertTrue(c.state.value.locked, "锁开启 → 冷启动处于锁定态")
        assertTrue(c.state.value.pinConfigured)

        c.verifyPin("000000")
        advanceUntilIdle()
        assertTrue(c.state.value.locked, "错误 PIN 不放行")
        assertEquals(1, c.state.value.failCount)

        // 防爆破冷却（Critical-1）：重试须等过冷却截止
        clock += AppLockController.cooldownFor(1) + 1_000
        c.verifyPin("246810")
        advanceUntilIdle()
        assertFalse(c.state.value.locked, "冷却过后正确 PIN 放行")
        assertEquals(0, c.state.value.failCount)

        // 后台 → 回前台：本会话已验证不重锁；onBackground 后需重验
        c.onBackground()
        c.onForeground()
        advanceUntilIdle()
        assertTrue(c.state.value.locked, "后台后回前台重新上锁")
        c.onBiometricSuccess()
        advanceUntilIdle()
        assertFalse(c.state.value.locked, "生物识别成功放行")
    }

    @Test
    fun `失败冷却按幂递增封顶30s`() {
        assertEquals(0L, AppLockController.cooldownFor(0))
        assertEquals(1000L, AppLockController.cooldownFor(1))
        assertEquals(2000L, AppLockController.cooldownFor(2))
        assertEquals(4000L, AppLockController.cooldownFor(3))
        assertEquals(30000L, AppLockController.cooldownFor(6), "封顶 30s")
        assertEquals(30000L, AppLockController.cooldownFor(20))
    }
}
