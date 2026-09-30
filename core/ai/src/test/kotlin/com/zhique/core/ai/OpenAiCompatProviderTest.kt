package com.zhique.core.ai

import java.util.UUID
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * OpenAI 兼容适配器（mockwebserver）：SSE 流映射、finish_reason、tool_calls 增量、
 * 错误码分类与请求形态断言（Task 3.1）。
 */
class OpenAiCompatProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var provider: OpenAiCompatProvider

    /** 测试假 Key：随机串，非真实厂商格式（凭据零字面量纪律）。 */
    private val testKey = "test-key-" + UUID.randomUUID().toString().replace("-", "").take(16)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val client = OkHttpClient.Builder().readTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build()
        provider = OpenAiCompatProvider(client)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun request(maxTokens: Int = 256) = ChatRequest(
        baseUrl = server.url("/").toString().trimEnd('/'),
        apiKey = testKey,
        model = "test-model",
        messages = listOf(ChatMessage("user", "hi")),
        maxTokens = maxTokens,
    )

    private fun sseBody(vararg chunks: String, withDone: Boolean = true): String =
        chunks.joinToString("") { "data: $it\n\n" } + if (withDone) "data: [DONE]\n\n" else ""

    private fun ok(body: String): MockResponse =
        MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody(body)

    @Test
    fun `reasoning_content映射思考_content映射正文_STOP终止`() = runTest {
        server.enqueue(
            ok(
                sseBody(
                    """{"choices":[{"delta":{"reasoning_content":"想"}}]}""",
                    """{"choices":[{"delta":{"content":"答"}}]}""",
                    """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
                ),
            ),
        )
        val events = provider.chatStream(request()).toList()
        assertEquals(
            listOf(
                StreamEvent.ThinkingDelta("想"),
                StreamEvent.ContentDelta("答"),
                StreamEvent.Done(StopReason.STOP),
            ),
            events,
        )
    }

    @Test
    fun `oreasoning字段同样映射思考`() = runTest {
        server.enqueue(ok(sseBody("""{"choices":[{"delta":{"reasoning":"推理"}}]}""")))
        val events = provider.chatStream(request()).toList()
        assertEquals(StreamEvent.ThinkingDelta("推理"), events[0])
    }

    @Test
    fun `finish_reason为length映射LENGTH`() = runTest {
        server.enqueue(ok(sseBody("""{"choices":[{"delta":{"content":"半"}}]}""", """{"choices":[{"delta":{},"finish_reason":"length"}]}""")))
        val events = provider.chatStream(request()).toList()
        assertEquals(StreamEvent.Done(StopReason.LENGTH), events.last())
    }

    @Test
    fun `tool_calls增量分片与聚合`() = runTest {
        server.enqueue(
            ok(
                sseBody(
                    """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"run","arguments":""}}]}}]}""",
                    """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"ur"}}]}}]}""",
                    """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"l\":\"a\"}"}}]}}]}""",
                    """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
                ),
            ),
        )
        val events = provider.chatStream(request()).toList()
        assertEquals(
            listOf(
                StreamEvent.ToolCallDelta(0, "call_1", "run", ""),
                StreamEvent.ToolCallDelta(0, null, null, """{"ur"""),
                StreamEvent.ToolCallDelta(0, null, null, """l":"a"}"""),
            ),
            events.dropLast(1),
        )
        val agg = ToolCallAggregator()
        events.forEach { if (it is StreamEvent.ToolCallDelta) agg.accept(it) }
        assertEquals(listOf(ToolCall("call_1", "run", """{"url":"a"}""")), agg.build())
    }

    @Test
    fun `请求路径_鉴权头_流式标记`() = runTest {
        server.enqueue(ok(sseBody("""{"choices":[{"delta":{},"finish_reason":"stop"}]}""")))
        provider.chatStream(request(maxTokens = 777)).toList()
        val recorded = server.takeRequest()
        assertEquals("/v1/chat/completions", recorded.path)
        assertEquals("Bearer $testKey", recorded.getHeader("Authorization"))
        val sent = recorded.body.readUtf8()
        assertTrue(""""stream":true""" in sent, "必须请求流式：$sent")
        assertEquals("test-model", sent.let { kotlinx.serialization.json.Json.parseToJsonElement(it).jsonObject["model"]!!.jsonPrimitive.content })
        assertEquals(777, kotlinx.serialization.json.Json.parseToJsonElement(sent).jsonObject["max_tokens"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `HTTP401映射Auth错误`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad key"}}"""))
        val e = assertFailsWith<AiError.Auth> { provider.chatStream(request()).toList() }
        assertTrue("bad key" in e.message!!)
    }

    @Test
    fun `HTTP429映射RateLimit`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("rate limited"))
        assertFailsWith<AiError.RateLimit> { provider.chatStream(request()).toList() }
    }

    @Test
    fun `HTTP5xx映射Server错误`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setBody("down"))
        assertFailsWith<AiError.Server> { provider.chatStream(request()).toList() }
    }

    @Test
    fun `流内错误对象转Done_ERROR`() = runTest {
        server.enqueue(ok(sseBody("""{"error":{"message":"boom"}}""")))
        val events = provider.chatStream(request()).toList()
        assertEquals(StreamEvent.Done(StopReason.ERROR("boom")), events.single())
    }

    @Test
    fun `无DONE哨兵以EOF终止并补Done`() = runTest {
        server.enqueue(
            ok(sseBody("""{"choices":[{"delta":{"content":"尾"}}]}""", withDone = false)),
        )
        val events = provider.chatStream(request()).toList()
        assertEquals(
            listOf(StreamEvent.ContentDelta("尾"), StreamEvent.Done(StopReason.STOP)),
            events,
        )
    }

    @Test
    fun `连接失败映射Network错误`() = runTest {
        val url = server.url("/").toString()
        server.shutdown()
        val dead = OpenAiCompatProvider(OkHttpClient())
        val req = request().copy(baseUrl = url.trimEnd('/'))
        assertFailsWith<AiError.Network> { dead.chatStream(req).toList() }
    }
}
