package com.zhique.runner.chat

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.zhique.core.ai.ChatMessage
import com.zhique.core.ai.ChatRequest
import com.zhique.core.ai.StreamEvent
import com.zhique.core.ai.StopReason
import com.zhique.runner.fakeApiKey
import com.zhique.runner.ui.theme.ZqTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 对话面板（Task 3.5）：思考与正文分离、续写徽标、Truncated 警告条与「继续输出」、顶栏双环。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatScreenUiTest {

    @get:Rule
    val compose = createComposeRule()

    private fun newController(
        scripts: ArrayDeque<List<StreamEvent>>,
        maxSegments: Int = 3,
    ): ChatController {
        val chat: suspend (ChatRequest) -> Flow<StreamEvent> = { req ->
            flow {
                val script = scripts.removeFirstOrNull() ?: error("脚本耗尽")
                script.forEach { emit(it) }
            }
        }
        return ChatController(
            chat = chat,
            newRequest = { history ->
                ChatRequest(
                    baseUrl = "https://example.invalid",
                    apiKey = fakeApiKey,
                    model = "test-model",
                    messages = history,
                    maxTokens = 8192,
                )
            },
            maxSegments = maxSegments,
            scope = CoroutineScope(UnconfinedTestDispatcher()),
            io = UnconfinedTestDispatcher(),
        )
    }

    @Test
    fun `思考与正文分离渲染_顶栏双环存在`() {
        val c = newController(
            ArrayDeque(
                listOf(
                    listOf(
                        StreamEvent.ThinkingDelta("盘算如何回答"),
                        StreamEvent.ContentDelta("回答正文"),
                        StreamEvent.Done(StopReason.STOP),
                    ),
                ),
            ),
        )
        compose.setContent { ZqTheme { ChatScreen(controller = c, onBack = {}) } }
        c.send("问题")
        compose.waitForIdle()
        // 双环
        compose.onNodeWithText("输出").assertExists()
        compose.onNodeWithText("上下文").assertExists()
        // 分离：正文节点只含正文；思考块按折叠态隐藏原文
        compose.onNodeWithTag("turn-content-1").assertExists()
        compose.onNodeWithText("回答正文").assertExists()
        compose.onNodeWithText("盘算如何回答").assertDoesNotExist()
        // 摘要行（折叠思考块）存在
        compose.onNodeWithTag("thinking-toggle").assertExists()
    }

    @Test
    fun `续写徽标与触顶警告条_继续输出再续一段`() {
        val c = newController(
            ArrayDeque(
                listOf(
                    listOf(StreamEvent.ContentDelta("前半"), StreamEvent.Done(StopReason.LENGTH)),
                    listOf(StreamEvent.ContentDelta("后半"), StreamEvent.Done(StopReason.STOP)),
                ),
            ),
            maxSegments = 0,
        )
        compose.setContent { ZqTheme { ChatScreen(controller = c, onBack = {}) } }
        c.send("写长文")
        compose.waitForIdle()
        compose.onNodeWithTag("truncated-bar-1").assertExists()
        compose.onNodeWithText("已达输出上限").assertExists()
        compose.onNodeWithText("前半").assertExists()
        compose.onNodeWithTag("continue-btn-1").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("前半后半").assertExists()
        compose.onNodeWithText("已续写 1 段").assertExists()
        compose.onNodeWithText("已达输出上限").assertDoesNotExist()
    }

    @Test
    fun `输入与发送走controller`() {
        val c = newController(
            ArrayDeque(
                listOf(listOf(StreamEvent.ContentDelta("好"), StreamEvent.Done(StopReason.STOP))),
            ),
        )
        compose.setContent { ZqTheme { ChatScreen(controller = c, onBack = {}) } }
        compose.onNodeWithTag("chat-input").performTextInput("你好")
        compose.onNodeWithTag("chat-send").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("你好").assertExists()
        compose.onNodeWithText("好").assertExists()
    }
}
