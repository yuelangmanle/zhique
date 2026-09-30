package com.zhique.core.paste

/**
 * 一次粘贴的形态（规格 §4.1.1）。分类只判定「是什么」，不改内容；
 * 清洗见 [Cleaner]，组装见 [Assembler]。
 */
sealed interface PasteForm {
    /** 完整 HTML 文档（含 `<!DOCTYPE` 或 `<html>` 标签）。 */
    data class CompleteHtml(val html: String) : PasteForm

    /** 说明文字与代码块混排（提取代码块按语言归类在处理阶段做）。 */
    data class MixedBlocks(val blocks: List<CodeBlock>, val prose: String) : PasteForm

    /** 多代码块（html/css/js 已按标注或指纹归类），供标准骨架组装。 */
    data class Fragments(val html: String?, val css: List<String>, val js: List<String>) : PasteForm

    /** 纯 JS 片段。 */
    data class JsOnly(val js: String) : PasteForm

    /** 纯 CSS 片段。 */
    data class CssOnly(val css: String) : PasteForm

    /** Apilot/JSON/cURL 配置（→ 转存 Provider 流程，M3 接线）。 */
    data class ApiConfig(val raw: String) : PasteForm

    /** 无法识别（置信度低时交 AI 兜底解析）。 */
    data class Unknown(val raw: String) : PasteForm
}

/** 一个围栏代码块；[lang] 已规范化为小写，无标注为空串。 */
data class CodeBlock(val lang: String, val code: String)

/** 分类结果：[confidence] 语义——>=0.8 规则明确命中；0.5–0.8 部分特征；更低触发 AI 兜底。 */
data class Classified(val form: PasteForm, val confidence: Float)

/** 置信度阈值常量（规格 §4.1.3：低于阈值交内置 AI 解析重组，仅结构化不改逻辑）。 */
object PasteConfidence {
    const val AI_FALLBACK_THRESHOLD = 0.5f
}

/**
 * 形态分类器（纯 JVM）。规则引擎：``` 围栏解析（支持 3+ 反引号与嵌套围栏）、
 * `<html|<!DOCTYPE` 探测、语言标注识别（大小写不敏感）、代码指纹
 * （`function\s*\(|const ` 等 vs `@media|{...:...;}`）、
 * `sk-/Bearer/apiProfiles/curl` 关键词路由。
 */
class PasteClassifier {

    fun classify(raw: String): Classified {
        val trimmed = raw.trim()

        // 空输入：确定性 unknown
        if (trimmed.isEmpty()) return Classified(PasteForm.Unknown(raw), 1.0f)

        // 1) 接口配置路由（cURL / Apilot JSON / 鉴权头），优先于代码探测
        if (looksLikeApiConfig(trimmed)) return Classified(PasteForm.ApiConfig(raw), 0.9f)

        // 2) 无围栏整段探测（先剥行号污染再嗅探）
        val sniff = stripLineNumberPrefixes(trimmed)
        if (sniff.trimStart().startsWith("<!DOCTYPE", ignoreCase = true)) {
            return Classified(PasteForm.CompleteHtml(raw), 0.95f)
        }
        if (HTML_TAG.containsMatchIn(sniff)) return Classified(PasteForm.CompleteHtml(raw), 0.85f)

        // 3) 围栏解析 → 混排 / 多块 / 单块
        val doc = FenceParser.parse(raw)
        return if (doc.blocks.isEmpty()) {
            classifyFreeForm(sniff, raw)
        } else {
            classifyFenced(doc, raw)
        }
    }

    // ---- internals ----

    private fun classifyFenced(doc: ParsedDoc, raw: String): Classified {
        if (doc.prose.isNotBlank()) return Classified(PasteForm.MixedBlocks(doc.blocks, doc.prose), 0.9f)

        if (doc.blocks.size == 1) {
            val b = doc.blocks[0]
            val code = stripLineNumberPrefixes(b.code).trim()
            if (code.startsWith("<!DOCTYPE", ignoreCase = true)) {
                return Classified(PasteForm.CompleteHtml(raw), 0.95f)
            }
            if (HTML_TAG.containsMatchIn(code)) return Classified(PasteForm.CompleteHtml(raw), 0.85f)
            return when {
                b.lang == LANG_HTML -> Classified(PasteForm.Fragments(code, emptyList(), emptyList()), 0.85f)
                b.lang in LANG_JS -> Classified(PasteForm.JsOnly(code), 0.9f)
                b.lang in LANG_CSS -> Classified(PasteForm.CssOnly(code), 0.9f)
                jsScore(code) >= JS_STRONG_ENOUGH && !looksCss(code) -> Classified(PasteForm.JsOnly(code), 0.8f)
                looksCss(code) && jsScore(code) < JS_STRONG_ENOUGH -> Classified(PasteForm.CssOnly(code), 0.8f)
                else -> Classified(PasteForm.Unknown(raw), 0.4f)
            }
        }

        var html: String? = null
        val css = mutableListOf<String>()
        val js = mutableListOf<String>()
        for (b in doc.blocks) {
            val code = stripLineNumberPrefixes(b.code).trim()
            when {
                b.lang == LANG_HTML -> if (html == null) html = code
                b.lang in LANG_JS -> js += code
                b.lang in LANG_CSS -> css += code
                HTML_ANY_TAG.containsMatchIn(code) -> if (html == null) html = code
                looksCss(code) -> css += code
                jsScore(code) >= JS_STRONG_ENOUGH -> js += code
            }
        }
        if (html == null && css.isEmpty() && js.isEmpty()) {
            return Classified(PasteForm.Unknown(raw), 0.4f)
        }
        return Classified(PasteForm.Fragments(html, css, js), 0.9f)
    }

    private fun classifyFreeForm(sniffed: String, raw: String): Classified {
        val js = jsScore(sniffed)
        val css = looksCss(sniffed)
        val tags = HTML_ANY_TAG.containsMatchIn(sniffed)
        return when {
            tags -> Classified(PasteForm.Fragments(sniffed, emptyList(), emptyList()), 0.7f)
            js >= JS_STRONG_ENOUGH -> Classified(PasteForm.JsOnly(sniffed), 0.85f)
            css -> Classified(PasteForm.CssOnly(sniffed), 0.85f)
            js == 0 && !css -> Classified(PasteForm.Unknown(raw), 0.9f) // 纯文本，确定无代码
            else -> Classified(PasteForm.Unknown(raw), 0.3f)            // 仅弱特征 → AI 兜底
        }
    }

    private fun looksLikeApiConfig(s: String): Boolean {
        if (s.startsWith("curl ")) return true
        val jsonish = s.startsWith("{") || s.startsWith("[")
        if (jsonish && API_PROFILES_KEYS.containsMatchIn(s)) return true
        val hasAuth = BEARER.containsMatchIn(s) || SECRET_KEY.containsMatchIn(s)
        if (hasAuth && jsScore(s) < JS_STRONG_ENOUGH && !looksCss(s) && !HTML_ANY_TAG.containsMatchIn(s)) {
            return true
        }
        return false
    }

    private fun jsScore(s: String): Int =
        JS_STRONG.findAll(s).count() * 2 + JS_WEAK.findAll(s).count()

    private fun looksCss(s: String): Boolean = CSS.containsMatchIn(s)

    companion object {
        private const val JS_STRONG_ENOUGH = 2

        private val LANG_HTML = "html"
        private val LANG_JS = setOf("js", "javascript", "jsx", "ts", "typescript", "node")
        private val LANG_CSS = setOf("css", "scss", "less")

        private val HTML_TAG = Regex("<html[\\s>]", RegexOption.IGNORE_CASE)
        private val HTML_ANY_TAG = Regex(
            """</?(?:html|head|body|div|span|p|a|img|ul|ol|li|table|canvas|svg|video|script|style|h[1-6]|section|button|input)\b""",
            RegexOption.IGNORE_CASE,
        )
        private val JS_STRONG = Regex(
            """\bfunction\s*\w*\s*\(|\bconst\s|\blet\s|\bvar\s|\bdocument\.|\bwindow\.|\baddEventListener\(""",
        )
        private val JS_WEAK = Regex("=>")
        private val CSS = Regex("""@media|@import|@keyframes|[.#]?[A-Za-z][\w-]*\s*\{[^{}]*:[^{}]*;""")
        private val BEARER = Regex("""Bearer\s+[A-Za-z0-9._\-]+""")
        private val SECRET_KEY = Regex("""sk-[A-Za-z0-9_\-]{8,}""")
        private val API_PROFILES_KEYS = Regex(""""(apiProfiles|baseUrl|apiKey|providers)"\s*:""")

        private val LINE_NO = Regex("""^\s*\d{1,4}\s*[|:]\s?""")
    }
}

/**
 * 行号前缀污染剥离（`01 | code` / `12: code` 等摘录格式）。
 * 仅当八成以上非空行都带前缀时才剥离，避免误伤以数字开头的正常代码。
 * 分类器嗅探与 [Cleaner]（带报告的权威清洗）共用本函数。
 */
internal fun stripLineNumberPrefixes(code: String): String {
    val lines = code.lines()
    val nonBlank = lines.filter { it.isNotBlank() }
    if (nonBlank.isEmpty()) return code
    val lineNo = Regex("""^\s*\d{1,4}\s*[|:]\s?""")
    val hits = nonBlank.count { lineNo.containsMatchIn(it) }
    if (hits * 5 < nonBlank.size * 4) return code
    return lines.joinToString("\n") { it.replaceFirst(lineNo, "") }
}

/** 围栏解析产物：代码块 + 围栏外的说明文字 + 开栏行原文（清洗报告摘录用）。 */
internal data class ParsedDoc(val blocks: List<CodeBlock>, val prose: String, val fenceLines: List<String>)

/** ``` 围栏解析器：3+ 反引号，闭栏长度须 ≥ 开栏（CommonMark 语义），支持嵌套围栏递归展开。 */
internal object FenceParser {
    private val FENCE = Regex("""^\s{0,3}(`{3,}|~{3,})\s*([\w+#.-]*)\s*$""")

    fun parse(text: String): ParsedDoc {
        val blocks = mutableListOf<CodeBlock>()
        val prose = StringBuilder()
        val fenceLines = mutableListOf<String>()
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val m = FENCE.matchEntire(lines[i])
            if (m == null) {
                prose.appendLine(lines[i])
                i++
                continue
            }
            val mark = m.groupValues[1]
            val lang = m.groupValues[2].lowercase()
            fenceLines += lines[i].trim()
            val body = StringBuilder()
            i++
            var closed = false
            while (i < lines.size) {
                val cm = FENCE.matchEntire(lines[i])
                if (cm != null && cm.groupValues[1][0] == mark[0] &&
                    cm.groupValues[1].length >= mark.length && cm.groupValues[2].isBlank()
                ) {
                    closed = true
                    i++
                    break
                }
                body.appendLine(lines[i])
                i++
            }
            val code = body.toString().trimEnd('\n')
            // 嵌套围栏：内容本身仍是完整围栏块 → 递归展开成内层块
            val inner = parse(code)
            if (inner.prose.isBlank() && inner.blocks.isNotEmpty()) {
                blocks += inner.blocks
                fenceLines += inner.fenceLines
            } else {
                blocks += CodeBlock(lang, code)
            }
            if (!closed) break
        }
        return ParsedDoc(blocks, prose.toString().trim(), fenceLines)
    }
}
