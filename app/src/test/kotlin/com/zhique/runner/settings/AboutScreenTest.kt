package com.zhique.runner.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.zhique.core.publish.AssetInfo
import com.zhique.core.publish.ReleaseInfo
import com.zhique.core.publish.UpdateInfo
import com.zhique.runner.ui.theme.ZqTheme
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 关于织雀（规格 §5.3 屏 13）：版本号、检查更新通道语义、更新卡（tag/notes/下载进度/
 * 安装）、更新日志列表、开源仓库与 Apache-2.0。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AboutScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private fun controller(
        scheduler: TestCoroutineScheduler,
        scope: CoroutineScope,
        update: UpdateInfo? = fakeUpdate(),
        logs: List<ReleaseInfo> = fakeLogs(),
        failCheck: Boolean = false,
        failDownload: Boolean = false,
    ): AboutController {
        val io = UnconfinedTestDispatcher(scheduler)
        return AboutController(
            currentVersion = "0.1.0",
            channelProvider = { PublishPreferences.CHANNEL_STABLE },
            check = { channel, current ->
                if (failCheck) throw java.io.IOException("网络不可用")
                update?.takeIf { channel == "stable" && SemVerLike.newer(it.version, current) }
            },
            changelogs = { logs },
            download = { info, dir, onProgress ->
                if (failDownload) throw java.io.IOException("下载中断")
                onProgress(0.5f)
                File(dir, "zhique-${info.version}.apk").apply { writeBytes(ByteArray(16)) }
            },
            downloadsDir = tmp.newFolder(),
            scope = scope,
            io = io,
        )
    }

    /** 测试内联的 semver 缝（不依赖生产私有实现，只为让 fake 更新可被“发现”）。 */
    private object SemVerLike {
        fun newer(candidate: String, current: String) =
            candidate.split('.').zip(current.split('.')).all { (a, b) -> a.toInt() >= b.toInt() }
    }

    private fun fakeUpdate() = UpdateInfo(
        tagName = "v0.2.0", version = "0.2.0", notes = "修复若干问题", prerelease = false,
        htmlUrl = "u", assetName = "zhique-0.2.0.apk", assetUrl = "https://d/apk",
    )

    private fun fakeLogs() = listOf(
        ReleaseInfo(id = 1, tagName = "v0.2.0", body = "修复若干问题", preRelease = false),
        ReleaseInfo(id = 2, tagName = "v0.1.0", body = "首个公开版", preRelease = false),
        ReleaseInfo(id = 3, tagName = "v0.2.0-beta1", body = "beta", preRelease = true),
    )

    private fun setContent(c: AboutController) {
        compose.setContent {
            ZqTheme {
                AboutScreen(controller = c, onBack = {}, onToast = {})
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `版本号仓库与许可证常驻`() = runTest {
        setContent(controller(testScheduler, this))
        compose.onNodeWithTag("about-version").assertTextContains("0.1.0", substring = true)
        compose.onNodeWithTag("about-repo").assertTextContains("github.com/zhique-app/zhique", substring = true)
        compose.onNodeWithTag("about-license").assertTextContains("Apache License 2.0", substring = true)
    }

    @Test
    fun `更新日志列表渲染tag与beta标注`() = runTest {
        setContent(controller(testScheduler, this))
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("about-changelog-item").fetchSemanticsNodes().size == 3
        }
        compose.onAllNodesWithTag("about-changelog-item").assertCountEquals(3)
        compose.onAllNodesWithText("v0.2.0", substring = true)[0].assertExists()
        compose.onAllNodesWithText("beta", substring = true)[0].assertExists()
    }

    @Test
    fun `发现新版本显示更新卡并可下载到安装`() = runTest {
        val c = controller(testScheduler, this)
        setContent(c)
        compose.onNodeWithTag("about-check").performClick()
        compose.waitUntil(10_000) { c.state.value.update != null }
        compose.waitForIdle()
        compose.onNodeWithTag("about-update-tag").assertTextContains("v0.2.0", substring = true)
        compose.onAllNodesWithText("修复若干问题", substring = true)[0].assertExists()
        compose.onNodeWithTag("about-download").performClick()
        compose.waitUntil(10_000) { c.state.value.downloadedApk != null }
        compose.waitForIdle()
        compose.onNodeWithTag("about-install").assertExists()
        assertTrue(c.state.value.progress > 0f, "下载进度回调入状态")
    }

    @Test
    fun `已是最新给明确提示`() = runTest {
        val c = controller(testScheduler, this, update = null)
        setContent(c)
        compose.onNodeWithTag("about-check").performClick()
        compose.waitUntil(10_000) { c.state.value.updateMessage != null }
        compose.onNodeWithTag("about-update-message").assertTextContains("已是最新", substring = true)
    }

    @Test
    fun `检查失败给网络提示`() = runTest {
        val c = controller(testScheduler, this, failCheck = true)
        setContent(c)
        compose.onNodeWithTag("about-check").performClick()
        compose.waitUntil(10_000) { c.state.value.updateMessage?.startsWith("检查失败") == true }
        compose.onNodeWithTag("about-update-message").assertTextContains("检查失败", substring = true)
    }

    @Test
    fun `下载失败给提示且不进入安装态`() = runTest {
        val c = controller(testScheduler, this, failDownload = true)
        setContent(c)
        compose.onNodeWithTag("about-check").performClick()
        compose.waitUntil(10_000) { c.state.value.update != null }
        compose.waitForIdle()
        compose.onNodeWithTag("about-download").performClick()
        compose.waitUntil(10_000) { c.state.value.updateMessage?.startsWith("下载失败") == true }
        assertEquals(null, c.state.value.downloadedApk)
        compose.onAllNodesWithTag("about-install").assertCountEquals(0)
    }
}
