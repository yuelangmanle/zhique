package com.zhique.core.agent

import com.zhique.core.web.debug.TimelineEntry

/**
 * 运行器控制面（计划 Task 4.1）：:app 侧由既有 [com.zhique.core.web.WebViewHost] 薄适配实现，
 * 测试用假实现。跨进程直连对工具层透明。
 */
interface WebControl {
    val running: Boolean

    /** 启动项目页（等价运行器「▶」）。 */
    suspend fun run()

    suspend fun stop()

    suspend fun reload()

    /** TimelineReducer 问题流（console error/warn + js_error/promise_reject/white_screen/web_crash）。 */
    suspend fun consoleProblems(): List<TimelineEntry>

    /** evaluateJavascript 序列化的 DOM 树摘要（未加载返回 null）。 */
    suspend fun domSummary(): String?

    /** PixelCopy 截图的 data URL（失败返回 null）。 */
    suspend fun screenshotDataUrl(): String?
}
