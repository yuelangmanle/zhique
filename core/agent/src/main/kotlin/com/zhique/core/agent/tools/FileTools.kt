package com.zhique.core.agent.tools

import com.zhique.core.agent.Tool
import com.zhique.core.agent.ToolContext
import com.zhique.core.agent.ToolException
import com.zhique.core.agent.ToolRegistry
import com.zhique.core.ai.ToolSchema
import com.zhique.core.project.ProjectRepository
import java.io.File
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 文件工具组（规格 §4.5）：edit_file（整文件替换或行区间补丁 + 未闭合检测）、
 * list_files / read_file / grep（正则+行号）。全部经 ProjectRepository 沙箱（路径逃逸由仓库拒绝）。
 */
object FileTools {
    const val EDIT_FILE = "edit_file"
    const val LIST_FILES = "list_files"
    const val READ_FILE = "read_file"
    const val GREP = "grep"

    const val GREP_LINE_CAP = 100

    fun all(): List<Tool> = listOf(EditFileTool, ListFilesTool, ReadFileTool, GrepTool)
}

internal object EditFileTool : Tool {
    override val name = FileTools.EDIT_FILE
    override val schema = ToolSchema(
        name = name,
        description = "编辑项目文件：整文件替换（给 path+content）或行区间替换（给 path+startLine+endLine+content，行号 1 起闭区间）。" +
            "产出会做未闭合检测（括号平衡/标签闭合），残缺代码会被拒绝并要求续写",
        parametersJson = """
            {"type":"object","properties":{
                "path":{"type":"string","description":"项目内相对路径，如 index.html"},
                "content":{"type":"string","description":"新内容（整文件或区间替换文本）"},
                "startLine":{"type":"integer","description":"行区间起点（1 起）"},
                "endLine":{"type":"integer","description":"行区间终点（闭区间）"}
            },"required":["path","content"],"additionalProperties":true}
        """.trimIndent(),
    )

    override suspend fun invoke(ctx: ToolContext, args: JsonElement): JsonObject {
        val path = ToolRegistry.str(args, "path")
            ?: throw ToolException("edit_file 缺 path")
        val content = ToolRegistry.str(args, "content")
            ?: throw ToolException("edit_file 缺 content")
        val startLine = ToolRegistry.int(args, "startLine")
        val endLine = ToolRegistry.int(args, "endLine")

        val next = if (startLine != null && endLine != null) {
            val current = runCatching { ctx.repo.readFile(ctx.projectId, path) }
                .getOrElse { throw ToolException("行区间替换要求文件已存在：$path") }
            applyLineRange(current, startLine, endLine, content)
        } else {
            content
        }

        UnclosedDetector.check(path, next)?.let { throw Tool.UnclosedCode(path, next, it) }

        ctx.repo.writeFile(ctx.projectId, path, next)
        return buildJsonObject {
            put("status", "ok")
            put("path", path)
            put("mode", if (startLine != null) "lines" else "whole")
            put("lines", next.count { it == '\n' } + if (next.isNotEmpty() && !next.endsWith("\n")) 1 else 0)
        }
    }

    /** 行区间替换（1 起闭区间）；越界钳到文件边界。 */
    internal fun applyLineRange(current: String, startLine: Int, endLine: Int, replacement: String): String {
        require(startLine >= 1 && endLine >= startLine) { "非法行区间：$startLine-$endLine" }
        val lines = current.split("\n").toMutableList()
        val from = (startLine - 1).coerceIn(0, lines.size)
        val to = endLine.coerceAtMost(lines.size)
        if (from > to) return current
        lines.subList(from, to).clear()
        lines.addAll(from, replacement.split("\n"))
        return lines.joinToString("\n")
    }
}

internal object ListFilesTool : Tool {
    override val name = FileTools.LIST_FILES
    override val schema = ToolSchema(
        name = name,
        description = "列出项目全部文件（相对路径，不含 history/）",
        parametersJson = """{"type":"object","properties":{},"additionalProperties":true}""",
    )

    override suspend fun invoke(ctx: ToolContext, args: JsonElement) = buildJsonObject {
        put("files", buildJsonArray {
            ProjectFiles.walk(ctx.repo, ctx.projectId).forEach { add(JsonPrimitive(it)) }
        })
    }
}

internal object ReadFileTool : Tool {
    override val name = FileTools.READ_FILE
    override val schema = ToolSchema(
        name = name,
        description = "读取项目文件全文（大文件只看前 32KB）",
        parametersJson = """
            {"type":"object","properties":{"path":{"type":"string"}},"required":["path"],"additionalProperties":true}
        """.trimIndent(),
    )

    const val READ_CAP = 32 * 1024

    override suspend fun invoke(ctx: ToolContext, args: JsonElement) = buildJsonObject {
        val path = ToolRegistry.str(args, "path") ?: throw ToolException("read_file 缺 path")
        val content = ctx.repo.readFile(ctx.projectId, path) // 沙箱逃逸/不存在在此抛出
        val clipped = if (content.length > READ_CAP) content.take(READ_CAP) + "…[截断]" else content
        put("path", path)
        put("content", clipped)
    }
}

internal object GrepTool : Tool {
    override val name = FileTools.GREP
    override val schema = ToolSchema(
        name = name,
        description = "在项目文件中正则搜索，输出「相对路径:行号:行文本」（最多 100 行）",
        parametersJson = """
            {"type":"object","properties":{
                "pattern":{"type":"string","description":"正则表达式"},
                "glob":{"type":"string","description":"可选：按扩展名过滤，如 .js"}
            },"required":["pattern"],"additionalProperties":true}
        """.trimIndent(),
    )

    override suspend fun invoke(ctx: ToolContext, args: JsonElement) = buildJsonObject {
        val pattern = ToolRegistry.str(args, "pattern") ?: throw ToolException("grep 缺 pattern")
        val glob = ToolRegistry.str(args, "glob")
        val regex = runCatching { Regex(pattern) }.getOrElse { throw ToolException("grep 正则非法：${it.message}") }
        var total = 0
        val lines = mutableListOf<String>()
        for (rel in ProjectFiles.walk(ctx.repo, ctx.projectId)) {
            if (glob != null && !rel.endsWith(glob)) continue
            val content = runCatching { ctx.repo.readFile(ctx.projectId, rel) }.getOrNull() ?: continue
            content.lineSequence().forEachIndexed { idx, line ->
                if (regex.containsMatchIn(line)) {
                    total++
                    if (lines.size < FileTools.GREP_LINE_CAP) lines += "$rel:${idx + 1}:$line"
                }
            }
        }
        put("total", total)
        put("matches", buildJsonArray { lines.forEach { add(JsonPrimitive(it)) } })
    }
}

/** 项目文件遍历（相对路径，跳过 history/），排序稳定；供 :app 编辑器/文件地图复用。 */
object ProjectFiles {
    const val SKIP_DIR = "history"

    fun walk(repo: ProjectRepository, projectId: String): List<String> {
        val root = repo.projectDir(projectId)
        if (!root.isDirectory) return emptyList()
        val out = mutableListOf<String>()
        fun visit(dir: File, prefix: String) {
            for (child in dir.listFiles()?.sortedBy { it.name } ?: emptyList()) {
                if (child.isDirectory) {
                    if (child.name == SKIP_DIR) continue
                    visit(child, "$prefix${child.name}/")
                } else {
                    out += prefix + child.name
                }
            }
        }
        visit(root, "")
        return out
    }
}

/** script/style 块（含未闭合块）提取：FileMap 剔除内联 JS/CSS 用。 */
internal val SCRIPT_STYLE_BLOCK = Regex(
    "<script\\b[^>]*>[\\s\\S]*?(?:</script\\s*>|\\z)|<style\\b[^>]*>[\\s\\S]*?(?:</style\\s*>|\\z)",
    RegexOption.IGNORE_CASE,
)

/**
 * 未闭合检测（M3 规格审查遗留接线 d）：括号平衡 + `<script>/<style>/<html>` 闭合标签校验。
 * HTML 只检查标签闭合与 script/style 块内括号（页面正文文本不参与括号统计，避免误报）；
 * JS/TS 按全量括号平衡（剔除字符串与注释后）；CSS 按花括号平衡。
 * 返回 null = 通过；否则返回可读原因（编排器据此强制续写）。
 */
internal object UnclosedDetector {
    private val PAIRS = mapOf(')' to '(', ']' to '[', '}' to '{')

    fun check(path: String, code: String): String? {
        val lower = path.lowercase()
        return when {
            lower.endsWith(".html") || lower.endsWith(".htm") -> checkHtml(code)
            lower.endsWith(".css") -> balance(code, bracesOnly = true)
            else -> balance(code, bracesOnly = false) // js/ts/json 等按脚本处理
        }
    }

    private fun checkHtml(code: String): String? {
        for (tag in listOf("script", "style")) {
            val open = Regex("<$tag\\b", RegexOption.IGNORE_CASE).findAll(code).count()
            val close = Regex("</$tag\\s*>", RegexOption.IGNORE_CASE).findAll(code).count()
            if (open > close) return "<$tag> 标签未闭合（开 $open 处 / 闭 $close 处）"
        }
        val hasHtmlOpen = Regex("<html\\b", RegexOption.IGNORE_CASE).containsMatchIn(code)
        val hasHtmlClose = Regex("</html\\s*>", RegexOption.IGNORE_CASE).containsMatchIn(code)
        if (hasHtmlOpen && !hasHtmlClose) return "<html> 标签未闭合"
        val multi = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        // script/style 块内代码做括号平衡（残缺脚本最常见的形态）
        Regex("<script\\b[^>]*>(.*?)</script>", multi)
            .findAll(code)
            .forEach { m ->
                balance(m.groupValues[1], bracesOnly = false)?.let { return "script 块内$it" }
            }
        Regex("<style\\b[^>]*>(.*?)</style>", multi)
            .findAll(code)
            .forEach { m ->
                balance(m.groupValues[1], bracesOnly = true)?.let { return "style 块内$it" }
            }
        return null
    }

    /** 括号平衡：剔除字符串/注释后计数。[bracesOnly]=true 时只看花括号（CSS）。 */
    internal fun balance(code: String, bracesOnly: Boolean): String? {
        val clean = stripStringsAndComments(code)
        val stack = ArrayDeque<Char>()
        for (ch in clean) {
            when (ch) {
                '(' -> if (!bracesOnly) stack.addLast('(')
                '[' -> if (!bracesOnly) stack.addLast('[')
                '{' -> stack.addLast('{')
                ')', ']', '}' -> {
                    val expect = PAIRS.getValue(ch)
                    if (bracesOnly && ch != '}') continue
                    if (stack.isEmpty() || stack.removeLast() != expect) {
                        return "括号不平衡：出现多余的「$ch」"
                    }
                }
            }
        }
        if (stack.isNotEmpty()) {
            return "括号不平衡：「${stack.last()}」未闭合 ${stack.size} 处"
        }
        return null
    }

    /** 粗粒度剔除 JS/CSS 字符串与注释（处理转义）；保留结构括号。 */
    internal fun stripStringsAndComments(code: String): String {
        val sb = StringBuilder(code.length)
        var i = 0
        var state = 0 // 0 normal, 1 line comment, 2 block comment, 3 ' , 4 " , 5 `
        while (i < code.length) {
            val c = code[i]
            val next = if (i + 1 < code.length) code[i + 1] else ' '
            when (state) {
                0 -> when {
                    c == '/' && next == '/' -> { state = 1; i += 2; continue }
                    c == '/' && next == '*' -> { state = 2; i += 2; continue }
                    c == '\'' -> state = 3
                    c == '"' -> state = 4
                    c == '`' -> state = 5
                    else -> sb.append(c)
                }
                1 -> if (c == '\n') { state = 0; sb.append(c) }
                2 -> if (c == '*' && next == '/') { state = 0; i += 2; continue }
                3 -> when {
                    c == '\\' -> i++
                    c == '\'' -> state = 0
                }
                4 -> when {
                    c == '\\' -> i++
                    c == '"' -> state = 0
                }
                5 -> when {
                    c == '\\' -> i++
                    c == '`' -> state = 0
                }
            }
            i++
        }
        return sb.toString()
    }
}
