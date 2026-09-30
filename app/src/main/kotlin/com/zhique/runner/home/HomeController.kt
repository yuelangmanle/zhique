package com.zhique.runner.home

import com.zhique.core.project.ProjectMeta
import com.zhique.core.project.ProjectRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 首页控制器：项目列表的磁盘 IO 全部经 [io] 调度器执行（绝不占主线程），
 * UI 态以 [projects] StateFlow 暴露。
 */
class HomeController(
    private val repo: ProjectRepository,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val onToast: (String) -> Unit = {},
    private val onRun: (ProjectMeta) -> Unit = {},
    private val sampleHtml: (suspend () -> String?)? = null,
) {

    private val _projects = MutableStateFlow<List<ProjectMeta>>(emptyList())

    /** 项目列表 UI 态（按 createdAt 排序，来自仓库）。 */
    val projects: StateFlow<List<ProjectMeta>> = _projects.asStateFlow()

    private val _historyIds = MutableStateFlow<Set<String>>(emptySet())

    /** 含历史快照的项目 id（删除确认文案用）。 */
    val historyIds: StateFlow<Set<String>> = _historyIds.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        scope.launch(io) { snapshot() }
    }

    private suspend fun snapshot() {
        val list = repo.list()
        _projects.value = list
        _historyIds.value =
            list.filter { repo.hasHistory(it.id) }.map { it.id }.toSet()
    }

    fun createEmpty() {
        scope.launch(io) {
            repo.create(DEFAULT_NAME, EMPTY_HTML)
            snapshot()
        }
    }

    fun createSample() {
        scope.launch(io) {
            val html = sampleHtml?.invoke()
            if (html == null) {
                onToast("示例资源缺失")
                return@launch
            }
            val meta = repo.create(SAMPLE_NAME, html)
            snapshot()
            onRun(meta)
        }
    }

    fun rename(id: String, name: String) {
        if (name.isBlank()) return
        scope.launch(io) {
            repo.rename(id, name.trim())
            snapshot()
        }
    }

    fun moveGroup(id: String, group: String) {
        scope.launch(io) {
            repo.moveGroup(id, group.trim())
            snapshot()
            onToast("已移动到「${group.trim().ifBlank { "未分组" }}」")
        }
    }

    fun copy(id: String) {
        scope.launch(io) {
            val copy = repo.copy(id)
            snapshot()
            onToast("已复制为「${copy.name}」")
        }
    }

    fun exportZip(id: String) {
        scope.launch(io) {
            val f = repo.exportZip(id)
            onToast("已导出 ${f.absolutePath}")
        }
    }

    fun delete(id: String) {
        scope.launch(io) {
            runCatching { repo.delete(id, confirm = true) }
                .onFailure { onToast("删除失败：${it.message}") }
            snapshot()
        }
    }

    companion object {
        const val DEFAULT_NAME = "未命名项目"
        const val SAMPLE_NAME = "星空示例"
        val EMPTY_HTML = """
            <!DOCTYPE html>
            <html>
            <body>
              <h1>新项目</h1>
            </body>
            </html>
        """.trimIndent()
    }
}
