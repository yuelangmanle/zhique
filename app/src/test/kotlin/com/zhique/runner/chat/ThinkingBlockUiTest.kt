package com.zhique.runner.chat

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.zhique.runner.ui.theme.ZqTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 思考流折叠块（X7）：默认折叠摘要行、展开逐字回放、再点收起。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThinkingBlockUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val thinking = "先分析需求，再拆步骤，最后写实现。"

    private fun ComposeTestRule.renderedThinking(): String =
        onAllNodes(hasTestTag("thinking-replay-text")).fetchSemanticsNodes()
            .firstOrNull()
            ?.config
            ?.getOrNull(SemanticsProperties.Text)
            ?.joinToString("") { it.text }
            .orEmpty()

    @Test
    fun `默认折叠_摘要行显示_思考正文不可见`() {
        compose.setContent { ZqTheme { ThinkingBlock(thinking, seconds = 12.4, tokens = 842) } }
        compose.onNodeWithText("已思考 12.4 s · 842 tokens ▸").assertIsDisplayed()
        compose.onNodeWithTag("thinking-replay-text").assertDoesNotExist()
    }

    @Test
    fun `点击展开_逐字回放思考文本_再点收起`() {
        compose.setContent { ZqTheme { ThinkingBlock(thinking, seconds = 3.0, tokens = 21) } }
        compose.onNodeWithTag("thinking-toggle").performClick()
        compose.waitUntil(5_000) { compose.renderedThinking() == thinking }
        compose.onNodeWithTag("thinking-replay-text").assertExists()
        compose.onNodeWithTag("thinking-toggle").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("thinking-replay-text").assertDoesNotExist()
    }

    @Test
    fun `思考中状态显示占位`() {
        compose.setContent {
            ZqTheme { ThinkingBlock("部分思考", seconds = null, tokens = 2, streaming = true) }
        }
        compose.onNodeWithText("思考中…").assertIsDisplayed()
    }
}
