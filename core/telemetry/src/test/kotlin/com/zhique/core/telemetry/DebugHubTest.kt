package com.zhique.core.telemetry

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class DebugHubTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @BeforeTest
    fun reset() {
        DebugHub.resetForTest()
    }

    @AfterTest
    fun teardown() {
        DebugHub.setSinkEnabled(false)
        DebugHub.resetForTest()
    }

    @Test
    fun `事件带schema版本与单调id`() {
        DebugHub.init(appVersion = "0.2.6-test", device = "test-device")
        DebugHub.clear()
        DebugHub.event("ui", "t1", detail = mapOf("k" to "v"))
        DebugHub.event("flow", "t2")
        val list = DebugHub.recent(limit = 10)
        assertEquals(2, list.size)
        assertTrue(list[0].id < list[1].id)
        assertEquals(1, list[0].v)
        assertEquals("v", list[0].detail["k"])
        assertEquals("0.2.6-test", DebugHub.snapshot()["version"])
    }

    @Test
    fun `环形缓冲封顶丢最旧`() {
        DebugHub.init(appVersion = "t", device = "t")
        DebugHub.clear()
        repeat(DebugHub.RING_CAPACITY + 50) { DebugHub.event("ui", "e$it") }
        val list = DebugHub.recent(limit = DebugHub.RING_CAPACITY)
        assertEquals(DebugHub.RING_CAPACITY, list.size)
        // 最旧 50 条被挤出：第一条 id 对应第 50 次事件
        assertEquals("e50", list.first().action)
        assertEquals("e${DebugHub.RING_CAPACITY + 49}", list.last().action)
    }

    @Test
    fun `since增量查询`() {
        DebugHub.init(appVersion = "t", device = "t")
        DebugHub.clear()
        repeat(10) { DebugHub.event("ui", "s$it") }
        val last = DebugHub.recent(limit = 10).last()
        val next = DebugHub.recent(sinceId = last.id, limit = 10)
        assertTrue(next.isEmpty())
        DebugHub.event("ui", "after")
        val newer = DebugHub.recent(sinceId = last.id, limit = 10)
        assertEquals(listOf("after"), newer.map { it.action })
    }

    @Test
    fun `screen切换记flow且去重`() {
        DebugHub.init(appVersion = "t", device = "t")
        DebugHub.clear()
        DebugHub.screen("home")
        DebugHub.screen("home")
        DebugHub.screen("providers")
        val flows = DebugHub.recent(limit = 50).filter { it.cat == "flow" && it.action == "screen" }
        assertEquals(listOf("home", "providers"), flows.map { it.detail["to"] })
    }

    @Test
    fun `JSONL落盘并轮换`() {
        val dir = tmp.newFolder("debug")
        DebugHub.init(appVersion = "t", device = "t", sinkDir = dir, sinkEnabled = true)
        DebugHub.clear() // 只清内存：init 行已落盘
        repeat(5) { DebugHub.event("ui", "j$it") }
        DebugHub.flushSink()
        val f = File(dir, "debug-events.jsonl")
        assertTrue(f.isFile)
        val lines = f.readLines()
        assertEquals(6, lines.size) // hub.init + 5 条
        assertTrue(lines[0].contains("\"action\":\"hub.init\""))
        assertTrue(lines[1].contains("\"cat\":\"ui\""))
        assertTrue(lines[1].contains("\"action\":\"j0\""))
        // 轮换阈值：超限后旧文件变 .old，新文件从零计
        repeat(200) { DebugHub.event("ui", "big", detail = mapOf("pad" to "x".repeat(20_000))) }
        assertTrue(File(dir, "debug-events.old.jsonl").isFile)
        assertTrue(File(dir, "debug-events.jsonl").length() < DebugHub.SINK_ROTATE_BYTES)
    }

    @Test
    fun `崩溃钩子链式保留原handler`() {
        var called = false
        val original = Thread.getDefaultUncaughtExceptionHandler()
        try {
            Thread.setDefaultUncaughtExceptionHandler { _, _ -> called = true }
            DebugHub.init(appVersion = "t", device = "t")
            DebugHub.clear()
            val hooked = Thread.getDefaultUncaughtExceptionHandler()!!
            hooked.uncaughtException(Thread.currentThread(), RuntimeException("boom"))
            assertTrue(called, "原 handler 必须被链式调用")
            assertEquals("crash", DebugHub.recent(limit = 10).last().action)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }

    @Test
    fun `clear清空内存不影响文件`() {
        val dir = tmp.newFolder("debug2")
        DebugHub.init(appVersion = "t", device = "t", sinkDir = dir, sinkEnabled = true)
        DebugHub.event("ui", "keep")
        DebugHub.flushSink()
        DebugHub.clear()
        assertEquals(0, DebugHub.count())
        assertEquals(2, File(dir, "debug-events.jsonl").readLines().size) // hub.init + keep
    }
}
