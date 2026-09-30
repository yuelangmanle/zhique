package com.zhique.core.paste

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** Task 4.6：AI 兜底解析（快循环角色「仅解析重组」prompt → JSON 结构化；失败保留原文）。 */
class AiFallbackTest {

    @Test
    fun `提示词固定要求仅解析重组与JSON输出`() {
        assertTrue("仅解析重组" in AiFallback.SYSTEM_PROMPT)
        assertTrue("JSON" in AiFallback.SYSTEM_PROMPT)
    }

    @Test
    fun `模型输出围栏JSON可解析`() {
        val out = "```json\n{\"title\":\"星空\",\"html\":\"<html><body>ok</body></html>\"}\n```"
        val parsed = AiFallback.parseModelOutput(out)
        assertEquals("星空", parsed?.title)
        assertTrue(parsed?.html!!.contains("ok"))
    }

    @Test
    fun `裸JSON可解析`() {
        val parsed = AiFallback.parseModelOutput(
            """{"title":"Demo","html":"<html><body>hi</body></html>"}""",
        )
        assertEquals("Demo", parsed?.title)
    }

    @Test
    fun `无title可解析且title为空`() {
        val parsed = AiFallback.parseModelOutput("""{"html":"<html></html>"}""")
        assertNull(parsed?.title)
        assertEquals("<html></html>", parsed?.html)
    }

    @Test
    fun `乱输出返回null`() {
        assertNull(AiFallback.parseModelOutput("这输入我猜是个页面"))
        assertNull(AiFallback.parseModelOutput("""{"title":"只有标题"}"""))
        assertNull(AiFallback.parseModelOutput(""))
    }

    @Test
    fun `parse走FastChat且携带原文`() = runTest {
        var seenUser = ""
        val parser = AiFallback.FastChat { system, user, _ ->
            seenUser = user
            """{"title":"T","html":"<html>ok</html>"}"""
        }
        val parsed = AiFallback.parse("一些奇怪的粘贴内容", parser)
        assertEquals("T", parsed?.title)
        assertTrue("一些奇怪的粘贴内容" in seenUser, "原文须进解析请求（截断前）")
        assertTrue("仅解析重组" in AiFallback.SYSTEM_PROMPT)
    }

    @Test
    fun `FastChat异常向上抛由调用方保留原文`() = runTest {
        val parser = AiFallback.FastChat { _, _, _ -> error("服务过载") }
        val e = runCatching { AiFallback.parse("raw", parser) }.exceptionOrNull()
        assertTrue(e != null, "异常透出，调用方按失败保留原文入库")
    }

    @Test
    fun `超长原文按上限截断`() {
        assertEquals(
            AiFallback.INPUT_CLIP,
            AiFallback.clipInput("x".repeat(AiFallback.INPUT_CLIP + 100)).length,
        )
    }
}
