package com.zhique.runner.permission

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.zhique.core.permission.PermissionRegistry
import com.zhique.core.permission.PState
import com.zhique.core.project.ProjectRepository
import com.zhique.runner.ui.theme.ZqTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 权限中心（规格 §5.3 屏 11）：项目×能力矩阵四色态 chip、点击改状态、
 * 运行中提醒条、全局区密钥库占位、导出权限建议。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PermissionCenterScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var repo: ProjectRepository
    private lateinit var registry: PermissionRegistry
    private lateinit var pid: String

    private fun setup(
        granted: List<String> = emptyList(),
        denied: List<String> = emptyList(),
        usage: Map<String, Int> = emptyMap(),
    ) {
        repo = ProjectRepository(tmp.newFolder())
        pid = repo.create("矩阵项目", "<p></p>").id
        registry = PermissionRegistry(repo)
        granted.forEach { registry.set(pid, it, PState.GRANTED) }
        denied.forEach { registry.set(pid, it, PState.DENIED) }
        repeat(usage["camera"] ?: 0) { registry.recordUse(pid, "camera") }
    }

    private fun scrollTo(tag: String) {
        compose.onNodeWithTag("perm-center-root").performScrollToNode(hasTestTag(tag))
    }

    private fun setContent(focus: String? = null) {
        compose.setContent {
            ZqTheme {
                PermissionCenterScreen(
                    repo = repo,
                    registry = registry,
                    focusProjectId = focus ?: pid,
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `矩阵渲染四态chip`() = runTest {
        setup(granted = listOf("camera"), denied = listOf("mic"), usage = mapOf("camera" to 2))
        setContent()
        advanceUntilIdle()
        compose.waitForIdle()
        compose.onNodeWithTag("cap-chip-camera").assertTextContains("授予")
        compose.onNodeWithTag("cap-chip-mic").assertTextContains("拒绝")
        scrollTo("cap-chip-share")
        compose.onNodeWithTag("cap-chip-share").assertTextContains("未申请")
        scrollTo("cap-chip-camera")
        // 使用次数显示在能力摘要行（chip 右侧为状态文本）
        compose.onNodeWithText("拍照并把照片保存到项目目录 · 已使用 2 次", substring = true).assertExists()
        assertEquals(PState.GRANTED, registry.state(pid, "camera"))
    }

    @Test
    fun `点击chip改状态并即时持久化`() = runTest {
        setup()
        setContent()
        advanceUntilIdle()
        compose.waitForIdle()
        compose.onNodeWithTag("cap-chip-location").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("cap-set-GRANTED-location").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("cap-chip-location").assertTextContains("授予")
        assertEquals(PState.GRANTED, registry.state(pid, "location"), "权限中心直改必须落盘")
        assertEquals("GRANTED", repo.meta(pid).permissions["location"]?.state)

        compose.onNodeWithTag("cap-chip-location").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("cap-set-DENIED-location").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("cap-chip-location").assertTextContains("拒绝")

        compose.onNodeWithTag("cap-chip-location").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("cap-set-NOT_ASKED-location").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("cap-chip-location").assertTextContains("未申请")
        assertEquals(PState.NOT_ASKED, registry.state(pid, "location"), "清除（吊销）语义")
    }

    @Test
    fun `运行中项目显示提醒条_退出后消失`() = runTest {
        setup()
        setContent()
        advanceUntilIdle()
        compose.waitForIdle()
        compose.onNodeWithTag("perm-running-bar").assertDoesNotExist()
        RunningProjects.enter(pid)
        compose.waitForIdle()
        compose.onNodeWithTag("perm-running-bar")
            .assertTextContains("运行中", substring = true)
        RunningProjects.exit(pid)
        compose.waitForIdle()
        compose.onNodeWithTag("perm-running-bar").assertDoesNotExist()
    }

    @Test
    fun `全局区含密钥库状态占位入口`() = runTest {
        setup()
        setContent()
        advanceUntilIdle()
        compose.waitForIdle()
        scrollTo("keystore-entry")
        compose.onNodeWithTag("keystore-entry").assertExists()
        compose.onNodeWithText("密钥库状态 · 备份 · 恢复（M6 接入）").assertExists()
    }

    @Test
    fun `导出建议只列真实使用过的能力`() = runTest {
        setup(usage = mapOf("camera" to 3))
        setContent()
        advanceUntilIdle()
        compose.waitForIdle()
        scrollTo("export-suggest")
        compose.onNodeWithTag("export-suggest").assertTextContains("相机")
        assertTrue(!registry.suggestForExport(pid).contains("mic"))
    }

    @Test
    fun `未使用任何能力时导出建议显示空态`() = runTest {
        setup()
        setContent()
        advanceUntilIdle()
        compose.waitForIdle()
        scrollTo("export-suggest")
        compose.onNodeWithTag("export-suggest").assertTextContains("暂未使用", substring = true)
    }

    @Test
    fun `焦点项目优先选中`() = runTest {
        setup()
        val other = repo.create("另一个项目", "<p></p>").id
        setContent(focus = other)
        advanceUntilIdle()
        compose.waitForIdle()
        compose.onNodeWithTag("perm-project-$other").assertExists()
        compose.onNodeWithTag("perm-project-$pid").assertExists()
    }
}
