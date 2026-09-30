package com.zhique.core.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 防截断自动续写状态机：计划 Task 3.4 的 8 个场景全覆盖 + stitch 行级双指针单测。
 */
class TruncationContinuerTest {

    /** 脚本化假 chat：按调用次序吐预置事件流，并记录每次收到的请求。 */
    private class FakeChat {
        val requests = mutableListOf<ChatRequest>()
        val scripts = ArrayDeque<List<StreamEvent>>()

        fun enqueue(vararg events: StreamEvent) {
            scripts.addLast(events.toList())
        }

        val chat: suspend (ChatRequest) -> Flow<StreamEvent> = { req ->
            flow {
                requests += req
                val script = scripts.removeFirstOrNull()
                    ?: throw IllegalStateException("脚本耗尽：第 ${requests.size} 次调用")
                script.forEach { emit(it) }
            }
        }
    }

    private fun baseReq() = ChatRequest(
        baseUrl = "https://example.invalid",
        apiKey = fakeApiKey,
        model = "test-model",
        messages = listOf(ChatMessage("user", "写一个网页")),
        maxTokens = ModelCatalog.CONTINUE_SEGMENT_MAX_OUTPUT,
    )

    // ---- 8 个计划场景 ----

    @Test
    fun `场景1_DONE_STOP不续写`() = runTest {
        val fake = FakeChat()
        fake.enqueue(StreamEvent.ContentDelta("完"), StreamEvent.Done(StopReason.STOP))
        val out = TruncationContinuer(fake.chat).generate(baseReq())
        assertEquals("完", out.content)
        assertEquals(0, out.segments)
        assertFalse(out.limitHit)
        assertEquals(1, fake.requests.size, "STOP 不得发续写请求")
    }

    @Test
    fun `场景2_LENGTH自动续一段后STOP_徽标1`() = runTest {
        val fake = FakeChat()
        fake.enqueue(StreamEvent.ContentDelta("A"), StreamEvent.Done(StopReason.LENGTH))
        fake.enqueue(StreamEvent.ContentDelta("B"), StreamEvent.Done(StopReason.STOP))
        val out = TruncationContinuer(fake.chat).generate(baseReq())
        assertEquals("AB", out.content)
        assertEquals(1, out.segments)
        assertFalse(out.limitHit)
        assertEquals(2, fake.requests.size)
        // 续写请求 = 原消息 + assistant(前段) + user(续写指令)
        val second = fake.requests[1]
        assertEquals(3, second.messages.size)
        assertEquals(ChatMessage("assistant", "A"), second.messages[1])
        assertEquals("user", second.messages[2].role)
        assertTrue(second.messages[2].content.contains("原样继续"), "续写指令语义")
    }

    @Test
    fun `场景3_连续LENGTH两段后STOP_徽标2`() = runTest {
        val fake = FakeChat()
        fake.enqueue(StreamEvent.ContentDelta("一"), StreamEvent.Done(StopReason.LENGTH))
        fake.enqueue(StreamEvent.ContentDelta("二"), StreamEvent.Done(StopReason.LENGTH))
        fake.enqueue(StreamEvent.ContentDelta("三"), StreamEvent.Done(StopReason.STOP))
        val out = TruncationContinuer(fake.chat).generate(baseReq())
        assertEquals("一二三", out.content)
        assertEquals(2, out.segments)
        assertEquals(3, fake.requests.size)
        // 第三段请求携带的 assistant 前缀 = 第一+二段拼接（前缀窗口取尾部）
        val third = fake.requests[2]
        val assistantPrefix = third.messages.first { it.role == "assistant" }.content
        assertTrue("一二" in assistantPrefix, "前缀应含已输出内容：$assistantPrefix")
    }

    @Test
    fun `场景4_超maxSegments仍LENGTH_抛Truncated`() = runTest {
        val fake = FakeChat()
        repeat(4) { i ->
            fake.enqueue(StreamEvent.ContentDelta("s$i\n"), StreamEvent.Done(StopReason.LENGTH))
        }
        val e = assertFailsWith<TruncationContinuer.Truncated> {
            TruncationContinuer(fake.chat, maxSegments = 3).generate(baseReq())
        }
        assertEquals(3, e.segments)
        assertTrue(e.partial.startsWith("s0"), "partial 不得丢内容")
        assertTrue("s3" in e.partial, "触顶那一段内容仍在 partial 中")
        assertEquals(4, fake.requests.size, "第 4 次 LENGTH 到达后不再续写")
    }

    @Test
    fun `场景5_拼接重叠去重_前段尾与续段头重叠去一`() = runTest {
        val fake = FakeChat()
        fake.enqueue(StreamEvent.ContentDelta("cfg\n}\n"), StreamEvent.Done(StopReason.LENGTH))
        fake.enqueue(StreamEvent.ContentDelta("\n}\nrest"), StreamEvent.Done(StopReason.STOP))
        val out = TruncationContinuer(fake.chat).generate(baseReq())
        assertEquals("cfg\n}\nrest", out.content, "重叠的 } 行只保留一份")
    }

    @Test
    fun `场景6_代码边界_前段止于行中续段首空行去空行衔接`() = runTest {
        val fake = FakeChat()
        fake.enqueue(StreamEvent.ContentDelta("const total = su"), StreamEvent.Done(StopReason.LENGTH))
        fake.enqueue(StreamEvent.ContentDelta("\nm(1, 2);"), StreamEvent.Done(StopReason.STOP))
        val out = TruncationContinuer(fake.chat).generate(baseReq())
        assertEquals("const total = sum(1, 2);", out.content)
    }

    @Test
    fun `场景7_maxSegments为0_立即Truncated`() = runTest {
        val fake = FakeChat()
        fake.enqueue(StreamEvent.ContentDelta("只此一段"), StreamEvent.Done(StopReason.LENGTH))
        val e = assertFailsWith<TruncationContinuer.Truncated> {
            TruncationContinuer(fake.chat, maxSegments = 0).generate(baseReq())
        }
        assertEquals(0, e.segments)
        assertEquals("只此一段", e.partial)
        assertEquals(1, fake.requests.size, "关闭续写时不得发第二段请求")
    }

    @Test
    fun `场景8_手动continueOnce再续一段`() = runTest {
        val fake = FakeChat()
        fake.enqueue(StreamEvent.ContentDelta("续段"), StreamEvent.Done(StopReason.STOP))
        val out = TruncationContinuer(fake.chat).continueOnce(partial = "残", req = baseReq())
        assertEquals("残续段", out.content)
        assertEquals(1, out.segments)
        assertEquals(1, fake.requests.size)
        val req = fake.requests[0]
        assertEquals("残", req.messages.first { it.role == "assistant" }.content)
        // 仍触顶时 limitHit=true（供 UI 再挂警告）
        val fake2 = FakeChat()
        fake2.enqueue(StreamEvent.ContentDelta("x"), StreamEvent.Done(StopReason.LENGTH))
        val out2 = TruncationContinuer(fake2.chat).continueOnce(partial = "P", req = baseReq())
        assertTrue(out2.limitHit)
    }

    @Test
    fun `流内ERROR转AiErrorException_取消转CancellationException`() = runTest {
        val fake = FakeChat()
        fake.enqueue(StreamEvent.Done(StopReason.ERROR("boom")))
        assertFailsWith<AiErrorException> { TruncationContinuer(fake.chat).generate(baseReq()) }

        val fake2 = FakeChat()
        fake2.enqueue(StreamEvent.Done(StopReason.CANCELLED))
        assertFailsWith<CancellationException> { TruncationContinuer(fake2.chat).generate(baseReq()) }
    }

    // ---- stitch 行级双指针算法单测 ----

    @Test
    fun `stitch_无重叠直接衔接`() {
        assertEquals("abcdef", stitch("abc", "def"))
        assertEquals("x", stitch("", "x"))
        assertEquals("x", stitch("x", ""))
    }

    @Test
    fun `stitch_尾行重叠去一`() {
        assertEquals("a\n}\nb", stitch("a\n}", "}\nb"))
        assertEquals("f(1);\nf(2);\nf(3);", stitch("f(1);\nf(2);", "f(2);\nf(3);"))
    }

    @Test
    fun `stitch_空行边界归一后仍可去重`() {
        // 前段尾 "\n}\n" 与续段头 "\n}\n"：换行边界归一后按行匹配去一
        assertEquals("x\n}\ny", stitch("x\n}\n", "\n}\ny"))
    }

    @Test
    fun `stitch_重叠上限4行`() {
        val prev = (1..6).joinToString("\n") { "l$it" }
        val next = (3..8).joinToString("\n") { "l$it" }
        assertEquals((1..8).joinToString("\n") { "l$it" }, stitch(prev, next))
    }
}
