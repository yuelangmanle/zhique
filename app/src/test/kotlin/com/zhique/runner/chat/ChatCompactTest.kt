package com.zhique.runner.chat

import com.zhique.core.ai.ChatRequest
import com.zhique.core.ai.StreamEvent
import com.zhique.core.ai.StopReason
import com.zhique.core.agent.ContextBudget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/** 规格审查缺口 1：Chat 压缩按钮数据流 + 顶栏环 ÷ 工作预算（75%）真值。 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatCompactTest {

    private val testKey = "test-" + "c".repeat(12)


    private fun newController(
        scripts: ArrayDeque<List<StreamEvent>>,
        contextWindow: Int = 100_000,
        onFastRequest: (ChatRequest) -> Unit = {},
    ): ChatController {
        val chat: suspend (ChatRequest) -> Flow<StreamEvent> = { req ->
            flow {
                val script = scripts.removeFirstOrNull() ?: error("脚本耗尽")
                script.forEach { emit(it) }
            }
        }
        val fast: suspend (ChatRequest) -> Flow<StreamEvent> = { req ->
            flow {
                onFastRequest(req)
                emit(StreamEvent.ContentDelta("被压缩轮次的要点摘要"))
                emit(StreamEvent.Done(StopReason.STOP))
            }
        }
        return ChatController(
            chat = chat,
            newRequest = { history ->
                ChatRequest("https://example.invalid", testKey, "m", history, maxTokens = 1024)
            },
            contextWindow = contextWindow,
            contextBudget = ContextBudget(contextLimit = contextWindow),
            fastChat = fast,
            fastTemplate = ChatRequest(baseUrl = "https://example.invalid", apiKey = testKey, model = "fast-m", messages = emptyList(), maxTokens = 4096),
            scope = CoroutineScope(UnconfinedTestDispatcher()),
            io = UnconfinedTestDispatcher(),
        )
    }

    private fun round(text: String) = listOf(
        StreamEvent.ContentDelta(text),
        StreamEvent.Done(StopReason.STOP),
    )

    @Test
    fun `工作预算真值_上下文环按75百分之计算`() {
        val c = newController(ArrayDeque(), contextWindow = 1000)
        assertEquals(750, c.contextBudget.workLimit, "工作预算 = 窗口 75%")
        assertEquals(ContextBudget.DEFAULT_WORK_RATIO, c.contextBudget.workRatio)
    }

    @Test
    fun `压缩后轮次替换为摘要_目标与最近4轮保留`() = runTest {
        val scripts = ArrayDeque(
            listOf(
                round("第一个目标说明：修好星空"),
                round("早期回答" + "a".repeat(300)),
                round("早期回答二" + "b".repeat(300)),
                round("早期回答三" + "c".repeat(300)),
                round("近期一"),
                round("近期二"),
                round("近期三"),
                round("近期四（最后）"),
            ),
        )
        var fastSeen: ChatRequest? = null
        val c = newController(scripts, onFastRequest = { fastSeen = it })
        repeat(8) { c.send("t$it") }

        c.compactNow()
        val report = c.compression.value
        assertNotNull(report, "压缩产物出摘要卡")
        assertTrue(report.before > report.after, "${report.before} → ${report.after}")
        assertTrue(report.manual)
        assertEquals(4096, fastSeen?.maxTokens, "压缩走快循环 4096 上限")

        val turns = c.state.value.turns
        assertEquals(5, turns.size, "摘要 1 条 + 最近 4 轮")
        assertTrue(turns.first().content.contains("已压缩上下文"))
        assertTrue(turns.last().content.contains("近期四（最后）"), "最近一轮原文保留")
        assertTrue(report.kept.any { it.contains("任务目标") }, "目标原文为压缩不变量")
    }

    @Test
    fun `轮次不足时不压缩且不出摘要卡`() = runTest {
        val c = newController(ArrayDeque(listOf(round("一"))))
        c.send("只有一句")
        c.compactNow()
        assertEquals(null, c.compression.value)
    }
}
