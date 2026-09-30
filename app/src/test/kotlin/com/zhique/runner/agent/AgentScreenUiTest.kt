package com.zhique.runner.agent

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.zhique.core.agent.AgentEvent
import com.zhique.core.agent.Compactor
import com.zhique.runner.ui.theme.ZqTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Task 4.4 UI：Agent 会话屏元素与交互（目标/时间线/diff 红绿/预算环/批准卡/摘要卡）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentScreenUiTest {

    @get:Rule
    val compose = createComposeRule()

    private fun setContent(
        state: AgentUiState,
        onGoalChange: (String) -> Unit = {},
        onStart: () -> Unit = {},
        onCompact: () -> Unit = {},
        onApprove: () -> Unit = {},
        onDeny: () -> Unit = {},
    ) {
        compose.setContent {
            ZqTheme {
                AgentContent(
                    state = state,
                    onBack = {},
                    onGoalChange = onGoalChange,
                    onStart = onStart,
                    onStop = {},
                    onResumeFive = {},
                    onCompact = onCompact,
                    onDismissCompression = {},
                    onRollback = {},
                    onApprove = onApprove,
                    onDeny = onDeny,
                )
            }
        }
    }

    @Test
    fun `空态可输入目标并启动`() {
        var started = false
        var compacted = false
        var changed: String? = null
        setContent(
            state = AgentUiState(goal = "修好星空"),
            onGoalChange = { changed = it },
            onStart = { started = true },
            onCompact = { compacted = true },
        )
        // 输入回调携带新目标（状态提交时机由宿主管，UI 只验回调）
        compose.onNodeWithTag("agent-goal-input").performTextInput("!")
        compose.waitForIdle()
        org.junit.Assert.assertEquals("!修好星空", changed)
        compose.onNodeWithTag("budget-ring").assertExists()
        compose.onNodeWithText("文本 · DOM 观察").assertExists()
        compose.onNodeWithTag("agent-start").performClick()
        compose.waitForIdle()
        org.junit.Assert.assertTrue("启动回调未触发", started)
        compose.onNodeWithTag("compact-button").performClick()
        org.junit.Assert.assertTrue("压缩回调未触发", compacted)
    }

    @Test
    fun `时间线与diff红绿行渲染`() {
        val state = AgentUiState(
            projectName = "星空",
            vision = true,
            rounds = listOf(RoundUi(1, "", "看报错")),
            steps = listOf(
                StepUi(
                    "edit_file",
                    true,
                    "",
                    DiffUi(
                        "index.html",
                        listOf(DiffLine('-', "旧"), DiffLine('+', "新行 100px")),
                    ),
                ),
                StepUi("read_console", false, "boom"),
            ),
        )
        setContent(state)
        compose.onRoot().printToLog("AGENTTREE")
        compose.onNodeWithText("vision · 截图自查").assertExists()
        compose.onNodeWithTag("step-edit_file").assertExists()
        compose.onNodeWithText("+ 新行 100px").assertExists()
        compose.onNodeWithText("- 旧").assertExists()
        compose.onNodeWithTag("agent-timeline").performScrollToNode(hasTestTag("step-read_console"))
        compose.onNodeWithText("read_console").assertExists()
        compose.onNodeWithText("boom").assertExists()
        compose.onNodeWithText("看报错").assertExists()
    }

    @Test
    fun `批准卡渲染与批准回调`() {
        var approved = false
        setContent(
            state = AgentUiState(
                projectName = "星空",
                awaitConfirm = AgentEvent.AwaitConfirm("push", """{"remote":"origin"}"""),
            ),
            onApprove = { approved = true },
        )
        compose.onNodeWithTag("confirm-card").assertExists()
        compose.onNodeWithText("批准").performClick()
        org.junit.Assert.assertTrue("批准回调未触发", approved)
    }

    @Test
    fun `压缩摘要卡_kept_dropped_token计数动画终值`() {
        compose.mainClock.autoAdvance = false
        setContent(
            state = AgentUiState(
                compression = Compactor.CompressionReport(
                    kept = listOf("任务目标（原文）", "⭐ 关键结论 ×1", "最近 4 轮原文", "压缩摘要 1 条"),
                    dropped = listOf("assistant: 早期一轮…", "assistant: 早期二轮…"),
                    before = 48000,
                    after = 6100,
                    manual = true,
                ),
            ),
        )
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        compose.onNodeWithTag("compression-card").assertExists()
        compose.onNodeWithText("48000 → 6100 tokens").assertExists()
        compose.onNodeWithText("· ⭐ 关键结论 ×1").assertExists()
        compose.onNodeWithText("· assistant: 早期一轮…").assertExists()
        compose.mainClock.autoAdvance = true
    }
}
