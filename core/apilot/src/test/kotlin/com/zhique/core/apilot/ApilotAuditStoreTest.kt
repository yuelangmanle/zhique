package com.zhique.core.apilot

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/** Task 8.1：桥接审计——不含 Key/payload、落库可清（规格 §4.9）。 */
class ApilotAuditStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newStore(): ApilotAuditStore = ApilotAuditStore(File(tmp.root, "apilot/audit.jsonl"))

    @Test
    fun 记录并读回保序() {
        val store = newStore()
        store.record("read", "DeepSeek Production", hasKey = true)
        store.record("write", "OpenAI 工作区", hasKey = false)
        val all = store.list()
        assertEquals(2, all.size)
        assertEquals("read" to "DeepSeek Production", all[0].direction to all[0].summary)
        assertEquals("write" to false, all[1].direction to all[1].hasKey)
        assertTrue(all[0].time in 1..System.currentTimeMillis())
    }

    @Test
    fun 审计文件不含Key字面量() {
        val file = File(tmp.root, "apilot/audit.jsonl")
        val store = ApilotAuditStore(file)
        store.record("read", "DeepSeek Production", hasKey = true)
        val raw = file.readText()
        assertTrue("hasKey" in raw)
        // 纪律：审计只存布尔，不存 Key 本体
        assertFalse("sk-" in raw)
    }

    @Test
    fun clear删除全部记录且幂等() {
        val store = newStore()
        store.record("read", "x", hasKey = false)
        store.clear()
        assertEquals(0, store.list().size)
        store.clear() // 幂等：再清一次不抛
        assertEquals(0, store.list().size)
    }

    @Test
    fun 无文件时list为空不抛() {
        assertEquals(0, newStore().list().size)
    }

    @Test
    fun 损坏行跳过不炸整体() {
        val file = File(tmp.root, "apilot/audit2.jsonl")
        file.parentFile?.mkdirs()
        file.writeText("{broken\n" +
            """{"time":123,"direction":"read","summary":"ok","hasKey":false}""" + "\n")
        val all = ApilotAuditStore(file).list()
        assertEquals(1, all.size)
        assertEquals("ok", all[0].summary)
    }

    @Test
    fun 超200条滚动裁剪最旧() {
        val store = newStore()
        repeat(205) { i -> store.record("read", "conn-$i", hasKey = false) }
        val all = store.list()
        assertEquals(ApilotAuditStore.MAX_RECORDS, all.size)
        assertEquals("conn-5", all.first().summary) // 最旧 5 条被裁
        assertEquals("conn-204", all.last().summary)
    }
}
