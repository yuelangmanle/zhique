package com.zhique.core.web.debug

/**
 * zq 调用协议的 native 侧回写约定：
 * - 未注册能力：collector 收到 zq_call 且 [TimelineReducer.ZqCallRouter.route] 未命中时，
 *   立即回 [rejectJs]，页面侧 promise 不悬空；
 * - 超时兜底：页面侧（zhique-bridge.js）对 pending 调用按 [timeoutMs] 分级计时
 *   （慢能力 120s，默认 30s），超时自行 `__zqResolve(id,false,"timeout")`；
 *   native 侧（ZqDispatcher）以同一分级表强制执行，双保险防 pending 泄漏。
 */
object ZqProtocol {

    const val TIMEOUT_MS = 30_000L
    const val TIMEOUT_SLOW_MS = 120_000L

    /**
     * 分级超时（质量审查 Minor #5）：默认 30s；
     * 凡会弹「授权卡 + 系统权限确认框」的调用一律 120s——用户在卡片上停留
     * 超过 30s 时，30s 兜底会先触发误报 timeout（TV 走查实锤：相机授权卡
     * 停留 30s+ 后页面显示 timeout 而非拒绝语义）。页面侧 JS 表与本表保持一致。
     */
    fun timeoutMs(ns: String?, fn: String?): Long = when {
        ns == "camera" -> TIMEOUT_SLOW_MS
        ns == "mic" -> TIMEOUT_SLOW_MS
        ns == "screen" -> TIMEOUT_SLOW_MS
        ns == "file" -> TIMEOUT_SLOW_MS
        else -> TIMEOUT_MS
    }

    /** 构造在页面执行的 settle 调用；value 做转义防注入（含 \r \u2028 \u2029）。 */
    fun resolveJs(id: Long?, ok: Boolean, value: String): String {
        val safe = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\u2028", "\\u2028")
            .replace("\u2029", "\\u2029")
        return "window.__zqResolve && __zqResolve(${id ?: -1L}, $ok, \"$safe\")"
    }

    fun rejectJs(id: Long?, message: String): String = resolveJs(id, ok = false, value = message)
}
