package com.zhique.core.web.debug

import kotlin.test.Test
import kotlin.test.assertEquals

class EventBufferTest {

    private fun console(seq: Long, text: String) =
        DebugEvent(seq = seq, t = 0, type = TimelineReducer.TYPE_CONSOLE, level = "log", text = text)

    @Test
    fun `超容量丢最旧且版本单调递增`() {
        val b = EventBuffer(capacity = 3)
        repeat(5) { b.append(console(it + 1L, "$it")) }
        assertEquals(3, b.events.size)
        assertEquals("2", b.events.first().text)
        assertEquals("4", b.events.last().text)
        assertEquals(5L, b.version)
    }

    @Test
    fun `容量内全保留版本继续递增`() {
        val b = EventBuffer(capacity = 10)
        b.append(console(1, "a"))
        b.append(console(2, "b"))
        assertEquals(2, b.events.size)
        assertEquals(2L, b.version)
    }

    @Test
    fun `清空后版本继续累加`() {
        val b = EventBuffer(capacity = 2)
        b.append(console(1, "a"))
        b.append(console(2, "b"))
        b.clear()
        assertEquals(0, b.events.size)
        assertEquals(2L, b.version)
        b.append(console(3, "c"))
        assertEquals(3L, b.version)
        assertEquals(1, b.events.size)
    }
}
