package com.zhique.runner.chat

import com.zhique.core.ai.ChatRequest
import com.zhique.core.ai.StreamEvent
import com.zhique.core.ai.StopReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/** M3 遗留接线 b：UsageMeter.record 挂进 ChatController 调用管线。 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatUsageMeterTest {

    @Test
    fun `每轮完成后记录输出用量`() = runTest {
        var recorded = 0
        val chat: suspend (ChatRequest) -> Flow<StreamEvent> = { _ ->
            flow {
                emit(StreamEvent.ContentDelta("你好呀"))
                emit(StreamEvent.Done(StopReason.STOP))
            }
        }
        val c = ChatController(
            chat = chat,
            newRequest = { history -> ChatRequest("https://example.invalid", "test-" + "k".repeat(8), "m", history, maxTokens = 1024) },
            recordUsage = { recorded += it },
            scope = CoroutineScope(UnconfinedTestDispatcher()),
            io = UnconfinedTestDispatcher(),
        )
        c.send("在吗")
        assertTrue(recorded > 0, "完成一轮后应记录用量")
        assertEquals(estimateTokens("你好呀"), recorded)
    }

    @Test
    fun `续写段也计入用量`() = runTest {
        var recorded = 0
        val scripts = ArrayDeque(
            listOf(
                listOf(StreamEvent.ContentDelta("半截"), StreamEvent.Done(StopReason.LENGTH)),
                listOf(StreamEvent.ContentDelta("接上"), StreamEvent.Done(StopReason.STOP)),
            ),
        )
        val chat: suspend (ChatRequest) -> Flow<StreamEvent> = { _ ->
            flow {
                scripts.removeFirstOrNull()!!.forEach { emit(it) }
            }
        }
        val c = ChatController(
            chat = chat,
            newRequest = { history -> ChatRequest("https://example.invalid", "test-" + "k".repeat(8), "m", history, maxTokens = 1024) },
            maxSegments = 0,
            recordUsage = { recorded += it },
            scope = CoroutineScope(UnconfinedTestDispatcher()),
            io = UnconfinedTestDispatcher(),
        )
        c.send("写")
        c.continueOutput()
        assertEquals(estimateTokens("半截") + estimateTokens("半截接上"), recorded)
    }
}
