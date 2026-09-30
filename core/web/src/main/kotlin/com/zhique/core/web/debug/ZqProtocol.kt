package com.zhique.core.web.debug

/**
 * zq 调用协议的 native 侧回写约定：
 * - 未注册能力：collector 收到 zq_call 且 [TimelineReducer.ZqCallRouter.route] 未命中时，
 *   立即回 [rejectJs]，页面侧 promise 不悬空；
 * - 超时兜底：页面侧（zhique-bridge.js）对 pending 调用 30s 后自行
 *   `__zqResolve(id,false,"timeout")`，双保险防 [pending 泄漏]。
 */
object ZqProtocol {

    const val TIMEOUT_MS = 30_000L

    /** 构造在页面执行的 settle 调用；value 做引号/反斜杠转义防注入。 */
    fun resolveJs(id: Long?, ok: Boolean, value: String): String {
        val safe = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        return "window.__zqResolve && __zqResolve(${id ?: -1L}, $ok, \"$safe\")"
    }

    fun rejectJs(id: Long?, message: String): String = resolveJs(id, ok = false, value = message)
}
