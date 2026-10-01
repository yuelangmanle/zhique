package com.zhique.core.paste

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 组装引擎：统一 <!DOCTYPE html> 骨架（css→style / js→script defer / title 提取）。 */
class AssemblerTest {

    private val cleaner = Cleaner
    private val classifier = PasteClassifier()

    private fun pipeline(raw: String): Assembled {
        val cls = classifier.classify(raw)
        return Assembler.assemble(cls.form, cleaner.clean(raw))
    }

    @Test
    fun `fragments组装标准骨架`() {
        val a = pipeline(
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
        val html = a.html
        assertTrue(html.startsWith("<!DOCTYPE html>"), "统一骨架: $html")
        assertTrue(html.contains("<style>") && html.contains("body { margin: 0; }"), "css 应入 style")
        assertTrue(html.contains("<script defer>") && html.contains("const app"), "js 应入 script defer")
        assertTrue(html.contains("<div id=\"app\"></div>"), "html 片段应入 body")
        assertTrue(html.indexOf("<style>") < html.indexOf("<body>"), "style 应在 head 内")
        assertTrue(html.indexOf("<script defer>") > html.indexOf("<body>"), "script 应在 body 尾")
        assertTrue(html.trimEnd().endsWith("</html>"))
        assertEquals(Assembler.DEFAULT_TITLE, a.title)
    }

    @Test
    fun `jsOnly组装可运行`() {
        val a = pipeline("function () { console.log(1); }")
        assertTrue(a.html.startsWith("<!DOCTYPE html>"))
        assertTrue("<script defer>" in a.html)
        assertTrue("</html>" in a.html)
    }

    @Test
    fun `cssOnly组装进head样式`() {
        val a = pipeline("body { color: red; }")
        assertTrue(a.html.contains("<style>") && a.html.contains("body { color: red; }"))
        assertTrue(a.html.indexOf("<style>") < a.html.indexOf("<body>"))
    }

    @Test
    fun `completeHtml透传不二次包裹`() {
        val doc = "<!DOCTYPE html>\n<html><body>x</body></html>"
        val a = Assembler.assemble(PasteForm.CompleteHtml(doc), cleaner.clean(doc))
        assertEquals(doc, a.html)
        assertEquals(1, Regex("<!DOCTYPE").findAll(a.html).count())
    }

    @Test
    fun `title从title标签提取`() {
        val doc = "<!DOCTYPE html>\n<html><head><title>星空</title></head><body></body></html>"
        val a = Assembler.assemble(PasteForm.CompleteHtml(doc), cleaner.clean(doc))
        assertEquals("星空", a.title)
    }

    @Test
    fun `title从首行注释提取`() {
        val a = pipeline("// 星空动画\nconst a = 1;")
        assertEquals("星空动画", a.title)
        assertTrue("<title>星空动画</title>" in a.html)
    }

    @Test
    fun `unknown转义为pre不执行`() {
        val a = Assembler.assemble(
            PasteForm.Unknown("<script>alert(1)</script>"),
            cleaner.clean("<script>alert(1)</script>"),
        )
        assertTrue("&lt;script&gt;" in a.html, "未知输入应转义展示: ${a.html}")
        assertTrue("<script>alert" !in a.html)
    }

    @Test
    fun `多css多js保序合并`() {
        val a = pipeline(
            """
            ```html
            <div></div>
            ```
            ```css
            .one { color: red; }
            ```
            ```css
            .two { color: blue; }
            ```
            ```js
            const one = 1;
            ```
            ```js
            const two = 2;
            ```
            """.trimIndent(),
        )
        val html = a.html
        assertTrue(html.indexOf(".one") < html.indexOf(".two"), "css 保序")
        assertTrue(html.indexOf("const one") < html.indexOf("const two"), "js 保序")
    }

    @Test
    fun `title注入被转义`() {
        val a = pipeline("// </title><script>x</script>\nconst a = 1;")
        assertTrue(
            "&lt;/title&gt;&lt;script&gt;" in a.html,
            "title 拼入骨架前应转义: ${a.html}",
        )
        assertFalse("</title><script>" in a.html, "不得出现可闭合 title 的原文")
        assertTrue("<title>&lt;/title&gt;&lt;script&gt;x&lt;/script&gt;</title>" in a.html)
    }
}

/** 反向兜底钩子：组装后扫描标准权限 API 与 zq 调用，产出 CompatHint（M4/M8 接线）。 */
class CompatHintTest {

    @Test
    fun `标准权限API检出且getUserMedia不双报`() {
        val hints = CompatScanner.scan(
            """
            <script>
            navigator.mediaDevices.getUserMedia({video: true});
            navigator.geolocation.getCurrentPosition(console.log);
            new Notification("hi");
            Notification.requestPermission();
            </script>
            """.trimIndent(),
        )
        val std = hints.filter { it.kind == CompatKind.STANDARD_PERMISSION_API }.map { it.api }
        assertEquals(
            listOf("navigator.mediaDevices", "navigator.geolocation", "Notification."),
            std,
            "getUserMedia 已被 navigator.mediaDevices 覆盖，不重复上报",
        )
    }

    @Test
    fun `getUserMedia单独出现仍检出`() {
        val hints = CompatScanner.scan("navigator.getUserMedia({video: true}, cb);")
        assertTrue(hints.any { it.api == "getUserMedia" && it.kind == CompatKind.STANDARD_PERMISSION_API })
    }

    @Test
    fun `Notification子串不误报`() {
        assertTrue(
            CompatScanner.scan("const myNotification = makeNotification(); myNotification.show();").isEmpty(),
            "myNotification./makeNotification() 不应命中 Notification.",
        )
    }

    @Test
    fun `Notification纯构造调用上报`() {
        // M2 债务收敛：new Notification("hi") 无成员访问，同样命中权限桥提示且不双报
        val hints = CompatScanner.scan("new Notification(\"hi\");")
        assertEquals(
            listOf("Notification."),
            hints.filter { it.kind == CompatKind.STANDARD_PERMISSION_API }.map { it.api },
        )
        val both = CompatScanner.scan("new Notification(\"a\"); Notification.requestPermission();")
        assertEquals(
            listOf("Notification."),
            both.filter { it.kind == CompatKind.STANDARD_PERMISSION_API }.map { it.api },
            "构造调用与成员访问归并为同一条，不双报",
        )
    }

    @Test
    fun `zq调用检出并规范化`() {
        val hints = CompatScanner.scan("const r = zq.fs.read('a.txt');")
        val zq = hints.filter { it.kind == CompatKind.ZQ_CALL }
        assertEquals(1, zq.size)
        assertEquals("zq.fs.read", zq[0].api)
    }

    @Test
    fun `重复调用去重`() {
        val hints = CompatScanner.scan("zq.fs.read(1); zq.fs.read(2); navigator.mediaDevices; navigator.mediaDevices;")
        assertEquals(2, hints.size)
    }

    @Test
    fun `干净页面无提示`() {
        assertTrue(CompatScanner.scan("<html><body><h1>ok</h1></body></html>").isEmpty())
    }
}
