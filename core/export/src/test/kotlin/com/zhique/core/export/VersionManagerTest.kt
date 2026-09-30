package com.zhique.core.export

import com.zhique.core.project.ProjectRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.rules.TemporaryFolder

/** 版本与包名决策（M6 Task 6.2）：自增、slug、跨项目冲突、同项目稳定。 */
class VersionManagerTest {

    private val tmp = TemporaryFolder()

    private fun manager(): Pair<ProjectRepository, VersionManager> {
        tmp.create()
        val repo = ProjectRepository(tmp.root)
        return repo to VersionManager(repo)
    }

    @Test
    fun `首导出版本号从1开始`() {
        val (repo, version) = manager()
        val meta = repo.create("记事本", "<p>x</p>")
        assertEquals(1, version.nextVersionCode(meta))
        assertEquals("1.0.1", version.versionName(1))
    }

    @Test
    fun `导出记录存在时版本号自增`() {
        val (repo, version) = manager()
        val meta = repo.create("记事本", "<p>x</p>")
        repo.recordExport(
            meta.id,
            com.zhique.core.project.ExportRecord("com.zhique.export.a", 3, "1.0.3", 0, "min"),
        )
        assertEquals(4, version.nextVersionCode(repo.meta(meta.id)))
    }

    @Test
    fun `slug保留小写字母数字其余折叠为横线`() {
        val (_, version) = manager()
        assertEquals("my-note-2", version.slug("My Note 2!"))
        assertEquals("a-b", version.slug("  a...b  "))
        assertEquals("abc", version.slug("ABC"))
    }

    @Test
    fun `纯中文与空名回退app`() {
        val (_, version) = manager()
        assertEquals("app", version.slug("织雀记事本"))
        assertEquals("app", version.slug("   "))
        assertEquals("app", version.slug("###"))
    }

    @Test
    fun `slug超长截断`() {
        val (_, version) = manager()
        assertTrue(version.slug("a".repeat(100)).length <= VersionManager.MAX_SLUG_LEN)
    }

    @Test
    fun `不同项目同名时包名互不冲突`() {
        val (repo, version) = manager()
        val a = repo.create("记事本", "<p></p>")
        val b = repo.create("记事本", "<p></p>")
        val pkgA = version.packageName(repo.meta(a.id))
        val pkgB = version.packageName(repo.meta(b.id))
        assertNotEquals(pkgA, pkgB)
        assertTrue(pkgA.startsWith(VersionManager.PREFIX))
        assertTrue(pkgB.startsWith(VersionManager.PREFIX))
    }

    @Test
    fun `同一项目跨导出包名稳定`() {
        val (repo, version) = manager()
        val a = repo.create("笔记", "<p></p>")
        val first = version.packageName(repo.meta(a.id))
        repo.recordExport(a.id, com.zhique.core.project.ExportRecord(first, 1, "1.0.1", 0, "min"))
        assertEquals(first, version.packageName(repo.meta(a.id)))
    }

    @Test
    fun `既有导出包名被占用时新项目避让`() {
        val (repo, version) = manager()
        val a = repo.create("记事本", "<p></p>")
        val pkgA = version.packageName(repo.meta(a.id))
        repo.recordExport(a.id, com.zhique.core.project.ExportRecord(pkgA, 1, "1.0.1", 0, "min"))
        val b = repo.create("记事本", "<p></p>")
        val pkgB = version.packageName(repo.meta(b.id))
        assertNotEquals(pkgA, pkgB)
    }
}
