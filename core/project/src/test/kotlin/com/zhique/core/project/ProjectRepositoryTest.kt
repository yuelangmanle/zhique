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
    fun `运行器模式写回持久化`() {
        val a = repo.create("R", "<html/>")
        repo.setRunnerMode(a.id, "split")
        assertEquals("split", ProjectRepository(root).meta(a.id).runnerMode)
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

    @Test
    fun `损坏的project_json被收集而非静默吞`() {
        repo.create("Good", "<html/>")
        val badDir = java.io.File(root, "projects/bad-id").apply { mkdirs() }
        java.io.File(badDir, "project.json").writeText("{ not json")
        val all = repo.list()
        assertEquals(1, all.size)
        assertEquals("Good", all[0].name)
        assertEquals(listOf("bad-id"), repo.corruptedProjects)
    }

    @Test
    fun `importZip恶意zip不写穿沙盒`() {
        val evil = java.io.File.createTempFile("evil-", ".zip", tmp.root)
        java.util.zip.ZipOutputStream(evil.outputStream().buffered()).use { out ->
            out.putNextEntry(java.util.zip.ZipEntry("../evil.txt"))
            out.write(byteArrayOf(1))
            out.closeEntry()
        }
        assertFailsWith<IllegalArgumentException> { repo.importZip(evil) }
        assertTrue(repo.list().isEmpty())
        assertTrue(java.io.File(root, "projects").listFiles()?.isEmpty() ?: true)
    }

    @Test
    fun `history单实例由仓库暴露给消费方`() {
        val a = repo.create("A", "<html/>")
        // 消费方（M1 运行器/快照 UI）从仓库取唯一 HistoryStore 实例，而不是自行 new
        repo.history.append(a.id, "v1", "<html/>")
        assertTrue(repo.hasHistory(a.id))
        assertEquals(1, repo.history.list(a.id).size)
        repo.appendHistory(a.id, "v2", "<html/>")
        assertEquals(2, repo.history.list(a.id).size)
    }

    @Test
    fun `iconColor默认靛蓝且随meta往返持久化`() {
        val a = repo.create("A", "<html/>")
        assertEquals("#46509F", a.iconColor)
        // 旧版本 project.json（无 iconColor 字段）解析后落到默认值
        val b = repo.create("B", "<html/>")
        java.io.File(java.io.File(root, "projects/${b.id}"), "project.json").writeText(
            """{"id":"${b.id}","name":"B","createdAt":1,"updatedAt":1}""",
        )
        assertEquals("#46509F", repo.list().first { it.id == b.id }.iconColor)
        // 自定义 iconColor 随 json 读回
        java.io.File(java.io.File(root, "projects/${b.id}"), "project.json").writeText(
            """{"id":"${b.id}","name":"B","iconColor":"#AA3377","createdAt":1,"updatedAt":1}""",
        )
        assertEquals("#AA3377", repo.meta(b.id).iconColor)
        // 落盘 json 携带 iconColor 字段（encodeDefaults）
        repo.rename(a.id, "A2")
        val saved = java.io.File(java.io.File(root, "projects/${a.id}"), "project.json").readText()
        assertTrue(saved.contains("iconColor"))
    }

    @Test
    fun `json落盘不残留临时文件`() {
        val a = repo.create("T", "<html/>")
        repo.appendHistory(a.id, "v1", "x")
        repo.rename(a.id, "T2")
        val leftovers = java.io.File(root, "projects").walkTopDown()
            .filter { it.isFile && it.name.endsWith(".tmp") }
            .toList()
        assertTrue(leftovers.isEmpty())
    }

    @Test
    fun `审查I4_exportZip与copy排除git目录`() {
        val meta = repo.create("带版本库", "<p></p>")
        val gitDir = java.io.File(repo.projectDir(meta.id), ".git").apply { mkdirs() }
        java.io.File(gitDir, "HEAD").writeText("ref: refs/heads/main")
        repo.writeFile(meta.id, "index.html", "<p>x</p>")

        val zip = repo.exportZip(meta.id)
        val entries = ZipIO.read(zip).keys
        assertTrue(entries.none { it.startsWith(".git") }, "zip 不得携带版本库元数据：$entries")
        assertTrue("index.html" in entries)

        val copied = repo.copy(meta.id)
        assertTrue(!java.io.File(repo.projectDir(copied.id), ".git").exists(), "复制不带走 .git")
        assertTrue(java.io.File(repo.projectDir(copied.id), "index.html").isFile)
    }

    @Test
    fun `损坏project_json自动重建不丢项目`() {
        val a = repo.create("我的工具", "<html><head><title>工具页</title></head></html>")
        // 模拟旧版崩溃窗口期写坏 meta（真机反馈"数据消失"根因）
        java.io.File(repo.projectDir(a.id), "project.json").writeText("{ 损坏的 JSON ")

        val listed = repo.list()
        assertEquals(1, listed.size, "损坏 meta 必须自动重建而非静默消失")
        assertEquals("工具页", listed.single().name, "重建名来自 <title>")
        assertTrue(listed.single().rebuilt, "标记 rebuilt 供 UI 提示")
        // 修复已写回：再读不再标记
        assertTrue(!repo.list().single().rebuilt)
        assertTrue(repo.corruptedProjects.isEmpty())
    }

    @Test
    fun `无index_html的目录不重建计入损坏`() {
        val a = repo.create("空壳", "<p></p>")
        java.io.File(repo.projectDir(a.id), "index.html").delete()
        java.io.File(repo.projectDir(a.id), "project.json").writeText("bad")
        assertTrue(repo.list().isEmpty())
        assertEquals(listOf(a.id), repo.corruptedProjects)
    }
}