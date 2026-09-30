package com.zhique.core.web.debug

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 织雀桥（assets/zhique-bridge.js）采集的调试事件。
 *
 * 字段按事件类型取值：
 * - `console`：level + text
 * - `js_error`：message/line/col/stack
 * - `promise_reject`：reason
 * - `network_fail`：url/status/method
 * - `resource_error`：url
 * - `white_screen`：url
 * - `zq_call`：id/ns/fn/args（走 [TimelineReducer.ZqCallRouter] 分发，不进时间线）
 */
@Serializable
data class DebugEvent(
    val seq: Long,
    val t: Long,
    val type: String,
    val level: String? = null,
    val text: String? = null,
    val message: String? = null,
    val line: Int? = null,
    val col: Int? = null,
    val stack: String? = null,
    val reason: String? = null,
    val url: String? = null,
    val status: Int? = null,
    val method: String? = null,
    val id: Long? = null,
    val ns: String? = null,
    val fn: String? = null,
    val args: String? = null,
    val timeout: Long? = null, // zq_call 分级超时元数据（页面侧声明，native 同表强制）
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** 桥事件 JSON 解析；损坏行返回 null 而非抛错（采集器不崩运行时）。 */
        fun fromJson(raw: String): DebugEvent? =
            runCatching { json.decodeFromString(serializer(), raw) }.getOrNull()
    }
}

/** 折叠后的时间线条目：[count] 为同键连发次数。 */
data class TimelineEntry(val event: DebugEvent, val isProblem: Boolean, val count: Int = 1)

/** 三流时间线：问题流（报错页签）/ 输出流（Console 页签）/ 网络流（网络页签）。 */
data class Timeline(
    val entries: List<TimelineEntry>,
    val problems: List<TimelineEntry>,
    val console: List<TimelineEntry>,
    val network: List<TimelineEntry>,
)
