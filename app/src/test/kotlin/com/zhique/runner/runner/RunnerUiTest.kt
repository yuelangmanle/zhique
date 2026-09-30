package com.zhique.runner.runner

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.zhique.core.web.CapabilityReport
import com.zhique.core.web.debug.DebugEvent
import com.zhique.core.web.debug.Timeline
import com.zhique.core.web.debug.TimelineReducer
import com.zhique.runner.ui.theme.ZqTheme
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private val emptyTimeline = Timeline(emptyList(), emptyList(), emptyList(), emptyList())

/** 触发被测组合函数内 LaunchedEffect 重跑的载体。 */
private class TargetHolder {
    var value by mutableStateOf<Float?>(null)
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RunnerUiTest {

    @get:Rule
    val compose = createComposeRule()

    // ---- 三模式切换 ----

    @Test
    fun `切换器三态点击回传`() {
        var current: RunnerMode? = null
        compose.setContent {
            ZqTheme {
                RunnerModeSwitcher(RunnerMode.DRAWER, { current = it }, Modifier.testTag("switcher"))
            }
        }
        compose.onNodeWithTag("switcher").assertExists()
        compose.onNodeWithText("分屏").performClick()
        assertEquals(RunnerMode.SPLIT, current)
        compose.onNodeWithText("悬浮球").performClick()
        assertEquals(RunnerMode.BUBBLE, current)
    }

    @Test
    fun `三模式内容跟随切换`() {
        var mode by mutableStateOf(RunnerMode.DRAWER)
        compose.setContent {
            ZqTheme {
                RunnerContent(
                    projectName = "示例",
                    mode = mode,
                    onModeChange = { mode = it },
                    timeline = emptyTimeline,
                    capability = CapabilityReport(false, true, false),
                    onBack = {},
                    onSendToAgent = {},
                    onReload = {},
                    webView = { m -> Box(m) },
                )
            }
        }
        compose.onNodeWithTag("web-host").assertExists()
        compose.onNodeWithTag("drawer-handle").assertExists()

        // DRAWER 模式下顶栏与抽屉内各有一个切换器，点顶栏那个
        compose.onAllNodesWithTag("mode-SPLIT")[0].performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("split-divider").assertExists()
        compose.onNodeWithTag("ai-panel").assertExists()

        compose.onAllNodesWithTag("mode-BUBBLE")[0].performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("bubble-ball").assertExists()

        compose.onAllNodesWithTag("mode-DRAWER")[0].performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("drawer-handle").assertExists()
    }

    // ---- 抽屉三档吸附 ----

    @Test
    fun `抽屉吸附档位纯函数`() {
        // 就近
        assertEquals(DrawerSnap.PEEK, DrawerSnap.target(0.13f, 0f))
        assertEquals(DrawerSnap.HALF, DrawerSnap.target(0.4f, 0f))
        // 快速上滑展开一档、快速下滑收起一档（负速度=向上）
        assertEquals(DrawerSnap.HALF, DrawerSnap.target(DrawerSnap.PEEK, -3f))
        assertEquals(DrawerSnap.FULL, DrawerSnap.target(DrawerSnap.HALF, -3f))
        assertEquals(DrawerSnap.HALF, DrawerSnap.target(DrawerSnap.FULL, 3f))
        assertEquals(DrawerSnap.PEEK, DrawerSnap.target(DrawerSnap.HALF, 3f))
        // 到顶/到底再推保持边界
        assertEquals(DrawerSnap.FULL, DrawerSnap.target(DrawerSnap.FULL, -3f))
        assertEquals(DrawerSnap.PEEK, DrawerSnap.target(DrawerSnap.PEEK, 3f))
    }

    @Test
    fun `抽屉settle经spring收敛到三档吸附`() {
        // Robolectric 触摸注入对分层 Surface 手势链不稳定；抽屉吸附以
        // 状态机（dragTo/settle）+ 纯函数断言覆盖，手势接线 M10 真机验证。
        val state = DebugDrawerState()
        val holder = TargetHolder()
        compose.setContent {
            ZqTheme {
                LaunchedEffect(holder.value) {
                    holder.value?.let { state.settle(it) }
                }
            }
        }
        compose.waitForIdle()
        assertEquals(DrawerSnap.PEEK, state.fraction, 0.01f)
        for (target in listOf(DrawerSnap.HALF, DrawerSnap.FULL, DrawerSnap.PEEK)) {
            holder.value = target
            compose.waitForIdle()
            assertEquals(target, state.fraction, 0.05f)
        }
    }

    // ---- 悬浮球边缘吸附 ----

    @Test
    fun `悬浮球吸附纯函数`() {
        assertEquals(0f, BubbleSnap.snapX(10f, 400f, 56f))
        assertEquals(344f, BubbleSnap.snapX(390f, 400f, 56f))
        assertEquals(0f, BubbleSnap.snapX(-50f, 400f, 56f))
        assertEquals(344f, BubbleSnap.snapX(4000f, 400f, 56f))
    }

    @Test
    fun `悬浮球拖到左边缘吸附为零`() {
        val state = FloatingBubbleState()
        compose.setContent {
            ZqTheme {
                Box(Modifier.fillMaxSize()) { FloatingBubble(state) }
            }
        }
        compose.waitForIdle() // 等待初始落位（右下）
        compose.onNodeWithTag("bubble-ball").performTouchInput {
            down(center)
            repeat(12) { moveBy(Offset(-80f, 0f), delayMillis = 10) }
            up()
        }
        compose.waitForIdle()
        assertEquals(0f, state.x, 1f)
    }
}
