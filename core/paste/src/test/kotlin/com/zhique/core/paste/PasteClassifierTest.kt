package com.zhique.core.paste

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 形态分类器全量语料（计划 Task 2.1 Step 2：≥20 例）。
 * 置信度语义：>=0.8 规则明确命中；0.5–0.8 部分特征；< [PasteConfidence.AI_FALLBACK_THRESHOLD]
 * 触发 AI 兜底（规格 §4.1.3）。
 */
class PasteClassifierTest {

    private val c = PasteClassifier()

    // ---- 计划语料 ①：完整 <!DOCTYPE html> ----

    @Test
    fun `语料1_完整doctype文档`() {
        val r = c.classify("<!DOCTYPE html>\n<html><body><h1>hi</h1></body></html>")
        assertIs<PasteForm.CompleteHtml>(r.form)
        assertTrue(r.confidence >= 0.8f, "置信度应明确命中: ${r.confidence}")
    }

    // ---- 计划语料 ②：说明文字 + 围栏代码块（混排） ----

    @Test
    fun `语料2_说明文字混排代码块`() {
        val r = c.classify(
            """
            好的，代码如下：
            ```html
            <div id="app">hi</div>
            ```
            以上。
            """.trimIndent(),
        )
        val mixed = assertIs<PasteForm.MixedBlocks>(r.form)
        assertEquals(1, mixed.blocks.size)
        assertTrue("好的" in mixed.prose, "说明文字应保留在 prose")
        assertTrue(r.confidence >= 0.8f)
    }

    // ---- 计划语料 ③：三块分别标 html/css/javascript ----

    @Test
    fun `语料3_三语言代码块归为片段`() {
        val r = c.classify(
            """
            ```html
            <div id="app"></div>
            ```
            ```css
            body { margin: 0; }
            ```
            ```javascript
            const app = document.getElementById("app");
            ```
            """.trimIndent(),
        )
        val f = assertIs<PasteForm.Fragments>(r.form)
        assertTrue(f.html != null, "html 片段应就位")
        assertEquals(1, f.css.size)
        assertEquals(1, f.js.size)
        assertTrue(r.confidence >= 0.8f)
    }

    // ---- 计划语料 ④：仅 JS ----

    @Test
    fun `语料4_纯js片段`() {
        val r = c.classify("function () { console.log(1); }")
        assertIs<PasteForm.JsOnly>(r.form)
        assertTrue(r.confidence >= 0.8f)
    }

    // ---- 计划语料 ⑤：仅 CSS ----

    @Test
    fun `语料5_纯css片段`() {
        val r = c.classify("body { color: red; }")
        assertIs<PasteForm.CssOnly>(r.form)
        assertTrue(r.confidence >= 0.8f)
    }

    // ---- 计划语料 ⑥：markdown 带行号前缀 ----

    @Test
    fun `语料6_行号前缀污染的完整html`() {
        val r = c.classify(
            """
            ```html
            01 | <!DOCTYPE html>
            02 | <html>
            03 | <body>hi</body>
            04 | </html>
            ```
            """.trimIndent(),
        )
        assertIs<PasteForm.CompleteHtml>(r.form)
        assertTrue(r.confidence >= 0.8f)
    }

    // ---- 计划语料 ⑦：cURL 带 Authorization ----

    @Test
    fun `语料7_curl带bearer鉴权`() {
        val r = c.classify(
            """curl -X POST https://api.example.com/v1/chat -H "Authorization: Bearer sk-abc12345678" """.trim(),
        )
        assertIs<PasteForm.ApiConfig>(r.form)
        assertTrue(r.confidence >= 0.8f)
    }

    // ---- 计划语料 ⑧：Apilot 导出 JSON 含 apiProfiles ----

    @Test
    fun `语料8_apilot导出json`() {
        val r = c.classify(
            """{"apiProfiles":[{"name":"openai","baseUrl":"https://api.openai.com/v1","apiKey":"sk-x"}]}""",
        )
        assertIs<PasteForm.ApiConfig>(r.form)
        assertTrue(r.confidence >= 0.8f)
    }

    // ---- 计划语料 ⑨：空串 ----

    @Test
    fun `语料9_空串判定unknown且确定`() {
        val r = c.classify("")
        assertIs<PasteForm.Unknown>(r.form)
        assertEquals(1.0f, r.confidence)
    }

    // ---- 补充语料（10–25）：边界构造 ----

    @Test
    fun `语料10_纯文本无代码为unknown不触发兜底`() {
        val r = c.classify("今天天气不错，我们去公园散步吧。")
        assertIs<PasteForm.Unknown>(r.form)
        assertTrue(r.confidence >= 0.7f, "确定不是代码，不应触发 AI 兜底: ${r.confidence}")
    }

    @Test
    fun `语料11_嵌套围栏_四反引号包三反引号html`() {
        val r = c.classify(
            """
            ````
            ```html
            <div class="box">nested</div>
            ```
            ````
            """.trimIndent(),
        )
        val f = assertIs<PasteForm.Fragments>(r.form)
        assertTrue(f.html != null && "<div" in f.html, "内层 html 块应被递归展开")
    }

    @Test
    fun `语料12_四反引号围栏内含js不提前闭合`() {
        val r = c.classify(
            """
            ````
            ```js
            const a = 1;
            ```
            ````
            """.trimIndent(),
        )
        assertIs<PasteForm.JsOnly>(r.form)
    }

    @Test
    fun `语料13_大写语言标注`() {
        val r = c.classify(
            """
            ```HTML
            <!DOCTYPE html>
            <html><body>x</body></html>
            ```
            """.trimIndent(),
        )
        assertIs<PasteForm.CompleteHtml>(r.form)
    }

    @Test
    fun `语料14_大小写混合js标注`() {
        val r = c.classify("```JavaScript\nconst x = 1;\n```")
        assertIs<PasteForm.JsOnly>(r.form)
    }

    @Test
    fun `语料15_html无doctype但有html标签`() {
        val r = c.classify("<html>\n<body><p>x</p></body>\n</html>")
        assertIs<PasteForm.CompleteHtml>(r.form)
        assertTrue(r.confidence >= 0.8f && r.confidence < 0.95f, "html 标签命中置信度低于 doctype: ${r.confidence}")
    }

    @Test
    fun `语料16_html片段无doctype无html标签`() {
        val r = c.classify("<div class=\"card\">\n  <span>hi</span>\n</div>")
        val f = assertIs<PasteForm.Fragments>(r.form)
        assertTrue(f.html != null)
        assertEquals(0, f.css.size)
        assertEquals(0, f.js.size)
        assertTrue(r.confidence >= PasteConfidence.AI_FALLBACK_THRESHOLD)
    }

    @Test
    fun `语料17_js含html字样不误判`() {
        val r = c.classify(
            "const page = \"html page\";\nfunction render() { document.title = page; }",
        )
        assertIs<PasteForm.JsOnly>(r.form)
    }

    @Test
    fun `语料18_媒体查询css`() {
        val r = c.classify("@media (max-width: 600px) {\n  .a { display: none; }\n}")
        assertIs<PasteForm.CssOnly>(r.form)
    }

    @Test
    fun `语料19_弱特征说明文字触发ai兜底`() {
        val r = c.classify("把时间 => 金钱")
        assertIs<PasteForm.Unknown>(r.form)
        assertTrue(
            r.confidence < PasteConfidence.AI_FALLBACK_THRESHOLD,
            "仅弱特征应低于兜底阈值: ${r.confidence}",
        )
    }

    @Test
    fun `语料20_无关json不算接口配置`() {
        val r = c.classify("{ \"a\": 1 }")
        assertIs<PasteForm.Unknown>(r.form)
    }

    @Test
    fun `语料21_curl多header与请求体`() {
        val r = c.classify(
            """
            curl -X POST "https://api.example.com/v1/chat" \
              -H "Content-Type: application/json" \
              -H "Authorization: Bearer sk-test12345678" \
              -H "X-Trace: 1" \
              -d '{"model":"gpt-4o"}'
            """.trimIndent(),
        )
        assertIs<PasteForm.ApiConfig>(r.form)
    }

    @Test
    fun `语料22_单围栏js无说明`() {
        val r = c.classify("```js\nfunction a() { return 1; }\n```")
        assertIs<PasteForm.JsOnly>(r.form)
    }

    @Test
    fun `语料23_单围栏css无说明`() {
        val r = c.classify("```css\n.a { color: blue; }\n```")
        assertIs<PasteForm.CssOnly>(r.form)
    }

    @Test
    fun `语料24_无围栏完整html前后带说明`() {
        val r = c.classify("效果如下：\n<!DOCTYPE html>\n<html><body>ok</body></html>")
        assertIs<PasteForm.CompleteHtml>(r.form)
    }

    @Test
    fun `语料25_纯空白串等同空`() {
        val r = c.classify("  \n\t ")
        assertIs<PasteForm.Unknown>(r.form)
        assertEquals(1.0f, r.confidence)
    }
}
