package com.zhique.core.agent

import com.zhique.core.agent.tools.SCRIPT_STYLE_BLOCK
import com.zhique.core.agent.tools.UnclosedDetector

/**
 * 文件地图（规格 §4.5 三层记忆：repo map 简化版——结构树 + 符号索引，不塞全文；
 * 每文件段 ≤[PER_FILE_CAP] 字节，大项目靠 read_file/grep 按需取用）。
 * 符号：HTML→id/class；JS→function/const/let/class 名；CSS→顶层选择器。
 */
object FileMap {

    /** 单文件段上限（规格：≤2KB/文件）。 */
    const val PER_FILE_CAP = 2048

    private val HTML_ID = Regex("""\bid\s*=\s*["']([^"']+)["']""")
    private val HTML_CLASS = Regex("""\bclass\s*=\s*["']([^"']+)["']""")
    private val JS_SYMBOL = Regex(
        """\b(?:function\s+([A-Za-z_$][\w$]*)|(?:const|let|var)\s+([A-Za-z_$][\w$]*)|class\s+([A-Za-z_$][\w$]*))\b""",
    )
    private val CSS_SELECTOR = Regex("""(^|\n)\s*([.#]?[A-Za-z][\w-]*(?:\s*[>,][\s\S]*?)?)\s*\{""")

    /** [files] 为 (相对路径, 全文) 列表；输出结构树 + 每文件符号索引。 */
    fun build(files: List<Pair<String, String>>): String {
        if (files.isEmpty()) return ""
        return files.joinToString("\n") { (path, content) ->
            "- $path\n" + section(path, content)
        }.trimEnd()
    }

    private fun section(path: String, content: String): String {
        val symbols = symbolsFor(path, content)
        val body = symbols.joinToString("\n") { "  $it" }
        return if (body.length <= PER_FILE_CAP) body
        else body.take(PER_FILE_CAP) + "\n  …[符号索引截断]"
    }

    /** 单文件符号索引行（暴露给测试）。 */
    internal fun symbolsFor(path: String, content: String): List<String> {
        val lower = path.lowercase()
        return when {
            lower.endsWith(".html") || lower.endsWith(".htm") -> htmlSymbols(content)
            lower.endsWith(".css") -> cssSymbols(content)
            else -> jsSymbols(content) // js/ts/json 等按脚本处理
        }
    }

    private fun htmlSymbols(contentRaw: String): List<String> {
        // 内联 script/style 块剔除（含未闭合块）：JS 的 el.id = / className = 赋值会误配进索引。
        // 复用 UnclosedDetector 的字符串/注释剥离口径先把块内字符串内容中和，再整块移除。
        val content = SCRIPT_STYLE_BLOCK.replace(contentRaw) { m ->
            UnclosedDetector.stripStringsAndComments(m.value).ifBlank { "" }
        }
        val ids = HTML_ID.findAll(content).map { it.groupValues[1] }.distinct().take(SYMBOL_CAP).toList()
        val classes = HTML_CLASS.findAll(content)
            .flatMap { m -> m.groupValues[1].split(Regex("\\s+")) }
            .filter { it.isNotBlank() }
            .distinct()
            .take(SYMBOL_CAP)
            .toList()
        val out = mutableListOf<String>()
        if (ids.isNotEmpty()) out += "id: " + ids.joinToString(", ")
        if (classes.isNotEmpty()) out += "class: " + classes.joinToString(", ")
        return out
    }

    private fun jsSymbols(content: String): List<String> {
        val fns = mutableListOf<String>()
        val consts = mutableListOf<String>()
        val classes = mutableListOf<String>()
        for (m in JS_SYMBOL.findAll(content)) {
            val (fn, cn, cl) = m.destructured
            when {
                fn.isNotEmpty() -> fns += fn
                cn.isNotEmpty() -> consts += cn
                cl.isNotEmpty() -> classes += cl
            }
        }
        val out = mutableListOf<String>()
        if (fns.isNotEmpty()) out += "fn: " + fns.distinct().take(SYMBOL_CAP).joinToString(", ")
        if (consts.isNotEmpty()) out += "var: " + consts.distinct().take(SYMBOL_CAP).joinToString(", ")
        if (classes.isNotEmpty()) out += "class: " + classes.distinct().take(SYMBOL_CAP).joinToString(", ")
        return out
    }

    private fun cssSymbols(content: String): List<String> {
        val selectors = CSS_SELECTOR.findAll(content).map { it.groupValues[2].trim() }
            .filter { it.isNotEmpty() }.distinct().take(SYMBOL_CAP).toList()
        return if (selectors.isEmpty()) emptyList() else listOf("selector: " + selectors.joinToString(", "))
    }

    private const val SYMBOL_CAP = 30
}
