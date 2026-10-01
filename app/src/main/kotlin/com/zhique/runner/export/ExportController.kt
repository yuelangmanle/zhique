package com.zhique.runner.export

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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 导出执行缝（测试注入；生产 = ExportService::export）。 */
fun interface ExportExecutor {
    suspend fun export(
        projectId: String,
        appName: String,
        variant: String,
        iconColor: String,
    ): ExportOutcome
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
    val backup: com.zhique.core.export.BackupStatus? = null,
) {
    val canGoStep2: Boolean get() = appName.isNotBlank()
}

/**
 * 导出向导控制器（M6 Task 6.3）：信息编辑、变体选择、备份状态、
 * 打包执行（IO 线程）与签名不匹配阻断。UI 只消费 [state]。
 * 状态一律经 [MutableStateFlow.update] 原子改写（质量审查 Important-2）。
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
    /** 通知三事件挂点（M9）：导出成功触发「导出完成」，开关过滤在容器侧。 */
    private val onNotify: (channel: String, title: String, body: String) -> Unit = { _, _, _ -> },
) {

    private val _state = MutableStateFlow(ExportWizardState(projectId = projectId))

    val state: StateFlow<ExportWizardState> = _state

    /** .jks 文件本体（持久位置，非分享源——分享须走 [prepareBackup] 的白名单副本）。 */
    val keystoreFile: File get() = keystore.file

    init {
        refresh()
    }

    /** 读取项目元数据/建议清单/备份状态（IO）。 */
    fun refresh() {
        scope.launch(ioDispatcher) {
            val meta = runCatching { repo.meta(projectId) }.getOrNull()
            val backup = runCatching { keystore.backupStatus() }.getOrNull()
            _state.update { cur ->
                cur.copy(
                    projectName = meta?.name ?: projectId,
                    appName = cur.appName.ifBlank { meta?.name ?: "" },
                    iconColor = meta?.iconColor ?: "#46509F",
                    suggestions = registry?.suggestForExport(projectId) ?: emptyList(),
                    backup = backup,
                )
            }
        }
    }

    fun setAppName(name: String) {
        _state.update { it.copy(appName = name) }
    }

    fun setIconColor(color: String) {
        _state.update { it.copy(iconColor = color) }
    }

    fun setVariant(variant: String) {
        require(variant == "min" || variant == "full")
        _state.update { it.copy(variant = variant) }
    }

    fun next() {
        _state.update { it.copy(step = (it.step + 1).coerceAtMost(2)) }
    }

    fun back() {
        _state.update {
            it.copy(step = (it.step - 1).coerceAtLeast(0), error = null, mismatch = false)
        }
    }

    /**
     * 产出 .jks 备份副本到 FileProvider 白名单目录（cache/exports，
     * 质量/规格审查 Important-3：直接分享 filesDir/export/keystore 下的文件
     * 不在 zq_share_paths 白名单内必抛 FileProvider IllegalArgumentException）。
     * 返回副本文件；失败 toast 并返回 null。
     */
    fun prepareBackup(cacheDir: File): File? = runCatching {
        File(File(cacheDir, "exports").apply { mkdirs() }, "zhique-release.jks")
            .also { keystore.exportTo(it) }
    }.onFailure { onToast("备份副本产出失败：${it.message}") }.getOrNull()

    /** 「备份到电脑」动作落地（分享/SAF 完成后由 UI 调）：记录 lastBackupAt。 */
    fun markBackedUp() {
        scope.launch(ioDispatcher) {
            runCatching { keystore.markBackedUp() }
                .onSuccess {
                    _state.update { it.copy(backup = keystore.backupStatus()) }
                }
                .onFailure { onToast("备份状态记录失败：${it.message}") }
        }
    }

    /** 打包执行：前置校验（签名不一致 → 阻断页）→ 管线 → 结果。 */
    fun run() {
        // 原子占位：防并发双击双跑（质量审查 Important-2）
        var accepted = false
        _state.update {
            if (it.running || it.result != null) {
                it
            } else {
                accepted = true
                it.copy(running = true, error = null, mismatch = false)
            }
        }
        if (!accepted) return
        val snapshot = _state.value
        scope.launch(ioDispatcher) {
            try {
                val outcome = executor.export(
                    projectId = snapshot.projectId,
                    appName = snapshot.appName,
                    variant = snapshot.variant,
                    iconColor = snapshot.iconColor,
                )
                _state.update {
                    it.copy(running = false, result = outcome, backup = keystore.backupStatus())
                }
                onNotify(
                    com.zhique.runner.notify.ZhiqueNotifications.CHANNEL_EXPORT_DONE,
                    "导出完成",
                    "${outcome.record.packageName} v${outcome.record.versionName} 已打包（${outcome.record.variant}）",
                )
            } catch (e: SignatureMismatchException) {
                _state.update { it.copy(running = false, mismatch = true, error = e.message) }
            } catch (t: Throwable) {
                _state.update { it.copy(running = false, error = t.message ?: "导出失败") }
            }
        }
    }
}
