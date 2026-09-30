package com.zhique.runner.home

import com.zhique.core.project.ProjectRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Rule
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class HomeControllerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var repo: ProjectRepository

    private fun newController(
        onToast: (String) -> Unit = {},
        onRun: (com.zhique.core.project.ProjectMeta) -> Unit = {},
    ): HomeController {
        repo = ProjectRepository(tmp.root)
        val d = UnconfinedTestDispatcher()
        return HomeController(repo, CoroutineScope(d), io = d, onToast = onToast, onRun = onRun)
    }

    @Test
    fun `初始加载与创建空项目走IO后刷新列表`() {
        val c = newController()
        assertEquals(0, c.projects.value.size) // 初始为空，异步加载
        c.createEmpty()
        assertEquals(1, c.projects.value.size)
        assertEquals("未命名项目", c.projects.value[0].name)
    }

    @Test
    fun `重命名复制删除后列表同步`() {
        val c = newController()
        c.createEmpty()
        val id = c.projects.value[0].id
        c.rename(id, "新名")
        assertEquals("新名", c.projects.value[0].name)
        c.copy(id)
        assertEquals(2, c.projects.value.size)
        assertEquals("新名 副本", c.projects.value[1].name)
        c.delete(c.projects.value[1].id)
        assertEquals(1, c.projects.value.size)
    }

    @Test
    fun `删除失败走toast不抛`() {
        var toast: String? = null
        val c = newController(onToast = { toast = it })
        c.delete("no-such-id")
        assertTrue(toast?.contains("删除失败") == true)
        assertEquals(0, c.projects.value.size)
    }

    @Test
    fun `移动分组与导出zip`() {
        val c = newController()
        c.createEmpty()
        val id = c.projects.value[0].id
        c.moveGroup(id, "工作")
        assertEquals("工作", c.projects.value[0].group)
        var exportedPath: String? = null
        val c2 = newController(onToast = { exportedPath = it })
        c2.exportZip(id)
        assertTrue(exportedPath?.endsWith(".zip") == true)
    }

    @Test
    fun `含历史快照的项目进historyIds`() {
        val c = newController()
        c.createEmpty()
        val id = c.projects.value[0].id
        assertTrue(id !in c.historyIds.value)
        repo.appendHistory(id, "v1", "<html/>")
        c.refresh()
        assertTrue(id in c.historyIds.value)
    }
}
