package com.zhique.core.paste

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 清洗器：剥围栏 / 剥行号 / 剔说明文字，报告（动作+原文摘录），整体可回滚。 */
class CleanerTest {

    private val cleaner = Cleaner

    @Test
    fun `围栏剥离并报告动作`() {
        val r = cleaner.clean("```js\nconst a = 1;\n```")
        assertEquals("const a = 1;", r.text)
        assertTrue(r.actions.any { it.kind == Cleaner.ACTION_STRIP_FENCE }, "应报告剥离围栏")
    }

    @Test
    fun `行号前缀剥离并报告`() {
        val r = cleaner.clean(
            """
            ```html
            01 | <!DOCTYPE html>
            02 | <html>
            03 | </html>
            ```
            """.trimIndent(),
        )
        assertTrue("<!DOCTYPE html>" in r.text)
        assertTrue("01 |" !in r.text, "行号前缀应被剥除: ${r.text}")
        val act = r.actions.first { it.kind == Cleaner.ACTION_STRIP_LINE_NO }
        assertTrue("01" in act.excerpt, "摘录应含污染原文: ${act.excerpt}")
    }

    @Test
    fun `行号不误伤正常代码`() {
        val raw = "const a = 1;\nconst b = 2;"
        val r = cleaner.clean(raw)
        assertEquals(raw, r.text)
        assertTrue(r.actions.none { it.kind == Cleaner.ACTION_STRIP_LINE_NO })
    }

    @Test
    fun `混排说明文字剔除并报告`() {
        val r = cleaner.clean(
            """
            好的，代码如下：
            ```html
            <div id="app"></div>
            ```
            以上。
            """.trimIndent(),
        )
        assertTrue("好的" !in r.text && "以上" !in r.text)
        assertTrue("<div" in r.text)
        assertTrue(r.actions.any { it.kind == Cleaner.ACTION_TRIM_PROSE })
    }

    @Test
    fun `整体回滚_原始输入保留且重跑一致`() {
        val raw = "说明：\n```css\n01 | .a { color: red; }\n```"
        val r = cleaner.clean(raw)
        assertEquals(raw, r.original)
        assertEquals(r, cleaner.clean(r.original))
    }

    @Test
    fun `空串无动作`() {
        val r = cleaner.clean("")
        assertEquals("", r.text)
        assertTrue(r.actions.isEmpty())
    }

    @Test
    fun `报告摘录限长`() {
        val longProse = "这是一段特别长的说明文字".repeat(20)
        val fenced = "```js\nconst a = 1;\n```"
        val r = cleaner.clean("$longProse\n\n$fenced")
        assertTrue(r.actions.all { it.excerpt.length <= 48 }, "摘录应截断: ${r.actions.map { it.excerpt.length }}")
    }
}
