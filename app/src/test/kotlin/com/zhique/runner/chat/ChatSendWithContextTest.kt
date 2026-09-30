package com.zhique.runner.chat

import com.zhique.core.ai.ChatRequest
import com.zhique.core.ai.StreamEvent
import com.zhique.core.ai.StopReason
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/** F3「选中即上下文」：编辑器选中范围作为 ChatRequest 附加上下文。 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatSendWithContextTest {

    @Test
    fun `选中代码组装进用户消息`() = runTest {
        val captured = mutableListOf<ChatRequest>()
        val chat: suspend (ChatRequest) -> Flow<StreamEvent> = { req ->
            flow {
                captured += req
                emit(StreamEvent.ContentDelta("好的"))
                emit(StreamEvent.Done(StopReason.STOP))
            }
        }
        val c = ChatController(
            chat = chat,
            newRequest = { history -> ChatRequest("https://example.invalid", "test-" + "k".repeat(8), "m", history, maxTokens = 1024) },
            scope = CoroutineScope(UnconfinedTestDispatcher()),
            io = UnconfinedTestDispatcher(),
        )
        c.sendWithContext("const a = 1;", "javascript", "解释这段代码")
        val userMsg = c.state.value.turns.first { it.role == "user" }.content
        assertTrue(userMsg.startsWith("解释这段代码"), "问题在前：$userMsg")
        assertTrue("```javascript" in userMsg && "const a = 1;" in userMsg, "选中代码入围栏块")
    }

    @Test
    fun `空选中退化为普通发送`() = runTest {
        val c = ChatController(
            chat = { req -> flow { emit(StreamEvent.ContentDelta("好")); emit(StreamEvent.Done(StopReason.STOP)) } },
            newRequest = { history -> ChatRequest("https://example.invalid", "test-" + "k".repeat(8), "m", history, maxTokens = 1024) },
            scope = CoroutineScope(UnconfinedTestDispatcher()),
            io = UnconfinedTestDispatcher(),
        )
        c.sendWithContext("", "javascript", "你好")
        kotlin.test.assertEquals("你好", c.state.value.turns.first { it.role == "user" }.content)
    }
}
