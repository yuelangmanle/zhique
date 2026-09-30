package com.zhique.runner.agent

import android.graphics.Bitmap
import android.util.Base64
import com.zhique.core.agent.WebControl
import com.zhique.core.agent.tools.DomSnapshot
import com.zhique.core.web.debug.EventBuffer
import com.zhique.core.web.WebViewHost
import com.zhique.core.web.debug.TimelineReducer
import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 运行器 → Agent 的桥：RunnerScreen 把 WebViewHost 与事件缓冲登记进来，
 * 「交给 Agent」时 Agent 据此构造 [HostWebControl]（跨模块直连既有宿主）。
 */
class AgentBridge {
    var host: WebViewHost? = null
    var buffer: EventBuffer? = null

    fun webControl(): WebControl? {
        val h = host ?: return null
        return HostWebControl(h, buffer ?: EventBuffer())
    }

    /** RunnerScreen 结束时解绑，避免持有已销毁的 WebView。 */
    fun unbind(host: WebViewHost) {
        if (this.host === host) {
            this.host = null
            this.buffer = null
        }
    }
}

/** 运行器未打开时的占位控制面：run 一步报错进时间线，循环不断。 */
object NullWebControl : WebControl {
    override val running = false
    override suspend fun run() = throw IllegalStateException("运行器未打开，无法运行页面")
    override suspend fun stop() {}
    override suspend fun reload() {}
    override suspend fun consoleProblems() = emptyList<com.zhique.core.web.debug.TimelineEntry>()
    override suspend fun domSummary(): String? = null
    override suspend fun screenshotDataUrl(): String? = null
}

/** [WebViewHost] 薄适配（规格 §4.5 工具箱的运行器控制面）。 */
class HostWebControl(
    private val host: WebViewHost,
    private val buffer: EventBuffer,
) : WebControl {

    override val running: Boolean get() = true

    override suspend fun run() {
        host.loadIndex()
    }

    override suspend fun stop() {
        host.evaluate("if (window.stop) window.stop()")
    }

    override suspend fun reload() {
        host.reload()
    }

    override suspend fun consoleProblems() = TimelineReducer.reduce(buffer.events).problems

    override suspend fun domSummary(): String? = host.evaluateJs(DomSnapshot.js())

    override suspend fun screenshotDataUrl(): String? {
        val bitmap = host.snapshot()
        return bitmapToDataUrl(bitmap)
    }

    companion object {
        /** PixelCopy 位图 → PNG data URL（截图照样拍给用户看 + vision 回看）。 */
        fun bitmapToDataUrl(bitmap: Bitmap): String {
            val os = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 80, os)
            return "data:image/png;base64," + Base64.encodeToString(os.toByteArray(), Base64.NO_WRAP)
        }
    }
}

/** read_console 结果的轻量投影（UI 复用）。 */
internal fun consoleProblemLines(problems: List<com.zhique.core.web.debug.TimelineEntry>): List<String> =
    problems.take(20).map { e ->
        buildString {
            append("[${e.event.type}")
            if (!e.event.level.isNullOrBlank()) append("/${e.event.level}")
            append("]")
            if (e.count > 1) append("×${e.count} ")
            append(e.event.text ?: e.event.message ?: e.event.reason ?: "")
        }
    }

/** 供 AgentController 组装 read_console JSON 的辅助（避免 UI 层重复构建）。 */
internal fun problemsJson(problems: List<com.zhique.core.web.debug.TimelineEntry>) = buildJsonObject {
    put("total", problems.size)
    put("problems", buildJsonArray {
        problems.take(50).forEach { e ->
            add(buildJsonObject {
                put("type", e.event.type)
                put("text", e.event.text ?: e.event.message ?: e.event.reason ?: "")
                put("count", e.count)
            })
        }
    })
}
