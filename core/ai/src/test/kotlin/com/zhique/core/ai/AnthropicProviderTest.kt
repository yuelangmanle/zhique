package com.zhique.core.ai

import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Anthropic Messages 适配器（mockwebserver，事件名对齐官方流）：
 * thinking_delta/text_delta/input_json_delta、stop_reason=="max_tokens"→LENGTH、
 * x-api-key + anthropic-version、错误码分类（Task 3.2）。
 */
class AnthropicProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var provider: AnthropicProvider
    private val testKey = "test-key-" + UUID.randomUUID().toString().replace("-", "").take(16)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        provider = AnthropicProvider(
            OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun request(maxTokens: Int = 1024, thinking: Boolean = false) = ChatRequest(
        baseUrl = server.url("/").toString().trimEnd('/'),
        apiKey = testKey,
        model = "test-model",
        messages = listOf(ChatMessage("system", "你是助手"), ChatMessage("user", "hi")),
        maxTokens = maxTokens,
        thinkingEnabled = thinking,
    )

    private fun sse(vararg frames: String): String =
        frames.joinToString("") { "data: $it\n\n" }

    private fun ok(body: String): MockResponse =
        MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody(body)

    @Test
    fun `thinking_delta映射思考_text_delta映射正文`() = runTest {
        server.enqueue(
            ok(
                sse(
                    """{"type":"content_block_start","index":0,"content_block":{"type":"thinking"}}""",
                    """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"推演"}}""",
                    """{"type":"content_block_stop","index":0}""",
                    """{"type":"content_block_start","index":1,"content_block":{"type":"text"}}""",
                    """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"回答"}}""",
                    """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":9}}""",
                ),
            ),
        )
        val events = provider.chatStream(request()).toList()
        assertEquals(
            listOf(
                StreamEvent.ThinkingDelta("推演"),
                StreamEvent.ContentDelta("回答"),
                StreamEvent.Done(StopReason.STOP),
            ),
            events,
        )
    }

    @Test
    fun `stop_reason为max_tokens映射LENGTH`() = runTest {
        server.enqueue(
            ok(
                sse(
                    """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"半"}}""",
                    """{"type":"message_delta","delta":{"stop_reason":"max_tokens"}}""",
                ),
            ),
        )
        val events = provider.chatStream(request()).toList()
        assertEquals(StreamEvent.Done(StopReason.LENGTH), events.last())
    }

    @Test
    fun `input_json_delta工具增量含content_block_start元数据`() = runTest {
        server.enqueue(
            ok(
                sse(
                    """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_1","name":"run"}}""",
                    """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"u"}}""",
                    """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"rl\":1}"}}""",
                    """{"type":"message_delta","delta":{"stop_reason":"tool_use"}}""",
                ),
            ),
        )
        val events = provider.chatStream(request()).toList()
        assertEquals(
            listOf(
                StreamEvent.ToolCallDelta(0, "toolu_1", "run", ""),
                StreamEvent.ToolCallDelta(0, null, null, "{\"u"),
                StreamEvent.ToolCallDelta(0, null, null, "rl\":1}"),
                StreamEvent.Done(StopReason.STOP),
            ),
            events,
        )
        val agg = ToolCallAggregator()
        events.forEach { if (it is StreamEvent.ToolCallDelta) agg.accept(it) }
        assertEquals(listOf(ToolCall("toolu_1", "run", """{"url":1}""")), agg.build())
    }

    @Test
    fun `ping事件忽略`() = runTest {
        server.enqueue(
            ok(
                sse(
                    """{"type":"ping"}""",
                    """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"好"}}""",
                    """{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""",
                ),
            ),
        )
        val events = provider.chatStream(request()).toList()
        assertEquals(listOf(StreamEvent.ContentDelta("好"), StreamEvent.Done(StopReason.STOP)), events)
    }

    @Test
    fun `请求路径与鉴权头与系统消息置顶`() = runTest {
        server.enqueue(
            ok(sse("""{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""")),
        )
        provider.chatStream(request(maxTokens = 555)).toList()
        val recorded = server.takeRequest()
        assertEquals("/v1/messages", recorded.path)
        assertEquals(testKey, recorded.getHeader("x-api-key"))
        assertTrue(!recorded.getHeader("anthropic-version").isNullOrBlank())
        val sent = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals("test-model", sent["model"]!!.jsonPrimitive.content)
        assertEquals(555, sent["max_tokens"]!!.jsonPrimitive.content.toInt())
        assertEquals("你是助手", sent["system"]!!.jsonPrimitive.content)
        assertEquals(true, sent["stream"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `thinking开启注入thinking块并省略temperature`() = runTest {
        server.enqueue(
            ok(sse("""{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""")),
        )
        provider.chatStream(request(maxTokens = 2048, thinking = true)).toList()
        val sent = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("enabled", sent["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue(sent["temperature"] == null, "思考模式下不得携带 temperature（官方约束）")
    }

    @Test
    fun `流内error事件转Done_ERROR`() = runTest {
        server.enqueue(ok(sse("""{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""")))
        val events = provider.chatStream(request()).toList()
        assertEquals(StreamEvent.Done(StopReason.ERROR("Overloaded")), events.single())
    }

    @Test
    fun `HTTP错误码分类`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad key"}}"""))
        assertFailsWith<AiError.Auth> { provider.chatStream(request()).toList() }
        server.enqueue(MockResponse().setResponseCode(429).setBody("limited"))
        assertFailsWith<AiError.RateLimit> { provider.chatStream(request()).toList() }
        server.enqueue(MockResponse().setResponseCode(529).setBody("overloaded"))
        assertFailsWith<AiError.Server> { provider.chatStream(request()).toList() }
    }
}
