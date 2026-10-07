package com.zhique.core.telemetry

import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** DebugServer 用真实 socket 回环测试：HTTP 解析、端点语义、端口回退。 */
class DebugServerTest {

    private lateinit var server: DebugServer

    @BeforeTest
    fun setup() {
        DebugHub.resetForTest()
        DebugHub.init(appVersion = "test", device = "jvm")
        // 端口 0 起：连本地临时端口也不与开发者已开的 8791 冲突不了——直接用 0 让内核分配不可行
        // （basePort+attempt 语义），测试用高位段避开常用端口
        server = DebugServer(basePort = 9377, maxAttempts = 5)
        server.start()
    }

    @AfterTest
    fun teardown() {
        server.stop()
        DebugHub.resetForTest()
    }

    private fun get(path: String, method: String = "GET"): Pair<Int, String> {
        val conn = URL("http://127.0.0.1:${server.port}$path").openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        val code = conn.responseCode
        val body = (if (code < 400) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        return code to body
    }

    @Test
    fun `health返回会话与版本`() {
        assertTrue(server.isRunning)
        val (code, body) = get("/debug/health")
        assertEquals(200, code)
        assertTrue(body.contains("\"app\":\"zhique\""))
        assertTrue(body.contains("\"version\":\"test\""))
        assertTrue(body.contains("\"serverPort\":${server.port}"))
    }

    @Test
    fun `events增量与mark打标`() {
        DebugHub.event("ui", "first")
        val (code1, body1) = get("/debug/events?since=0&limit=50")
        assertEquals(200, code1)
        assertTrue(body1.contains("first"))
        val since = DebugHub.recent(limit = 1).last().id

        val (code2, _) = get("/debug/mark?label=外部操作A", method = "POST")
        assertEquals(200, code2)
        val (_, body3) = get("/debug/events?since=$since&limit=50")
        assertTrue(body3.contains("debug.mark"))
        assertTrue(body3.contains("外部操作A"))
    }

    @Test
    fun `toast走宿主注入`() {
        var toastText: String? = null
        server.onToast = { toastText = it }
        val (code, _) = get("/debug/toast?text=%E4%BD%A0%E5%A5%BD", method = "POST")
        assertEquals(200, code)
        assertEquals("你好", toastText)
    }

    @Test
    fun `clear与404`() {
        DebugHub.event("ui", "x")
        assertEquals(200, get("/debug/clear", method = "POST").first)
        assertEquals(0, DebugHub.count())
        assertEquals(404, get("/debug/nope").first)
    }
}
