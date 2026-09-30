package com.zhique.runner.chat

import com.zhique.core.ai.ChatRequest
import com.zhique.core.ai.StreamEvent
import com.zhique.core.ai.StopReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/** 质量审查修复回归（app 侧）：取消不吞、liveThinking 尾部缓冲与复用。 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewFixAppTest {

    private fun newController(
        chat: suspend (ChatRequest) -> Flow<StreamEvent>,
        maxSegments: Int = 0,
    ): ChatController = ChatController(
        chat = chat,
        newRequest = { history ->
            ChatRequest(
                baseUrl = "https://example.invalid",
                apiKey = "test-key-" + "r".repeat(8),
                model = "test-model",
                messages = history,
                maxTokens = 8192,
            )
        },
        maxSegments = maxSegments,
        scope = CoroutineScope(UnconfinedTestDispatcher()),
        io = UnconfinedTestDispatcher(),
    )

    @Test
    fun `fix5_继续输出被取消不吞CancellationException`() = runTest {
        var calls = 0
        val scripts = ArrayDeque(
            listOf(listOf(StreamEvent.ContentDelta("半截"), StreamEvent.Done(StopReason.LENGTH))),
        )
        val c = newController({ _ ->
            calls++
            if (calls == 1) {
                flow { scripts.removeFirst().forEach { emit(it) } }
            } else {
                throw kotlin.coroutines.cancellation.CancellationException("user cancel")
            }
        })
        c.send("写")
        assertTrue(c.state.value.truncated)
        c.continueOutput()
        assertEquals("已取消", c.state.value.error, "取消要落错误态而不是被吞成普通异常")
        assertFalse(c.state.value.busy)
    }

    @Test
    fun `fix10_liveThinking缓冲上限与尾部保留`() = runTest {
        val scripts = ArrayDeque(
            listOf(
                listOf(
                    StreamEvent.ThinkingDelta("a".repeat(ChatController.LIVE_BUFFER_CAP + 10_000)),
                    StreamEvent.ContentDelta("答"),
                    StreamEvent.Done(StopReason.STOP),
                ),
            ),
        )
        val chat: suspend (ChatRequest) -> Flow<StreamEvent> = { _ ->
            flow { scripts.removeFirst().forEach { emit(it) } }
        }
        val c = newController(chat)
        c.send("写")
        val turn = c.state.value.turns.last { it.role == "assistant" }
        assertTrue(turn.thinking.length <= ChatController.LIVE_BUFFER_CAP, "缓冲超限须裁剪")
        assertTrue(turn.thinking.endsWith("a"), "仅保留尾部")
    }

    @Test
    fun `fix10_TailBuffer裁剪尾部与复用`() {
        val buf = TailBuffer(cap = 8)
        buf.append("12345")
        buf.append("67890") // 10 > 8 → 保留尾部 8
        assertEquals("34567890", buf.value())
        buf.reset()
        buf.append("x")
        assertEquals("x", buf.value(), "reset 后可复用")
        assertEquals(1, buf.length())
    }
}
