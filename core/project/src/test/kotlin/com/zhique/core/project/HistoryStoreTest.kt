package com.zhique.core.project

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class HistoryStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var store: HistoryStore

    @org.junit.Before
    fun setup() {
        root = tmp.newFolder()
        store = HistoryStore(root)
    }

    private fun projectDir(id: String) = File(root, "projects/$id").apply { mkdirs() }

    @Test
    fun `快照按序编号落盘且index_json可列出`() {
        projectDir("p1")
        store.append("p1", "v1", "<html>v1</html>")
        store.append("p1", "v2", "<html>v2</html>")
        val snaps = store.list("p1")
        assertEquals(2, snaps.size)
        assertTrue(snaps[0].file.startsWith("snap-1-"))
        assertTrue(snaps[1].file.startsWith("snap-2-"))
        assertEquals("v1", snaps[0].label)
        assertEquals("v2", snaps[1].label)
        assertTrue(File(root, "projects/p1/history/index.json").isFile)
        assertEquals("<html>v1</html>", File(root, "projects/p1/history/${snaps[0].file}").readText())
    }

    @Test
    fun `restore把快照内容写回index_html`() {
        val dir = projectDir("p1")
        File(dir, "index.html").writeText("<html>orig</html>")
        store.append("p1", "v1", "<html>one</html>")
        store.append("p1", "v2", "<html>two</html>")
        val restored = store.restore("p1", store.list("p1")[0].id)
        assertEquals("<html>one</html>", restored)
        assertEquals("<html>one</html>", File(dir, "index.html").readText())
    }

    @Test
    fun `审计追加jsonl并分页读取`() {
        projectDir("p1")
        store.appendAudit("p1", AuditEntry(action = "edit_file", detail = "index.html"))
        store.appendAudit("p1", AuditEntry(action = "run", detail = "reload"))
        store.appendAudit("p1", AuditEntry(action = "edit_file", detail = "style.css"))
        val page0 = store.readAudit("p1", page = 0, pageSize = 2)
        val page1 = store.readAudit("p1", page = 1, pageSize = 2)
        assertEquals(2, page0.size)
        assertEquals("edit_file", page0[0].action)
        assertEquals("index.html", page0[0].detail)
        assertEquals("run", page0[1].action)
        assertEquals(1, page1.size)
        assertEquals("edit_file", page1[0].action)
        assertEquals("style.css", page1[0].detail)
        assertEquals(0, store.readAudit("p1", page = 2, pageSize = 2).size)
    }

    @Test
    fun `未知项目返回空而非抛错`() {
        assertEquals(0, store.list("nope").size)
        assertEquals(0, store.readAudit("nope").size)
    }
}
