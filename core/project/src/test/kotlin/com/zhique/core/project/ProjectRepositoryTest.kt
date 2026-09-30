package com.zhique.core.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class ProjectRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: java.io.File
    private lateinit var repo: ProjectRepository

    @org.junit.Before
    fun setup() {
        root = tmp.newFolder()
        repo = ProjectRepository(root)
    }

    @Test
    fun `创建后可列出`() {
        val a = repo.create("Demo", "<html/>")
        assertTrue(a.id.isNotBlank())
        val all = repo.list()
        assertEquals(1, all.size)
        assertEquals("Demo", all[0].name)
    }

    @Test
    fun `重命名与移动分组持久化`() {
        val a = repo.create("Old", "<html/>")
        repo.rename(a.id, "New")
        repo.moveGroup(a.id, "工作")
        val reopened = ProjectRepository(root)
        val meta = reopened.meta(a.id)
        assertEquals("New", meta.name)
        assertEquals("工作", meta.group)
    }

    @Test
    fun `复制项目生成新id并深拷文件`() {
        val a = repo.create("A", "<html><body>hi</body></html>")
        repo.writeFile(a.id, "style.css", "body{}")
        val b = repo.copy(a.id)
        assertNotEquals(a.id, b.id)
        assertEquals("A 副本", b.name)
        assertEquals("body{}", repo.readFile(b.id, "style.css"))
        assertEquals(2, repo.list().size)
    }

    @Test
    fun `zip导出导入往返保真`() {
        val a = repo.create("Z", "<html/>")
        repo.writeFile(a.id, "js/app.js", "1")
        val f = repo.exportZip(a.id)
        val imported = repo.importZip(f)
        assertNotEquals(a.id, imported.id)
        assertEquals("Z", imported.name.replaceFirst(" 副本$", ""))
        assertEquals("<html/>", repo.readFile(imported.id, "index.html"))
        assertEquals("1", repo.readFile(imported.id, "js/app.js"))
    }

    @Test
    fun `删除有history的项目必须显式confirm`() {
        val a = repo.create("H", "<html/>")
        repo.appendHistory(a.id, "snap-v1", "...")
        assertFailsWith<IllegalStateException> { repo.delete(a.id, confirm = false) }
        repo.delete(a.id, confirm = true)
        assertTrue(repo.list().isEmpty())
    }

    @Test
    fun `无history的项目可直接删除`() {
        val a = repo.create("Plain", "<html/>")
        repo.delete(a.id, confirm = false)
        assertTrue(repo.list().isEmpty())
    }
}
