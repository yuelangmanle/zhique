package com.zhique.core.web.debug

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TimelineReducerTest {

    private fun console(level: String, text: String, seq: Long = 1) =
        DebugEvent(seq = seq, t = 0, type = "console", level = level, text = text)

    @Test
    fun `error与warn归问题流`() {
        val tl = TimelineReducer.reduce(
            listOf(
                console("error", "boom"),
                console("warn", "careful"),
                DebugEvent(seq = 3, t = 0, type = "js_error", message = "x is not defined", line = 12),
                DebugEvent(seq = 4, t = 0, type = "promise_reject", reason = "timeout"),
                DebugEvent(seq = 5, t = 0, type = "white_screen", url = "about:blank"),
                DebugEvent(seq = 6, t = 0, type = "web_crash", text = "渲染进程崩溃 #1"),
            ),
        )
        assertEquals(6, tl.problems.size)
        assertTrue(tl.console.isEmpty())
        assertTrue(tl.problems.all { it.isProblem })
    }

    @Test
    fun `log归输出流`() {
        val tl = TimelineReducer.reduce(
            listOf(console("log", "hello"), console("info", "hi"), console("debug", "dbg")),
        )
        assertEquals(3, tl.console.size)
        assertTrue(tl.problems.isEmpty())
        assertTrue(tl.console.all { !it.isProblem })
    }

    @Test
    fun `同text五连发折叠为单条计数5`() {
        val events = (1..5L).map { console("log", "loop", seq = it) }
        val tl = TimelineReducer.reduce(events)
        assertEquals(1, tl.console.size)
        assertEquals(5, tl.console[0].count)
        // 不同 text 打断连发，重新起条目
        val mixed = (1..3L).map { console("log", "a", seq = it) } +
            listOf(console("log", "b", seq = 4)) +
            (5..7L).map { console("log", "a", seq = it) }
        val tl2 = TimelineReducer.reduce(mixed)
        assertEquals(3, tl2.console.size)
        assertEquals(3, tl2.console[0].count)
        assertEquals(1, tl2.console[1].count)
        assertEquals(3, tl2.console[2].count)
    }

    @Test
    fun `zq_call路由到已注册处理器且未注册不分发`() {
        val router = TimelineReducer.ZqCallRouter()
        var routed: DebugEvent? = null
        router.register("device", "info") { routed = it }
        val call = DebugEvent(
            seq = 1, t = 0, type = "zq_call",
            id = 7, ns = "device", fn = "info", args = """["a",1]""",
        )
        val stray = DebugEvent(seq = 2, t = 0, type = "zq_call", id = 8, ns = "file", fn = "read", args = "[]")
        val tl = TimelineReducer.reduce(listOf(call, stray, console("log", "ok")), zqRouter = router)
        assertEquals(7L, routed?.id)
        assertEquals("""["a",1]""", routed?.args)
        assertFalse(router.route(stray)) // 未注册的 ns.fn 不分发
        assertTrue(tl.entries.none { it.event.type == "zq_call" })
        assertEquals(1, tl.console.size)
    }

    @Test
    fun `网络失败与资源错误归网络流`() {
        val tl = TimelineReducer.reduce(
            listOf(
                DebugEvent(seq = 1, t = 0, type = "network_fail", url = "https://x/api", status = 500),
                DebugEvent(seq = 2, t = 0, type = "resource_error", url = "https://x/404.png"),
            ),
        )
        assertEquals(2, tl.network.size)
        assertTrue(tl.problems.isEmpty())
        assertTrue(tl.console.isEmpty())
    }

    @Test
    fun `fromJson容忍未知字段`() {
        val e = DebugEvent.fromJson(
            """{"seq":1,"t":123,"type":"console","level":"log","text":"hi","extra":"zzz","reason":"r"}""",
        )
        assertEquals("hi", e?.text)
        assertEquals("r", e?.reason)
        assertEquals(null, DebugEvent.fromJson("{ not json"))
    }
}
