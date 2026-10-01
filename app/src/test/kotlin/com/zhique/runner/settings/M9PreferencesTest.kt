package com.zhique.runner.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.rules.TemporaryFolder

/**
 * M9 Task 9.2 设置全集存储层：输出·思考·上下文 / Web(eruda) / 通用 / 隐私与安全
 * / 发布与同步推送偏好。DataStore 进程内临时文件。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class M9PreferencesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newAi() = AiPreferences(newDataStore())
    private fun newWeb() = WebPreferences(newDataStore())
    private fun newGeneral() = GeneralPreferences(newDataStore())
    private fun newPrivacy() = PrivacyPreferences(newDataStore())
    private fun newPublish() = PublishPreferences(newDataStore())

    private fun newDataStore() = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(UnconfinedTestDispatcher()),
        produceFile = { File(tmp.newFolder(), "t-${System.nanoTime()}.preferences_pb") },
    )

    // ---- 输出·思考·上下文（规格 §7 默认值 16384/3/折叠/0/75/80） ----

    @Test
    fun `aiPrefs默认值与规格一致`() = runTest {
        val s = newAi().snapshot()
        assertEquals(16384, s.maxOutputTokens)
        assertEquals(3, s.continueSegments)
        assertTrue(s.thinkingCollapsed, "思考默认折叠")
        assertEquals(0, s.contextLimit, "0 = 按模型窗口")
        assertEquals(75, s.budgetPercent)
        assertEquals(80, s.compactThresholdPercent)
    }

    @Test
    fun `aiPrefs写入往返与越界拒绝`() = runTest {
        val p = newAi()
        p.setMaxOutputTokens(8192)
        p.setContinueSegments(5)
        p.setContextLimit(0)
        p.setBudgetPercent(60)
        p.setCompactThresholdPercent(70)
        val s = p.snapshot()
        assertEquals(8192, s.maxOutputTokens)
        assertEquals(5, s.continueSegments)
        assertEquals(60, s.budgetPercent)
        assertEquals(70, s.compactThresholdPercent)
        assertFailsWith<IllegalArgumentException> { p.setMaxOutputTokens(100) }
        assertFailsWith<IllegalArgumentException> { p.setContinueSegments(11) }
        assertFailsWith<IllegalArgumentException> { p.setBudgetPercent(5) }
        assertFailsWith<IllegalArgumentException> { p.setCompactThresholdPercent(99) }
    }

    // ---- Web（eruda 开关） ----

    @Test
    fun `webPrefs_eruda开关往返`() = runTest {
        val p = newWeb()
        assertFalse(p.erudaNow(), "eruda 默认关")
        p.setErudaEnabled(true)
        assertTrue(p.erudaNow())
        p.setDownloadBehavior(WebPreferences.DOWNLOAD_DIRECT)
        assertEquals(WebPreferences.DOWNLOAD_DIRECT, p.downloadBehavior.first())
        assertFailsWith<IllegalArgumentException> { p.setDownloadBehavior("xxx") }
    }

    // ---- 通用 ----

    @Test
    fun `generalPrefs布局悬浮球与通知三开关`() = runTest {
        val p = newGeneral()
        assertEquals(GeneralPreferences.LAYOUT_DRAWER, p.runnerDefaultLayout.first())
        p.setRunnerDefaultLayout(GeneralPreferences.LAYOUT_BUBBLE)
        p.setBubblePosition(0.2f, 0.7f)
        p.setNotifyExportDone(false)
        p.setNotifyAgentDone(false)
        p.setNotifyNewVersion(false)
        val s = p.snapshot()
        assertEquals(GeneralPreferences.LAYOUT_BUBBLE, s.runnerDefaultLayout)
        assertEquals(0.2f, s.bubbleX)
        assertEquals(0.7f, s.bubbleY)
        assertFalse(s.notifyExportDone)
        assertFalse(s.notifyAgentDone)
        assertFalse(s.notifyNewVersion)
        assertFailsWith<IllegalArgumentException> { p.setRunnerDefaultLayout("floating") }
        assertFailsWith<IllegalArgumentException> { p.setBubblePosition(2f, 0.5f) }
        assertFailsWith<IllegalArgumentException> { p.setEditorFontSize(3) }
    }

    // ---- 隐私与安全（应用锁 PIN 哈希 + 告知记录） ----

    @Test
    fun `privacyPrefs_PIN哈希存取与校验`() = runTest {
        val p = newPrivacy()
        p.setPin("864201")
        val s = p.lockSnapshot()
        assertTrue(s.enabled)
        assertTrue(s.pinHash.isNotBlank())
        assertTrue(s.pinSalt.isNotBlank())
        assertNotEquals("864201", s.pinHash, "PIN 不得明文落盘")
        assertTrue(p.verifyPin("864201"), "正确 PIN 通过")
        assertFalse(p.verifyPin("000000"), "错误 PIN 拒绝")
        assertFalse(p.verifyPin(""), "空 PIN 拒绝")
        assertFailsWith<IllegalArgumentException> { p.setPin("abc") }
        assertFailsWith<IllegalArgumentException> { p.setPin("123") }
        p.disableLock()
        assertFalse(p.lockSnapshot().enabled)
        assertFalse(p.verifyPin("864201"), "关闭后哈希清除")
    }

    @Test
    fun `privacyPrefs_PIN同盐同哈希_不同盐不同哈希`() {
        val s1 = PrivacyPreferences.newSalt()
        val s2 = PrivacyPreferences.newSalt()
        assertNotEquals(s1, s2)
        assertEquals(PrivacyPreferences.hashPin(s1, "1234"), PrivacyPreferences.hashPin(s1, "1234"))
        assertNotEquals(PrivacyPreferences.hashPin(s1, "1234"), PrivacyPreferences.hashPin(s2, "1234"))
    }

    @Test
    fun `privacyPrefs_告知记录追加查看清理`() = runTest {
        val p = newPrivacy()
        p.recordNotice("agent", "2026-09-30T10:00:00Z")
        p.recordNotice("chat", "2026-09-30T11:00:00Z")
        val notices = p.notices()
        assertEquals(2, notices.size)
        assertEquals("chat", notices.first().second, "最新在前")
        p.clearNotices()
        assertTrue(p.notices().isEmpty())
    }

    // ---- 发布与同步推送偏好 ----

    @Test
    fun `publishPrefs推送偏好默认与写入`() = runTest {
        val p = newPublish()
        assertEquals(PublishPreferences.COMMIT_ZH, p.commitLanguage.first(), "commit 语言默认中文")
        assertEquals("main", p.defaultBranch.first())
        assertTrue(p.newRepoPrivate.first(), "新仓默认私有")
        p.setCommitLanguage(PublishPreferences.COMMIT_EN)
        p.setDefaultBranch("develop")
        p.setNewRepoPrivate(false)
        assertEquals(PublishPreferences.COMMIT_EN, p.commitLanguage.first())
        assertEquals("develop", p.defaultBranch.first())
        assertFalse(p.newRepoPrivate.first())
        assertFailsWith<IllegalArgumentException> { p.setDefaultBranch("") }
    }
}
