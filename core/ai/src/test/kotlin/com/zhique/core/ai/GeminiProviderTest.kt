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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Gemini 原生适配器（mockwebserver）：`:streamGenerateContent?alt=sse`、
 * parts[].thought==true → 思考、finishReason=="MAX_TOKENS" → LENGTH、
 * x-goog-api-key 头（Key 不进 URL，规格 §6 隐私纪律）（Task 3.2）。
 */
class GeminiProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var provider: GeminiProvider
    private val testKey = "test-key-" + UUID.randomUUID().toString().replace("-", "").take(16)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        provider = GeminiProvider(
            OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun request() = ChatRequest(
        baseUrl = server.url("/").toString().trimEnd('/'),
        apiKey = testKey,
        model = "test-model",
        messages = listOf(ChatMessage("system", "系统规范"), ChatMessage("user", "hi")),
        maxTokens = 512,
    )

    private fun sse(vararg frames: String): String = frames.joinToString("") { "data: $it\n\n" }

    private fun ok(body: String): MockResponse =
        MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody(body)

    @Test
    fun `text部分映射正文`() = runTest {
        server.enqueue(
            ok(
                sse(
                    """{"candidates":[{"content":{"parts":[{"text":"你"}]}}]}""",
                    """{"candidates":[{"content":{"parts":[{"text":"好"}]},"finishReason":"STOP"}]}""",
                ),
            ),
        )
        val events = provider.chatStream(request()).toList()
        assertEquals(
            listOf(StreamEvent.ContentDelta("你"), StreamEvent.ContentDelta("好"), StreamEvent.Done(StopReason.STOP)),
            events,
        )
    }

    @Test
    fun `thought为true的部分映射思考`() = runTest {
        server.enqueue(
            ok(
                sse(
                    """{"candidates":[{"content":{"parts":[{"thought":true,"text":"盘算"}]}}]}""",
                    """{"candidates":[{"content":{"parts":[{"text":"答"}]},"finishReason":"STOP"}]}""",
                ),
            ),
        )
        val events = provider.chatStream(request()).toList()
        assertEquals(
            listOf(
                StreamEvent.ThinkingDelta("盘算"),
                StreamEvent.ContentDelta("答"),
                StreamEvent.Done(StopReason.STOP),
            ),
            events,
        )
    }

    @Test
    fun `finishReason为MAX_TOKENS映射LENGTH`() = runTest {
        server.enqueue(
            ok(sse("""{"candidates":[{"content":{"parts":[{"text":"半"}]},"finishReason":"MAX_TOKENS"}]}""")),
        )
        assertEquals(StreamEvent.Done(StopReason.LENGTH), provider.chatStream(request()).toList().last())
    }

    @Test
    fun `functionCall部分映射工具调用`() = runTest {
        server.enqueue(
            ok(
                sse(
                    """{"candidates":[{"content":{"parts":[{"functionCall":{"name":"run","args":{"url":"a"}}}]},"finishReason":"STOP"}]}""",
                ),
            ),
        )
        val events = provider.chatStream(request()).toList()
        assertEquals(
            listOf(
                StreamEvent.ToolCallDelta(0, null, "run", """{"url":"a"}"""),
                StreamEvent.Done(StopReason.STOP),
            ),
            events,
        )
    }

    @Test
    fun `请求路径_alt_sse与Key头不进URL`() = runTest {
        server.enqueue(ok(sse("""{"candidates":[{"content":{"parts":[{"text":"好"}]},"finishReason":"STOP"}]}""")))
        provider.chatStream(request()).toList()
        val recorded = server.takeRequest()
        assertEquals("/v1beta/models/test-model:streamGenerateContent", recorded.requestUrl!!.encodedPath)
        assertEquals("sse", recorded.requestUrl!!.queryParameter("alt"))
        assertNull(recorded.requestUrl!!.queryParameter("key"), "Key 不得出现在 URL")
        assertEquals(testKey, recorded.getHeader("x-goog-api-key"))
        val sent = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals("系统规范", sent["systemInstruction"]!!.jsonObject["parts"]!!
            .let { it as kotlinx.serialization.json.JsonArray }[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals(512, sent["generationConfig"]!!.jsonObject["maxOutputTokens"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `流内error对象转Done_ERROR`() = runTest {
        server.enqueue(ok(sse("""{"error":{"code":429,"message":"Resource exhausted"}}""")))
        val events = provider.chatStream(request()).toList()
        assertEquals(StreamEvent.Done(StopReason.ERROR("Resource exhausted")), events.single())
    }

    @Test
    fun `HTTP错误码分类`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"message":"denied"}}"""))
        assertFailsWith<AiError.Auth> { provider.chatStream(request()).toList() }
        server.enqueue(MockResponse().setResponseCode(429).setBody("limited"))
        assertFailsWith<AiError.RateLimit> { provider.chatStream(request()).toList() }
        server.enqueue(MockResponse().setResponseCode(500).setBody("down"))
        assertFailsWith<AiError.Server> { provider.chatStream(request()).toList() }
    }

    @Test
    fun `EOF无终止事件时补Done_STOP`() = runTest {
        server.enqueue(ok(sse("""{"candidates":[{"content":{"parts":[{"text":"尾"}]}}]}""")))
        val events = provider.chatStream(request()).toList()
        assertEquals(
            listOf(StreamEvent.ContentDelta("尾"), StreamEvent.Done(StopReason.STOP)),
            events,
        )
        assertTrue(events.last() is StreamEvent.Done)
    }
}
