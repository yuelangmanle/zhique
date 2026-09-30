package com.zhique.core.ai

import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 「拉取模型列表」（Task 3.6）：三协议 /models 端点解析。 */
class ModelListFetcherTest {

    private lateinit var server: MockWebServer
    private lateinit var fetcher: ModelListFetcher
    private val testKey = "test-key-" + UUID.randomUUID().toString().replace("-", "").take(16)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        fetcher = ModelListFetcher(OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun base() = server.url("/").toString().trimEnd('/')

    @Test
    fun `openai协议拉取models列表`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"object":"list","data":[{"id":"gpt-4o"},{"id":"deepseek-chat"},{"id":"x"}]}""",
            ),
        )
        val models = fetcher.fetch(Protocol.OPENAI_COMPATIBLE, base(), testKey)
        assertEquals(listOf("gpt-4o", "deepseek-chat", "x"), models)
        val req = server.takeRequest()
        assertEquals("/v1/models", req.path)
        assertEquals("Bearer $testKey", req.getHeader("Authorization"))
    }

    @Test
    fun `anthropic协议带版本头`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[{"id":"claude-sonnet-4"}]}"""))
        val models = fetcher.fetch(Protocol.ANTHROPIC_MESSAGES, base(), testKey)
        assertEquals(listOf("claude-sonnet-4"), models)
        val req = server.takeRequest()
        assertEquals("/v1/models", req.path)
        assertEquals(testKey, req.getHeader("x-api-key"))
    }

    @Test
    fun `gemini协议解析models名并剥前缀`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"models":[{"name":"models/gemini-2.5-pro"},{"name":"models/gemini-2.5-flash"}]}""",
            ),
        )
        val models = fetcher.fetch(Protocol.GOOGLE_GENAI, base(), testKey)
        assertEquals(listOf("gemini-2.5-pro", "gemini-2.5-flash"), models)
        val req = server.takeRequest()
        assertTrue(req.path!!.startsWith("/v1beta/models"))
        assertEquals(testKey, req.getHeader("x-goog-api-key"))
    }

    @Test
    fun `HTTP错误抛AiError`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad"}}"""))
        val e = runCatching { fetcher.fetch(Protocol.OPENAI_COMPATIBLE, base(), testKey) }
            .exceptionOrNull()
        assertTrue(e is AiError.Auth)
    }
}
