package com.zhique.core.agent.tools

import com.zhique.core.agent.Tool
import com.zhique.core.agent.ToolContext
import com.zhique.core.agent.ToolRegistry
import com.zhique.core.agent.WebControl
import com.zhique.core.ai.ToolSchema
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Web 工具组（规格 §4.5）：run/stop/reload、read_console（时间线问题流）、
 * read_dom_snapshot（evaluateJavascript DOM 树摘要 ≤64KB）、screenshot_page（仅 vision）。
 */
object WebTools {
    const val RUN = "run"
    const val STOP = "stop"
    const val RELOAD = "reload"
    const val READ_CONSOLE = "read_console"
    const val READ_DOM_SNAPSHOT = "read_dom_snapshot"
    const val SCREENSHOT_PAGE = "screenshot_page"

    /** DOM 摘要字节上限（规格：≤64KB）。 */
    const val DOM_SUMMARY_CAP = 64 * 1024

    fun all(): List<Tool> = listOf(
        RunTool, StopTool, ReloadTool, ReadConsoleTool, ReadDomSnapshotTool, ScreenshotPageTool,
    )
}

private fun schema(name: String, description: String, properties: String = "{}") = ToolSchema(
    name = name,
    description = description,
    parametersJson = """{"type":"object","properties":$properties,"additionalProperties":true}""",
)

internal object RunTool : Tool {
    override val name = WebTools.RUN
    override val schema = schema(name, "运行当前项目的 index.html（启动/重载 WebView）")
    override suspend fun invoke(ctx: ToolContext, args: kotlinx.serialization.json.JsonElement) = simple(ctx.web, "run")
}

internal object StopTool : Tool {
    override val name = WebTools.STOP
    override val schema = schema(name, "停止运行当前项目页")
    override suspend fun invoke(ctx: ToolContext, args: kotlinx.serialization.json.JsonElement) = simple(ctx.web, "stop")
}

internal object ReloadTool : Tool {
    override val name = WebTools.RELOAD
    override val schema = schema(name, "重载当前项目页（应用 edit_file 的修改后必用）")
    override suspend fun invoke(ctx: ToolContext, args: kotlinx.serialization.json.JsonElement) = simple(ctx.web, "reload")
}

private suspend fun simple(web: WebControl, action: String) = buildJsonObject {
    when (action) {
        "run" -> web.run()
        "stop" -> web.stop()
        else -> web.reload()
    }
    put("status", "ok")
    put("running", web.running)
}

internal object ReadConsoleTool : Tool {
    override val name = WebTools.READ_CONSOLE
    override val schema = schema(name, "读取页面问题流（报错/console error/warn/白屏/崩溃），已按同源事件折叠计数")

    override suspend fun invoke(ctx: ToolContext, args: kotlinx.serialization.json.JsonElement) = buildJsonObject {
        val problems = ctx.web.consoleProblems()
        put("total", problems.size)
        put("problems", buildJsonArray {
            problems.take(50).forEach { e ->
                add(buildJsonObject {
                    put("type", e.event.type)
                    put("level", e.event.level ?: "")
                    put("text", e.event.text ?: e.event.message ?: e.event.reason ?: "")
                    put("url", e.event.url ?: "")
                    put("line", e.event.line ?: -1)
                    put("count", e.count)
                })
            }
        })
    }
}

internal object ReadDomSnapshotTool : Tool {
    override val name = WebTools.READ_DOM_SNAPSHOT
    override val schema = schema(name, "读取当前页面 DOM 树摘要（tag/id/class/文本前80字/子节点数），纯文本模型替代截图的观察手段")

    override suspend fun invoke(ctx: ToolContext, args: kotlinx.serialization.json.JsonElement) = buildJsonObject {
        val summary = DomSnapshot.truncate(ctx.web.domSummary())
        put("summary", summary ?: "无 DOM 摘要：页面尚未加载")
    }
}

internal object ScreenshotPageTool : Tool {
    override val name = WebTools.SCREENSHOT_PAGE
    override val schema = schema(
        name,
        "对当前页面截图（PixelCopy），返回 data URL 供视觉自查。仅 vision 模型可用",
    )

    override suspend fun invoke(ctx: ToolContext, args: kotlinx.serialization.json.JsonElement) = buildJsonObject {
        val url = ctx.web.screenshotDataUrl()
        put("image", url?.let { JsonPrimitive(it) } ?: JsonPrimitive(null))
        if (url == null) put("error", "截图失败")
    }
}

/**
 * DOM 树摘要（规格 §4.5）：tag/id/class/文本前 80 字/子节点数。
 * JS 侧限深 12 层、限节点 400 个防巨型 DOM；宿主把 `evaluateJavascript` 的返回字符串原样送进 [truncate]。
 */
object DomSnapshot {
    const val MAX_CHARS = 64 * 1024

    fun js(): String = """
        |(function(){
        |  var MAX_DEPTH = 12, MAX_NODES = 400, nodes = 0;
        |  function textOf(el){
        |    var t = '';
        |    for (var i = 0; i < el.childNodes.length && t.length < 80; i++) {
        |      var c = el.childNodes[i];
        |      if (c.nodeType === 3) t += (c.nodeValue || '').trim();
        |    }
        |    return t.slice(0, 80);
        |  }
        |  function walk(el, depth){
        |    if (!el || nodes >= MAX_NODES || depth > MAX_DEPTH) return null;
        |    nodes++;
        |    var o = { tag: el.tagName ? el.tagName.toLowerCase() : '#doc' };
        |    if (el.id) o.id = el.id;
        |    if (el.className && typeof el.className === 'string' && el.className.trim()) o.cls = el.className.trim().slice(0, 60);
        |    var t = textOf(el);
        |    if (t) o.text = t;
        |    var kids = [];
        |    for (var i = 0; i < el.children.length; i++) {
        |      var k = walk(el.children[i], depth + 1);
        |      if (k) kids.push(k);
        |      if (nodes >= MAX_NODES) break;
        |    }
        |    o.childrenCount = el.children.length;
        |    if (kids.length) o.children = kids;
        |    return o;
        |  }
        |  return JSON.stringify(walk(document.body || document.documentElement, 0));
        |})()
    """.trimMargin()

    /** ≤64KB 守卫：超出截断并标注（截断标记本身计入长度）。 */
    fun truncate(summary: String?): String? {
        if (summary == null) return null
        if (summary.length <= MAX_CHARS) return summary
        val marker = "…[截断]"
        return summary.take(MAX_CHARS - marker.length) + marker
    }
}
