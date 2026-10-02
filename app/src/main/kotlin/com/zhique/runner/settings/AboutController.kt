package com.zhique.runner.settings

import com.zhique.core.publish.ReleaseInfo
import com.zhique.core.publish.UpdateInfo
import com.zhique.core.publish.UpdateChecker
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 关于页状态（版本/通道/更新检查/下载进度/更新日志）。 */
data class AboutUiState(
    val version: String = "",
    val channel: String = PublishPreferences.CHANNEL_STABLE,
    val checking: Boolean = false,
    val update: UpdateInfo? = null,
    val updateMessage: String? = null,
    val downloading: Boolean = false,
    val progress: Float = 0f,
    val downloadedApk: File? = null,
    val changelogs: List<ReleaseInfo> = emptyList(),
    /** 内置更新日志全文（assets/CHANGELOG.md；无 GitHub 也可查，迭代纪律的展示面）。 */
    val localChangelog: String? = null,
)

/**
 * 关于页控制器（Task 7.3）：检查更新 → 下载（进度回调）→ 交给 UI 走
 * PackageInstaller 更新流。检查/日志/下载均为注入缝（UI 测试用 fake）。
 */
class AboutController(
    val repoFullName: String = "${UpdateChecker.ZHIQUE_OWNER}/${UpdateChecker.ZHIQUE_REPO}",
    private val currentVersion: String,
    private val channelProvider: suspend () -> String,
    private val check: suspend (channel: String, currentVersion: String) -> UpdateInfo?,
    private val changelogs: suspend () -> List<ReleaseInfo>,
    private val download: suspend (UpdateInfo, File, (Float) -> Unit) -> File,
    private val downloadsDir: File,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** 内置更新日志读取（assets/CHANGELOG.md；null = 不展示本地日志）。 */
    private val localChangelog: (suspend () -> String?)? = null,
    /** 通知三事件挂点（M9）：发现新版本触发，开关过滤在容器侧。 */
    private val onNotify: (channel: String, title: String, body: String) -> Unit = { _, _, _ -> },
) {

    private val _state = MutableStateFlow(AboutUiState(version = currentVersion))
    val state: StateFlow<AboutUiState> = _state

    init {
        scope.launch(io) {
            val channel = runCatching { channelProvider() }.getOrDefault(PublishPreferences.CHANNEL_STABLE)
            val logs = runCatching { changelogs() }.getOrDefault(emptyList())
            val local = runCatching { localChangelog?.invoke() }.getOrNull()
            _state.update { it.copy(channel = channel, changelogs = logs, localChangelog = local) }
        }
    }

    /** 检查更新（GitHub Releases，按通道）。 */
    fun checkUpdate() {
        if (_state.value.checking || _state.value.downloading) return
        _state.update { it.copy(checking = true, updateMessage = null, update = null) }
        scope.launch(io) {
            val channel = runCatching { channelProvider() }.getOrDefault(PublishPreferences.CHANNEL_STABLE)
            _state.update { it.copy(channel = channel) }
            val info = try {
                check(channel, currentVersion)
            } catch (t: Throwable) {
                _state.update {
                    it.copy(checking = false, updateMessage = "检查失败：${t.message ?: "网络不可用"}")
                }
                return@launch
            }
            _state.update {
                it.copy(
                    checking = false,
                    update = info,
                    updateMessage = info?.let { u -> "发现新版本 ${u.tagName}，可下载安装" } ?: "已是最新版本",
                )
            }
            if (info != null) {
                onNotify(
                    com.zhique.runner.notify.ZhiqueNotifications.CHANNEL_NEW_VERSION,
                    "织雀新版本",
                    "发现新版本 ${info.tagName}，可到「关于织雀」下载安装",
                )
            }
        }
    }

    /** 下载更新 APK（进度入状态；完成后 UI 调 PackageInstaller 安装流）。 */
    fun downloadUpdate() {
        val update = _state.value.update ?: return
        if (_state.value.downloading) return
        _state.update { it.copy(downloading = true, progress = 0f) }
        scope.launch(io) {
            try {
                val target = withContext(io) {
                    download(update, downloadsDir, { p -> _state.update { it.copy(progress = p) } })
                }
                _state.update { it.copy(downloading = false, downloadedApk = target) }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(downloading = false, updateMessage = "下载失败：${t.message ?: "网络不可用"}")
                }
            }
        }
    }
}
