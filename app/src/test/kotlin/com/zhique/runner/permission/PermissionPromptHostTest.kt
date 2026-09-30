package com.zhique.runner.permission

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.zhique.core.permission.PermissionAsk
import com.zhique.runner.ui.theme.ZqTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 授权卡（规格 §4.6）：哪个项目、要什么、干什么；授予/拒绝结算挂起的
 * request()；无卡不渲染；多请求排队串行弹。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PermissionPromptHostTest {

    @get:Rule
    val compose = createComposeRule()

    private fun setContent(prompt: AppPermissionPrompt) {
        compose.setContent { ZqTheme { PermissionPromptHost(prompt) } }
        compose.waitForIdle()
    }

    @Test
    fun `授权卡展示项目能力与理由_授予结算true`() = runTest {
        val prompt = AppPermissionPrompt()
        setContent(prompt)
        val job = launch {
            assertTrue(
                prompt.ask(PermissionAsk("pid-1", "星空示例", "camera", "拍照并把照片保存到项目目录")),
                "点授予必须结算 true",
            )
        }
        advanceUntilIdle()
        compose.waitForIdle()
        compose.onNodeWithText("「星空示例」想使用相机").assertExists()
        compose.onNodeWithTag("perm-card-title").assertExists()
        compose.onNodeWithTag("perm-grant").performClick()
        job.join()
        advanceUntilIdle()
        compose.waitForIdle()
        assertEquals(null, prompt.current.value, "结算后卡片关闭")
    }

    @Test
    fun `拒绝结算false且网页拿denied语义`() = runTest {
        val prompt = AppPermissionPrompt()
        setContent(prompt)
        var result = true
        val job = launch { result = prompt.ask(PermissionAsk("pid-1", "星空示例", "location", "展示附近天气")) }
        advanceUntilIdle()
        compose.waitForIdle()
        compose.onNodeWithTag("perm-deny").performClick()
        job.join()
        assertEquals(false, result, "点拒绝必须结算 false")
    }

    @Test
    fun `无挂起授权卡时不渲染`() = runTest {
        setContent(AppPermissionPrompt())
        compose.onNodeWithTag("perm-card").assertDoesNotExist()
    }

    @Test
    fun `多请求排队串行弹卡`() = runTest {
        val prompt = AppPermissionPrompt()
        setContent(prompt)
        var first = false
        var second = false
        val j1 = launch { first = prompt.ask(PermissionAsk("p1", "项目甲", "camera", "拍照")) }
        advanceUntilIdle()
        compose.waitForIdle()
        compose.onNodeWithText("「项目甲」想使用相机").assertExists()
        compose.onNodeWithTag("perm-grant").performClick()
        j1.join()
        val j2 = launch { second = prompt.ask(PermissionAsk("p2", "项目乙", "mic", "录音")) }
        advanceUntilIdle()
        compose.waitForIdle()
        compose.onNodeWithText("「项目乙」想使用麦克风").assertExists()
        compose.onNodeWithTag("perm-deny").performClick()
        j2.join()
        assertTrue(first && !second)
    }
}
