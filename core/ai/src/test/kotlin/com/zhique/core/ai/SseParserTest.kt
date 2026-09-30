package com.zhique.core.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SSE 帧解析器：分帧边界（`data: ` 前缀 / 多行 data / [DONE] / CRLF / 半包粘包）
 * 与忽略行语义（Task 3.1 Step 2 失败测试清单）。
 */
class SseParserTest {

    private fun parseAll(vararg chunks: String): List<SseParser.Frame> {
        val p = SseParser()
        val out = mutableListOf<SseParser.Frame>()
        for (c in chunks) out += p.feed(c)
        out += p.finish()
        return out
    }

    private fun data(payload: String) = SseParser.Frame.Data(payload)

    @Test
    fun `基础帧_带空格前缀与空行闭合`() {
        assertEquals(listOf(data("""{"a":1}""")), parseAll("""data: {"a":1}""", "\n", "\n"))
    }

    @Test
    fun `data后无空格也成帧`() {
        assertEquals(listOf(data("x")), parseAll("data:x\n\n"))
    }

    @Test
    fun `多行data按换行拼接`() {
        val frames = parseAll("""data: {"choices":[{"delta":""", "\n", """data:  {"content":"m"}}]}""", "\n", "\n")
        assertEquals(listOf(data("{\"choices\":[{\"delta\":\n {\"content\":\"m\"}}]}")), frames)
    }

    @Test
    fun `DONE哨兵单独成帧`() {
        assertEquals(listOf(SseParser.Frame.DoneSentinel), parseAll("data: [DONE]\n\n"))
    }

    @Test
    fun `CRLF行尾兼容`() {
        assertEquals(listOf(data("x")), parseAll("data: x\r\n\r\n"))
    }

    @Test
    fun `半包_一行拆两个块到达`() {
        val p = SseParser()
        assertTrue(p.feed("data: {\"a\":").isEmpty(), "半行不应出帧")
        assertEquals(listOf(data("""{"a":1}""")), p.feed("1}\n\n"))
    }

    @Test
    fun `粘包_单块多帧`() {
        val frames = parseAll("data: 1\n\ndata: 2\n\n")
        assertEquals(listOf(data("1"), data("2")), frames)
    }

    @Test
    fun `event与id与注释行忽略`() {
        val frames = parseAll("event: delta\n", "id: 42\n", ": keep-alive\n", "data: q\n", "\n")
        assertEquals(listOf(data("q")), frames)
    }

    @Test
    fun `EOF冲刷_无空行结尾与残行`() {
        assertEquals(listOf(data("tail")), parseAll("data: tai", "l"))
    }

    @Test
    fun `无data的空行不成帧`() {
        assertEquals(emptyList(), parseAll("\n", "\n"))
    }
}
