package com.zhique.core.export

import com.zhique.core.project.ProjectRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.rules.TemporaryFolder

/** 版本与包名决策（M6 Task 6.2）：自增、slug、跨项目冲突、同项目稳定、**包名合法性**。 */
class VersionManagerTest {

    private val tmp = TemporaryFolder()

    private fun manager(): Pair<ProjectRepository, VersionManager> {
        tmp.create()
        val repo = ProjectRepository(tmp.root)
        return repo to VersionManager(repo)
    }

    /** Android 包名合法性：每段 [a-zA-Z][a-zA-Z0-9_]*（真机修复：'-' 导致解析包失败）。 */
    private fun assertValidPackage(pkg: String) {
        assertTrue(pkg.startsWith(VersionManager.PREFIX), pkg)
        val seg = pkg.removePrefix(VersionManager.PREFIX)
        assertTrue(seg.isNotBlank(), pkg)
        assertTrue(seg[0] in 'a'..'z', "段首必须小写字母: $pkg")
        assertTrue(seg.all { it in 'a'..'z' || it in '0'..'9' || it == '_' }, "非法字符: $pkg")
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
    fun `slug保留小写字母数字其余折为下划线`() {
        val (_, version) = manager()
        assertEquals("my_note_2", version.slug("My Note 2!"))
        assertEquals("a_b", version.slug("  a...b  "))
        assertEquals("abc", version.slug("ABC"))
    }

    @Test
    fun `数字开头与纯中文回退合法段`() {
        val (_, version) = manager()
        assertEquals("p3d", version.slug("3d"))           // 段首补字母
        assertEquals("app", version.slug("织雀记事本"))     // CJK 全折叠 → 回退
        assertEquals("app", version.slug("   "))
        assertEquals("app", version.slug("###"))
    }

    @Test
    fun `slug超长截断`() {
        val (_, version) = manager()
        assertTrue(version.slug("a".repeat(100)).length <= VersionManager.MAX_SLUG_LEN)
    }

    @Test
    fun `不同项目同名时包名互不冲突且全部合法`() {
        val (repo, version) = manager()
        val a = repo.create("记事本", "<p></p>")
        val b = repo.create("记事本", "<p></p>")
        val pkgA = version.packageName(repo.meta(a.id))
        val pkgB = version.packageName(repo.meta(b.id))
        assertNotEquals(pkgA, pkgB)
        assertValidPackage(pkgA)
        assertValidPackage(pkgB) // 真机 bug 回归：冲突消解曾拼 '-' → 解析包失败
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
    fun `重命名不改既有包名`() {
        val (repo, version) = manager()
        val a = repo.create("旧名字", "<p></p>")
        val first = version.packageName(repo.meta(a.id))
        repo.recordExport(a.id, com.zhique.core.project.ExportRecord(first, 1, "1.0.1", 0, "min"))
        repo.rename(a.id, "全新名字")
        assertEquals(first, version.packageName(repo.meta(a.id)), "覆盖安装锚点：重命名不改包名")
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
        assertValidPackage(pkgB)
    }

    @Test
    fun `多个中文项目依次导出全部拿到合法不冲突包名`() {
        val (repo, version) = manager()
        val pkgs = (1..4).map { i ->
            val meta = repo.create("星空示例", "<p></p>")
            val pkg = version.packageName(meta)
            repo.recordExport(meta.id, com.zhique.core.project.ExportRecord(pkg, 1, "1.0.1", 0, "min"))
            pkg
        }
        pkgs.forEach { assertValidPackage(it) }
        assertEquals(pkgs.size, pkgs.toSet().size, "包名必须互不相同（可共存）")
    }

    @Test
    fun `历史非法包名被弃用重生成`() {
        val (repo, version) = manager()
        val a = repo.create("星空示例", "<p></p>")
        // 模拟旧版 bug 产物：带连字符的非法包名
        repo.recordExport(a.id, com.zhique.core.project.ExportRecord("com.zhique.export.app-9974365e", 1, "1.0.1", 0, "min"))
        val pkg = version.packageName(repo.meta(a.id))
        assertValidPackage(pkg)
        assertTrue(pkg != "com.zhique.export.app-9974365e")
    }
}
