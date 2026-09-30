package com.zhique.core.ai

import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 质量审查修复回归：SSE 取消即断连、行长上限、ChatRequest toString 抹 Key、
 * Gemini 多轮工具历史、Anthropic thinking budget 收紧、tailCodePoints 代理对安全、
 * stitch 悬空 token 两侧行为。
 */
class ReviewFixCoreTest {

    // ---- fix 1 / 7：SSE 取消断连与行长上限 ----

    private lateinit var server: MockWebServer
    private lateinit var provider: OpenAiCompatProvider
    private val testKey = "test-key-" + UUID.randomUUID().toString().replace("-", "").take(16)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        provider = OpenAiCompatProvider(
            OkHttpClient.Builder().readTimeout(20, TimeUnit.SECONDS).build(),
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
        messages = listOf(ChatMessage("user", "hi")),
        maxTokens = 64,
    )

    @Test
    fun `fix1_collector取消后连接立即断开`() = runTest {
        // 响应头立即到达、正文延迟 30s：provider 阻塞在 readLine 等正文
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("")
                .setBodyDelay(30, TimeUnit.SECONDS),
        )
        val job = launch(Dispatchers.IO) {
            try {
                provider.chatStream(request()).collect { }
            } catch (_: Throwable) {
                // 断连异常：取消竞态下允许
            }
        }
        assertTrue(server.takeRequest(5, TimeUnit.SECONDS) != null, "连接已建立")
        job.cancel()
        // join 等待放真实线程：runTest 虚拟时间会瞬时烧掉 withTimeout 的 3 秒
        val joined = withContext(Dispatchers.IO) { withTimeoutOrNull(3_000) { job.join(); true } }
        assertTrue(joined == true, "取消后 3 秒内必须断开连接（阻塞读未被中断）")
    }

    @Test
    fun `fix7_超长行断流报Protocol错误`() = runTest {
        val big = "data: " + "x".repeat(1024 * 1024 + 16) + "\n\n"
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody(big),
        )
        val e = assertFailsWith<AiError.Protocol> { provider.chatStream(request()).toList() }
        assertTrue("超长" in e.message!!, e.message)
    }

    // ---- fix 6：ChatRequest toString 抹 apiKey ----

    @Test
    fun `fix6_toString抹除apiKey`() {
        val key = "test-key-" + "s".repeat(12)
        val req = ChatRequest(
            baseUrl = "https://example.invalid",
            apiKey = key,
            model = "m",
            messages = listOf(ChatMessage("user", "hi")),
            maxTokens = 8,
        )
        val s = req.toString()
        assertTrue(key !in s, "toString 不得泄漏明文 Key：$s")
        assertTrue("***" in s)
    }

    // ---- fix 3 / 9：stitch 悬空 token 与 tailCodePoints ----

    @Test
    fun `fix3_前段尾非悬空token时保留换行`() {
        // 行完整（悬空 token 检查不过）→ 不并行两行
        assertEquals("a\nb\nc", stitch("a\nb", "\nc"))
        assertEquals("done\nmore", stitch("done", "\nmore"))
    }

    @Test
    fun `fix3_前段尾悬空token时仍去空行衔接`() {
        assertEquals("const t = sum(1);", stitch("const t = su", "\nm(1);"))
        assertEquals("x = 5;", stitch("x = ", "\n5;"))
        assertEquals("run(fn,1, 2)", stitch("run(fn,", "\n1, 2)"))
        assertEquals("return doSomething;", stitch("return do", "\nSomething;"))
    }

    @Test
    fun `fix9_takeLast代理对安全`() {
        val emoji = "\uD83D\uDE00"
        val s = "a".repeat(10) + emoji + "b".repeat(5)
        val tail = tailCodePoints(s, 6) // 恰好切在代理对中间
        assertFalse(tail.first().isLowSurrogate(), "不得以低代理开头")
        assertEquals(emoji + "b".repeat(5), tail)
    }

    // ---- fix 4：Gemini 多轮工具历史 ----

    @Test
    fun `fix4_gemini多轮工具对话_assistant转functionCall_tool转functionResponse`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"好\"}]},\"finishReason\":\"STOP\"}]}\n\n"),
        )
        val gemini = GeminiProvider(OkHttpClient())
        val req = ChatRequest(
            baseUrl = server.url("/").toString().trimEnd('/'),
            apiKey = testKey,
            model = "test-model",
            messages = listOf(
                ChatMessage("user", "跑一下"),
                ChatMessage("assistant", "", toolCallsJson = """[{"id":"c1","name":"run","arguments":"{\"url\":\"a\"}"}]"""),
                ChatMessage("tool", "结果ok", toolCallId = "run"),
            ),
            maxTokens = 100,
        )
        gemini.chatStream(req).toList()
        val contents = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["contents"]!!.jsonArray
        val modelTurn = contents[1].jsonObject
        assertEquals("model", modelTurn["role"]!!.jsonPrimitive.content)
        val call = modelTurn["parts"]!!.jsonArray
            .map { it.jsonObject }
            .firstOrNull { it.containsKey("functionCall") }
            ?.get("functionCall")!!.jsonObject
        assertEquals("run", call["name"]!!.jsonPrimitive.content)
        assertEquals("a", call["args"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        val toolTurn = contents[2].jsonObject
        val response = toolTurn["parts"]!!.jsonArray
            .map { it.jsonObject }
            .firstOrNull { it.containsKey("functionResponse") }
            ?.get("functionResponse")!!.jsonObject
        assertEquals("run", response["name"]!!.jsonPrimitive.content)
        assertEquals("结果ok", response["response"]!!.jsonObject["result"]!!.jsonPrimitive.content)
    }

    // ---- fix 8：Anthropic thinking budget 收紧 ----

    @Test
    fun `fix8_thinkingBudget必须小于maxTokens`() {
        assertEquals(1023, thinkingBudget(maxTokens = 1024), "budget==max_tokens 会被 400")
        assertEquals(2048, thinkingBudget(maxTokens = 4096))
        assertEquals(99, thinkingBudget(maxTokens = 100))
    }
}
