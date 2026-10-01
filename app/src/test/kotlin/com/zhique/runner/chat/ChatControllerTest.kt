package com.zhique.runner.chat

import com.zhique.core.ai.ChatMessage
import com.zhique.core.ai.ChatRequest
import com.zhique.core.ai.StreamEvent
import com.zhique.core.ai.StopReason
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * 对话控制器：流式态聚合、思考不回传历史、Truncated 警告与手动续写（Task 3.5）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatControllerTest {

    private val testKey = "test-key-" + "u".repeat(8)

    private fun newController(
        scripts: ArrayDeque<List<StreamEvent>> = ArrayDeque(),
        capturedRequests: MutableList<ChatRequest> = mutableListOf(),
        maxSegments: Int = 3,
    ): ChatController {
        val chat: suspend (ChatRequest) -> kotlinx.coroutines.flow.Flow<StreamEvent> = { req ->
            flow {
                capturedRequests += req
                val script = scripts.removeFirstOrNull() ?: error("脚本耗尽")
                script.forEach { emit(it) }
            }
        }
        return ChatController(
            chat = chat,
            newRequest = { history ->
                ChatRequest(
                    baseUrl = "https://example.invalid",
                    apiKey = testKey,
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
    fun `发送后流式聚合与完成落轮`() = runTest {
        val scripts = ArrayDeque(
            listOf(
                listOf(
                    StreamEvent.ThinkingDelta("推理"),
                    StreamEvent.ContentDelta("答案"),
                    StreamEvent.Done(StopReason.STOP),
                ),
            ),
        )
        val c = newController(scripts)
        c.send("你好")
        val s = c.state.value
        assertFalse(s.busy)
        assertEquals(2, s.turns.size)
        assertEquals("user", s.turns[0].role)
        assertEquals("答案", s.turns[1].content)
        assertEquals("推理", s.turns[1].thinking)
        assertTrue(s.turns[1].thinkingTokens > 0)
        assertNull(s.error)
    }

    @Test
    fun `发送历史只含正文_思考永不回传`() = runTest {
        val captured = mutableListOf<ChatRequest>()
        val scripts = ArrayDeque(
            listOf(
                listOf(
                    StreamEvent.ThinkingDelta("秘密推理"),
                    StreamEvent.ContentDelta("回复"),
                    StreamEvent.Done(StopReason.STOP),
                ),
                listOf(StreamEvent.ContentDelta("再来"), StreamEvent.Done(StopReason.STOP)),
            ),
        )
        val c = newController(scripts, captured)
        c.send("第一问")
        c.send("第二问")
        val second = captured[1]
        assertEquals(listOf("user", "assistant", "user"), second.messages.map { it.role })
        assertEquals("回复", second.messages[1].content)
        assertTrue("秘密推理" !in second.messages.map { it.content }.joinToString(), "思考不得回传")
    }

    @Test
    fun `触顶出警告条_继续输出再续一段`() = runTest {
        val scripts = ArrayDeque(
            listOf(
                listOf(StreamEvent.ContentDelta("半截"), StreamEvent.Done(StopReason.LENGTH)),
                listOf(StreamEvent.ContentDelta("接上"), StreamEvent.Done(StopReason.STOP)),
            ),
        )
        val c = newController(scripts, maxSegments = 0)
        c.send("写")
        val s = c.state.value
        assertTrue(s.truncated, "超 maxSegments=0 → 警告")
        assertEquals("半截", s.turns.last().content)
        c.continueOutput()
        val after = c.state.value
        assertFalse(after.truncated)
        assertEquals("半截接上", after.turns.last().content)
        assertEquals(1, after.turns.last().segments)
    }

    @Test
    fun `续写重置思考缓冲_新旧思考不混显`() = runTest {
        // M3 债务收敛：continueOutput 不 reset liveThinkingBuf 时，续写思考会叠进上一段
        val scripts = ArrayDeque(
            listOf(
                listOf(
                    StreamEvent.ThinkingDelta("旧思考"),
                    StreamEvent.ContentDelta("半截"),
                    StreamEvent.Done(StopReason.LENGTH),
                ),
                listOf(
                    StreamEvent.ThinkingDelta("新思考"),
                    StreamEvent.ContentDelta("接上"),
                    StreamEvent.Done(StopReason.STOP),
                ),
            ),
        )
        val c = newController(scripts, maxSegments = 0)
        c.send("写")
        assertTrue(c.state.value.truncated)
        c.continueOutput()
        val turn = c.state.value.turns.last()
        assertEquals("新思考", turn.thinking, "续写后的思考只含本段，不混上一段")
        assertEquals("半截接上", turn.content)
    }

    @Test
    fun `取消与错误落错误态`() = runTest {
        val c1 = newController(ArrayDeque(listOf(listOf(StreamEvent.Done(StopReason.ERROR("boom"))))))
        c1.send("q")
        assertEquals("boom", c1.state.value.error)

        val c2 = newController(ArrayDeque(listOf(listOf(StreamEvent.Done(StopReason.CANCELLED)))))
        runCatching { c2.send("q") } // CANCELLED 重新抛 CancellationException
        assertEquals("已取消", c2.state.value.error)
    }

    @Test
    fun `输出环用量与上下文占比估算`() {
        val state = ChatUiState(
            turns = listOf(
                ChatTurn("user", "问"),
                ChatTurn("assistant", "答".repeat(100)),
            ),
        )
        assertEquals(50, state.outputTokens)
        val frac = state.contextFraction(1000)
        assertTrue(frac > 0f && frac <= 1.2f)
    }
}
