package com.zhique.runner.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 质量审查 Critical-1：应用锁冷却递减与持久化。
 * - 输错 → 冷却置入且逐秒递减归零后可再试（不死锁）；
 * - 中途「杀进程」重建控制器 → 冷却/失败计数仍在（防爆破有效）；
 * - 冷却期内 verifyPin 直接拒绝（防 UI 绕过）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppLockCooldownTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newPrivacy() = PrivacyPreferences(
        PreferenceDataStoreFactory.create(
            scope = CoroutineScope(UnconfinedTestDispatcher()),
            produceFile = { File(tmp.newFolder(), "cd-${System.nanoTime()}.preferences_pb") },
        ),
        hashDispatcher = UnconfinedTestDispatcher(),
    )

    @Test
    fun `输错一次_冷却递减归零后可再试`() = runTest {
        val prefs = newPrivacy()
        prefs.setPin("135790")
        var clock = 1_000_000L
        val c = AppLockController(prefs, CoroutineScope(UnconfinedTestDispatcher()), nowMs = { clock })
        advanceUntilIdle()
        assertTrue(c.state.value.locked)

        c.verifyPin("000000")
        advanceUntilIdle()
        val afterFail = c.state.value
        assertEquals(1, afterFail.failCount)
        assertEquals(AppLockController.cooldownFor(1), afterFail.cooldownMs, "冷却置入 1s")

        // 冷却期内 verifyPin 直接拒绝（不烧 PBKDF2、不叠失败计数）
        clock += 500
        c.verifyPin("135790")
        advanceUntilIdle()
        assertTrue(c.state.value.locked, "冷却期内即使 PIN 正确也拒绝")
        assertEquals(1, c.state.value.failCount, "冷却期拒绝不计新失败")

        // 冷却归零（时钟推进过截止点 + 递减协程走一拍）→ 正确 PIN 放行
        clock += AppLockController.cooldownFor(1) + 2_000
        c.verifyPin("135790")
        advanceUntilIdle()
        assertFalse(c.state.value.locked, "冷却归零后正确 PIN 放行（会话不死锁）")
        assertEquals(0, c.state.value.failCount)
    }

    @Test
    fun `杀进程重建_冷却与失败计数仍在`() = runTest {
        val prefs = newPrivacy()
        prefs.setPin("246810")
        var clock = 2_000_000L
        val first = AppLockController(prefs, CoroutineScope(UnconfinedTestDispatcher()), nowMs = { clock })
        advanceUntilIdle()
        first.verifyPin("000000")
        advanceUntilIdle()
        assertEquals(1, first.state.value.failCount)
        assertEquals(AppLockController.cooldownFor(1), first.state.value.cooldownMs)

        // 「杀进程」：丢弃控制器，用同一份 DataStore 重建（时钟只走 300ms，冷却未到期）
        clock += 300
        val second = AppLockController(prefs, CoroutineScope(UnconfinedTestDispatcher()), nowMs = { clock })
        advanceUntilIdle()
        assertEquals(1, second.state.value.failCount, "失败计数持久化，杀进程不清")
        assertTrue(second.state.value.cooldownMs > 0, "冷却按截止时间戳折算剩余，杀进程不清")
        assertTrue(second.state.value.locked)

        // 冷却期内重建后仍拒绝
        second.verifyPin("246810")
        advanceUntilIdle()
        assertTrue(second.state.value.locked, "重建后冷却期内仍拒绝")
    }

    @Test
    fun `连续失败冷却按截止时间戳折算剩余`() = runTest {
        val prefs = newPrivacy()
        prefs.setPin("112233")
        var clock = 3_000_000L
        val c = AppLockController(prefs, CoroutineScope(UnconfinedTestDispatcher()), nowMs = { clock })
        advanceUntilIdle()
        c.verifyPin("999999")
        advanceUntilIdle()
        val cooldown1 = c.state.value.cooldownMs
        // 第一次冷却期间 verifyPin 被闸拒（不叠计数），推进过截止后再错一次 → 2s
        clock += cooldown1 + 1_000
        c.verifyPin("999999")
        advanceUntilIdle()
        assertEquals(2, c.state.value.failCount)
        assertEquals(AppLockController.cooldownFor(2), c.state.value.cooldownMs)
        assertTrue(c.state.value.cooldownMs > cooldown1, "逐次翻倍")
    }

    @Test
    fun `正确PIN后成功清零持久化状态`() = runTest {
        val prefs = newPrivacy()
        prefs.setPin("445566")
        var clock = 4_000_000L
        val c = AppLockController(prefs, CoroutineScope(UnconfinedTestDispatcher()), nowMs = { clock })
        advanceUntilIdle()
        c.verifyPin("445566")
        advanceUntilIdle()
        val snap = prefs.lockSnapshot()
        assertEquals(0, snap.failCount, "成功解锁清零失败计数（落盘）")
        assertEquals(0L, snap.cooldownUntilMs)
    }

    @Test
    fun `冷却时序协程逐秒递减`() = runTest {
        val prefs = newPrivacy()
        prefs.setPin("778899")
        // 假时钟绑定虚拟调度时间：递减协程每走 1s 虚拟时间，时钟同步前进
        val start = 5_000_000L
        val c = AppLockController(
            prefs,
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)), // 共享调度器：delay 受 advanceTimeBy 驱动
            nowMs = { start + testScheduler.currentTime },
        )
        advanceUntilIdle()
        c.verifyPin("000000")
        // Unconfined：verifyPin 同步走到 ticker 的 delay 挂起，冷却已置入
        assertEquals(1000L, c.state.value.cooldownMs)
        // 1s tick 到点：时钟同步前进 → 折算剩余归零、协程自停
        testScheduler.advanceTimeBy(1_000)
        advanceUntilIdle() // tick 内 DataStore 读完成后 Unconfined 续跑排回调度器
        assertTrue(c.state.value.cooldownMs <= 0L, "递减归零自停：${c.state.value.cooldownMs}")
    }
}
