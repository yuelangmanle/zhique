package com.zhique.core.ai

import kotlin.test.Test
import kotlin.test.assertEquals

/** Token 用量统计（Task 3.6）：每 Provider / 每项目累计。 */
class UsageMeterTest {

    private class FakeStore : UsageStore {
        val map = mutableMapOf<String, Long>()
        override suspend fun get(key: String): Long = map[key] ?: 0L
        override suspend fun add(key: String, delta: Long) {
            map[key] = (map[key] ?: 0L) + delta
        }

        override suspend fun all(): Map<String, Long> = map.toMap()
    }

    @Test
    fun `按Provider与项目累计`() = runTestCompat {
        val store = FakeStore()
        val meter = UsageMeter(store)
        meter.record(providerId = "p1", projectId = "proj-a", tokens = 100)
        meter.record(providerId = "p1", projectId = "proj-a", tokens = 50)
        meter.record(providerId = "p1", projectId = "proj-b", tokens = 30)
        meter.record(providerId = "p2", projectId = null, tokens = 7)
        assertEquals(180, meter.providerTotal("p1"))
        assertEquals(7, meter.providerTotal("p2"))
        assertEquals(150, meter.projectTotal("proj-a"))
        assertEquals(30, meter.projectTotal("proj-b"))
        assertEquals(0, meter.projectTotal("none"))
    }

    @Test
    fun `全量快照带维度前缀`() = runTestCompat {
        val meter = UsageMeter(FakeStore())
        meter.record(providerId = "p1", projectId = "proj-a", tokens = 10)
        val all = meter.all()
        assertEquals(10, all["provider:p1"])
        assertEquals(10, all["project:proj-a"])
        assertEquals(2, all.size)
    }

    private fun runTestCompat(block: suspend () -> Unit) = kotlinx.coroutines.test.runTest { block() }
}
