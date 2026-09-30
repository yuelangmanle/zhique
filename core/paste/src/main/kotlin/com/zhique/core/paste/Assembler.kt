package com.zhique.core.paste

/** 组装产物：可直接写入 index.html 的文档 + 提取/推导出的项目名（可空，UI 兜底命名）。 */
data class Assembled(val html: String, val title: String?)

/**
 * 组装引擎（规格 §4.1.2）：片段/混排/纯 JS/CSS → 统一 `<!DOCTYPE html>` 标准骨架
 * （css 入 `<style>` 于 head、js 入 `<script defer>` 于 body 尾、title 从 `<title>` 或首行注释取）。
 * 完整文档透传不二次包裹；未知/接口配置转义为 `<pre>` 展示（可运行、不执行）。
 */
object Assembler {

    const val DEFAULT_TITLE = "粘贴的项目"
    private const val TITLE_MAX = 30

    fun assemble(form: PasteForm, cleaned: CleanResult): Assembled = when (form) {
        is PasteForm.CompleteHtml -> Assembled(cleaned.text, extractTitle(cleaned.text) ?: DEFAULT_TITLE)
        is PasteForm.JsOnly -> skeleton(commentTitle(cleaned.text), emptyList(), listOf(cleaned.text), "")
        is PasteForm.CssOnly -> skeleton(commentTitle(cleaned.text), listOf(cleaned.text), emptyList(), "")
        is PasteForm.MixedBlocks, is PasteForm.Fragments -> {
            var body = ""
            val css = mutableListOf<String>()
            val js = mutableListOf<String>()
            for (b in cleaned.blocks) {
                when {
                    b.lang == PasteClassifier.LANG_HTML -> if (body.isBlank()) body = b.code
                    b.lang in PasteClassifier.LANG_JS -> js += b.code
                    b.lang in PasteClassifier.LANG_CSS -> css += b.code
                    PasteClassifier.HTML_ANY_TAG.containsMatchIn(b.code) -> if (body.isBlank()) body = b.code
                    looksCssLike(b.code) -> css += b.code
                    PasteClassifier.JS_STRONG.containsMatchIn(b.code) -> js += b.code
                }
            }
            skeleton(
                title = extractTitle(cleaned.text) ?: commentTitle(cleaned.text),
                css = css,
                js = js,
                body = body,
            )
        }
        // ApiConfig → Provider 转存流程在 M3 接线；此处先以转义 pre 呈现，可运行不执行
        is PasteForm.ApiConfig, is PasteForm.Unknown ->
            skeleton(null, emptyList(), emptyList(), "<pre>${escapeHtml(cleaned.text)}</pre>")
    }

    /** `<title>` 提取（优先），失败返回 null。 */
    fun extractTitle(html: String): String? {
        val m = Regex("<title[^>]*>(.*?)</title>", RegexOption.DOT_MATCHES_ALL).find(html) ?: return null
        return m.groupValues[1].trim().takeIf { it.isNotBlank() }?.take(TITLE_MAX)
    }

    /** 首行注释取名：`<!-- 名 -->` / `// 名` / `/* 名 */`。 */
    fun commentTitle(code: String): String? {
        val first = code.lines().firstOrNull { it.isNotBlank() }?.trim() ?: return null
        val htmlComment = Regex("""^<!--\s*(.+?)\s*-->$""").find(first)
        val slashComment = Regex("""^(?://+|/\*+|\*+)\s*(.+?)(?:\s*\*/)?$""").find(first)
        val name = (htmlComment?.groupValues?.get(1) ?: slashComment?.groupValues?.get(1))
            ?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return name.take(TITLE_MAX)
    }

    private fun skeleton(title: String?, css: List<String>, js: List<String>, body: String): Assembled {
        val t = title?.takeIf { it.isNotBlank() } ?: DEFAULT_TITLE
        val sb = StringBuilder()
        sb.append("<!DOCTYPE html>\n")
        sb.append("<html lang=\"zh-CN\">\n<head>\n")
        sb.append("<meta charset=\"utf-8\">\n")
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
        // title 可能取自粘贴原文（<title>/首行注释），拼入骨架前必须转义，防 `</title><script>` 注入
        sb.append("<title>").append(escapeHtml(t)).append("</title>\n")
        if (css.isNotEmpty()) sb.append("<style>\n").append(css.joinToString("\n")).append("\n</style>\n")
        sb.append("</head>\n<body>\n")
        if (body.isNotBlank()) sb.append(body).append('\n')
        if (js.isNotEmpty()) sb.append("<script defer>\n").append(js.joinToString("\n")).append("\n</script>\n")
        sb.append("</body>\n</html>")
        return Assembled(sb.toString(), t)
    }

    private fun escapeHtml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
