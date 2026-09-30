package com.zhique.core.web.debug

/**
 * 时间线折叠器：桥事件流 → 三流时间线（纯函数，喂调试抽屉与 AI 上下文）。
 *
 * - console error/warn 与 js_error/promise_reject/white_screen 归**问题流**；
 * - console log/info/debug 归**输出流**；
 * - network_fail/resource_error 归**网络流**；
 * - `zq_call` 交 [ZqCallRouter] 分发，不进时间线；`metrics` 仅统计用，忽略；
 * - 同（type+level+text+message+url）连续事件折叠为一条并累加 [TimelineEntry.count]。
 */
object TimelineReducer {

    const val TYPE_CONSOLE = "console"
    const val TYPE_JS_ERROR = "js_error"
    const val TYPE_PROMISE_REJECT = "promise_reject"
    const val TYPE_WHITE_SCREEN = "white_screen"
    const val TYPE_NETWORK_FAIL = "network_fail"
    const val TYPE_RESOURCE_ERROR = "resource_error"
    const val TYPE_ZQ_CALL = "zq_call"
    const val TYPE_METRICS = "metrics"
    const val TYPE_WEB_CRASH = "web_crash"

    /** zq_call 事件 → `ns.fn` 处理器分发器（M5 注册真实能力实现，此处机制先行）。 */
    class ZqCallRouter {
        fun interface Handler {
            fun onCall(event: DebugEvent)
        }

        private val handlers = mutableMapOf<String, Handler>()

        fun register(ns: String, fn: String, handler: Handler) {
            handlers["$ns.$fn"] = handler
        }

        /** 返回是否命中已注册处理器并完成分发。 */
        fun route(event: DebugEvent): Boolean {
            if (event.type != TYPE_ZQ_CALL) return false
            val handler = handlers["${event.ns}.${event.fn}"] ?: return false
            handler.onCall(event)
            return true
        }
    }

    private val PROBLEM_LEVELS = setOf("error", "warn")
    private val PROBLEM_TYPES =
        setOf(TYPE_JS_ERROR, TYPE_PROMISE_REJECT, TYPE_WHITE_SCREEN, TYPE_WEB_CRASH)
    private val NETWORK_TYPES = setOf(TYPE_NETWORK_FAIL, TYPE_RESOURCE_ERROR)

    fun isProblem(event: DebugEvent): Boolean =
        event.type in PROBLEM_TYPES ||
            (event.type == TYPE_CONSOLE && event.level in PROBLEM_LEVELS)

    fun reduce(events: List<DebugEvent>, zqRouter: ZqCallRouter? = null): Timeline {
        val entries = mutableListOf<TimelineEntry>()
        for (e in events) {
            when (e.type) {
                TYPE_ZQ_CALL -> { zqRouter?.route(e); continue }
                TYPE_METRICS -> continue
            }
            val last = entries.lastOrNull()
            if (last != null && sameKey(last.event, e)) {
                entries[entries.size - 1] = last.copy(count = last.count + 1)
            } else {
                entries += TimelineEntry(e, isProblem(e))
            }
        }
        return Timeline(
            entries = entries,
            problems = entries.filter { it.isProblem },
            console = entries.filter { !it.isProblem && it.event.type == TYPE_CONSOLE },
            network = entries.filter { it.event.type in NETWORK_TYPES },
        )
    }

    private fun sameKey(a: DebugEvent, b: DebugEvent): Boolean =
        a.type == b.type && a.level == b.level && a.text == b.text &&
            a.message == b.message && a.url == b.url
}
