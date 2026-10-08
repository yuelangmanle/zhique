package com.zhique.runner.home

import com.zhique.core.paste.PasteClassifier
import com.zhique.core.paste.PasteForm
import com.zhique.core.project.ProjectMeta
import com.zhique.core.project.ProjectRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 首页控制器：项目列表的磁盘 IO 全部经 [io] 调度器执行（绝不占主线程），
 * UI 态以 [projects] StateFlow 暴露。
 * 剪贴板检测：ON_RESUME 读一次 + 显式刷新，检测到内容且含代码特征才出卡（规格 §2.1，不做后台监听）。
 */
class HomeController(
    private val repo: ProjectRepository,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val onToast: (String) -> Unit = {},
    private val onRun: (ProjectMeta) -> Unit = {},
    private val sampleHtml: (suspend () -> String?)? = null,
    private val clipboardText: (() -> String?)? = null,
    // zip 导出到系统分享（文件落在 app 私有 cache，用户必须经分享/文件选择器才能拿到）
    private val onShareZip: (java.io.File) -> Unit = {},
) {

    private val _projects = MutableStateFlow<List<ProjectMeta>>(emptyList())

    /** 项目列表 UI 态（按 createdAt 排序，来自仓库）。 */
    val projects: StateFlow<List<ProjectMeta>> = _projects.asStateFlow()

    // 搜索（重度用户：项目多了找不到）——query 与项目列表组合出过滤视图
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** 搜索过滤视图：名称不区分大小写包含；空 query 返回原列表。
     *  stateIn 热流（初始值=当前列表）：冷流 + initial=emptyList 会让首帧闪空态。 */
    val visibleProjects: kotlinx.coroutines.flow.StateFlow<List<ProjectMeta>> =
        kotlinx.coroutines.flow.combine(_projects, _query) { list, q ->
            if (q.isBlank()) list
            else list.filter { it.name.contains(q.trim(), ignoreCase = true) }
        }.stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, _projects.value)

    fun setQuery(q: String) {
        _query.value = q
    }

    private val _historyIds = MutableStateFlow<Set<String>>(emptySet())

    /** 含历史快照的项目 id（删除确认文案用）。 */
    val historyIds: StateFlow<Set<String>> = _historyIds.asStateFlow()

    private val _clipboardCandidate = MutableStateFlow<String?>(null)

    /** 剪贴板中检测到的可粘贴代码（null = 不显示卡）。 */
    val clipboardCandidate: StateFlow<String?> = _clipboardCandidate.asStateFlow()

    private var dismissedClipHash: Int = 0

    init {
        refresh()
    }

    fun refresh() {
        scope.launch(io) { snapshot() }
    }

    /** 回前台 ON_RESUME / 显式刷新按钮共用：读一次剪贴板，含代码特征才出卡。 */
    fun checkClipboard() {
        val read = clipboardText ?: return
        scope.launch(io) {
            val text = read() ?: return@launch
            if (text.isBlank()) return@launch
            val hash = text.hashCode()
            if (hash == dismissedClipHash || hash == _clipboardCandidate.value?.hashCode()) return@launch
            val classified = PasteClassifier().classify(text)
            if (classified.form is PasteForm.Unknown) return@launch
            _clipboardCandidate.value = text
        }
    }

    /** 「忽略」：同内容不再打扰，直到剪贴板变化。 */
    fun dismissClipboard() {
        dismissedClipHash = _clipboardCandidate.value?.hashCode() ?: 0
        _clipboardCandidate.value = null
    }

    /** 「粘贴预览」取走候选内容并收卡。 */
    fun consumeClipboard(): String? {
        val text = _clipboardCandidate.value
        _clipboardCandidate.value = null
        return text
    }

    private suspend fun snapshot() {
        val list = repo.list()
        _projects.value = list
        _historyIds.value =
            list.filter { repo.hasHistory(it.id) }.map { it.id }.toSet()
    }

    fun createEmpty() {
        scope.launch(io) {
            val meta = repo.create(DEFAULT_NAME, EMPTY_HTML)
            snapshot()
            // PM 视角修复：点「+」期待直接看到新项目（与 createSample 行为一致），
            // 此前只入库不打开，用户以为"新建没反应"
            onRun(meta)
        }
    }

    fun createSample() {
        scope.launch(io) {
            val html = sampleHtml?.invoke()
            if (html == null) {
                onToast("示例资源缺失")
                return@launch
            }
            // 允许重复创建：同名已存在时自动加序号（示例库每个都能玩）
            val name = uniqueName(SAMPLE_NAME)
            val meta = repo.create(name, html)
            snapshot()
            onRun(meta)
        }
    }

    /** 示例库通用创建（任一 assets/samples/<asset>）：同名加序号防覆盖。 */
    fun createFromSample(displayName: String, html: String?) {
        scope.launch(io) {
            if (html.isNullOrBlank()) {
                onToast("示例资源缺失")
                return@launch
            }
            val meta = repo.create(uniqueName(displayName), html)
            snapshot()
            onRun(meta)
        }
    }

    private fun uniqueName(base: String): String {
        val existing = runCatching { repo.list().map { it.name }.toSet() }.getOrDefault(emptySet())
        if (base !in existing) return base
        var i = 2
        while ("$base $i" in existing) i++
        return "$base $i"
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
            runCatching { repo.exportZip(id) }
                .onSuccess { f ->
                    // toast 只报结果；文件经系统分享交付（私有 cache 路径用户无法访问）。
                    // 分享失败（如 FileProvider root 未覆盖）不能带崩协程
                    runCatching { onShareZip(f) }
                        .onFailure { onToast("分享失败：${it.message}") }
                        .onSuccess { onToast("已打包，选择保存位置或分享") }
                }
                .onFailure { onToast("导出失败：${it.message}") }
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
        // 空白项目欢迎页：全白 body 会触发白屏检测 + 「已降级」误报面（TV 走查），
        // 给一个自解释的起点页——样式内联，用户替换全文即可
        val EMPTY_HTML = """
            <!DOCTYPE html>
            <html lang="zh">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>新项目</title>
            <style>
              html, body { margin: 0; height: 100%; }
              body { display: flex; align-items: center; justify-content: center;
                     background: #14151d; color: #e8e9f2; font-family: system-ui, sans-serif; }
              .card { text-align: center; padding: 32px; }
              h1 { font-size: 22px; margin: 0 0 10px; }
              p { margin: 4px 0; color: #9aa0b5; font-size: 14px; }
            </style>
            </head>
            <body>
              <div class="card">
                <h1>空白项目就绪</h1>
                <p>用「编辑器」写代码，或把 HTML 粘贴进来替换本页</p>
                <p>也可以在抽屉里把页面交给 AI 对话来改</p>
              </div>
            </body>
            </html>
        """.trimIndent()
    }
}
