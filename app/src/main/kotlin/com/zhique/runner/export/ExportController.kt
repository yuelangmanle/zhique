package com.zhique.runner.export

import com.zhique.core.export.BackupStatus
import com.zhique.core.export.KeystoreManager
import com.zhique.core.export.ExportOutcome
import com.zhique.core.export.SignatureMismatchException
import com.zhique.core.permission.PermissionRegistry
import com.zhique.core.project.ProjectRepository
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 导出执行缝（测试注入；生产 = ExportService::export）。 */
fun interface ExportExecutor {
    suspend fun export(projectId: String, appName: String, variant: String): ExportOutcome
}

/** 导出向导状态（3 步：①应用信息 ②权限与签名 ③打包→完成）。 */
data class ExportWizardState(
    val projectId: String,
    val projectName: String = "",
    val step: Int = 0,
    val appName: String = "",
    val iconColor: String = "#46509F",
    val variant: String = "min", // min | full
    val suggestions: List<String> = emptyList(),
    val running: Boolean = false,
    val error: String? = null,
    val mismatch: Boolean = false,
    val result: ExportOutcome? = null,
    val backup: BackupStatus? = null,
) {
    val canGoStep2: Boolean get() = appName.isNotBlank()
}

/**
 * 导出向导控制器（M6 Task 6.3）：信息编辑、变体选择、备份状态、
 * 打包执行（IO 线程）与签名不匹配阻断。UI 只消费 [state]。
 */
class ExportController(
    private val projectId: String,
    private val repo: ProjectRepository,
    private val registry: PermissionRegistry?,
    private val keystore: KeystoreManager,
    private val executor: ExportExecutor,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onToast: (String) -> Unit = {},
) {

    private val _state = MutableStateFlow(ExportWizardState(projectId = projectId))

    val state: StateFlow<ExportWizardState> = _state

    /** .jks 文件（「备份到电脑」分享的源）。 */
    val keystoreFile: File get() = keystore.file

    init {
        refresh()
    }

    /** 读取项目元数据/建议清单/备份状态（IO）。 */
    fun refresh() {
        scope.launch(ioDispatcher) {
            val meta = runCatching { repo.meta(projectId) }.getOrNull()
            val backup = runCatching { keystore.backupStatus() }.getOrNull()
            _state.value = _state.value.copy(
                projectName = meta?.name ?: projectId,
                appName = _state.value.appName.ifBlank { meta?.name ?: "" },
                iconColor = meta?.iconColor ?: "#46509F",
                suggestions = registry?.suggestForExport(projectId) ?: emptyList(),
                backup = backup,
            )
        }
    }

    fun setAppName(name: String) {
        _state.value = _state.value.copy(appName = name)
    }

    fun setIconColor(color: String) {
        _state.value = _state.value.copy(iconColor = color)
    }

    fun setVariant(variant: String) {
        require(variant == "min" || variant == "full")
        _state.value = _state.value.copy(variant = variant)
    }

    fun next() {
        _state.value = _state.value.copy(step = (_state.value.step + 1).coerceAtMost(2))
    }

    fun back() {
        _state.value = _state.value.copy(
            step = (_state.value.step - 1).coerceAtLeast(0),
            error = null,
            mismatch = false,
        )
    }

    /** 「备份到电脑」动作落地（分享/SAF 完成后由 UI 调）：记录 lastBackupAt。 */
    fun markBackedUp() {
        scope.launch(ioDispatcher) {
            runCatching { keystore.markBackedUp() }
                .onSuccess { _state.value = _state.value.copy(backup = keystore.backupStatus()) }
                .onFailure { onToast("备份状态记录失败：${it.message}") }
        }
    }

    /** 打包执行：前置校验（签名不一致 → 阻断页）→ 管线 → 结果。 */
    fun run() {
        val s = _state.value
        if (s.running || s.result != null) return
        _state.value = s.copy(running = true, error = null, mismatch = false)
        scope.launch(ioDispatcher) {
            try {
                val outcome = executor.export(projectId = s.projectId, appName = s.appName, variant = s.variant)
                _state.value = _state.value.copy(running = false, result = outcome, backup = keystore.backupStatus())
            } catch (e: SignatureMismatchException) {
                _state.value = _state.value.copy(running = false, mismatch = true, error = e.message)
            } catch (t: Throwable) {
                _state.value = _state.value.copy(running = false, error = t.message ?: "导出失败")
            }
        }
    }
}
