package com.zhique.core.permission.zq

/**
 * 订阅事件推送协议（EventChannel 风格）：
 * 页面侧 zhique-bridge.js 提供 `window.__zqEvent(sub, value)`，value 为 JSON 字符串。
 */
object ZqEvents {

    /** 构造在页面执行的推送调用；value 做引号/反斜杠转义防注入（同 ZqProtocol 约定）。 */
    fun pushJs(sub: String, value: String): String {
        val safe = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        return "window.__zqEvent && __zqEvent(\"$sub\", \"$safe\")"
    }
}
