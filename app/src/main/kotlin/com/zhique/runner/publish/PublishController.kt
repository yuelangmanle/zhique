package com.zhique.runner.publish

import com.zhique.core.publish.GitHubApi
import com.zhique.core.publish.GitRepo
import com.zhique.core.publish.PatStore
import com.zhique.core.publish.PublishException
import com.zhique.core.publish.ReleaseCanceledException
import com.zhique.core.publish.ReleaseJob
import com.zhique.core.publish.ReleaseJobEngine
import com.zhique.core.publish.ReleaseStage
import com.zhique.core.publish.RepoStatus
import com.zhique.core.project.ProjectRepository
import com.zhique.core.project.RepoBinding
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 发布向导状态（规格 §4.8/§5.3 屏 12）：
 * - 日常 2 步（已绑定）：0=变更检查+AI commit message 1=确认推送→进度→完成；
 * - 首次 4 步（未绑定）：0=包信息 1=选/建仓库 2=确认 3=推送→完成；
 * - 断点续跑横幅：resumeStage 非空时先问「进行到 X，继续？」。
 */
data class PublishUiState(
    val projectId: String,
    val projectName: String = "",
    val firstTime: Boolean = true,
    val hasPat: Boolean = false,
    val step: Int = 0,
    val status: RepoStatus? = null,
    val commitMessage: String = "",
    val repoName: String = "",
    val repoPrivate: Boolean = true,
    val readme: Boolean = true,
    val license: Boolean = true,
    val wantRelease: Boolean = true,   // 同时创建 GitHub Release（附 APK），默认开（规格 F8）
    val tag: String = "v1.0.0",
    val releaseApk: File? = null,
    val binding: RepoBinding? = null,
    val resumeStage: ReleaseStage? = null,
    val resumeCanceled: Boolean = false,
    val running: Boolean = false,
    val stage: ReleaseStage? = null,
    val done: ReleaseJob? = null,
    val canceled: Boolean = false,
    val error: String? = null,
) {
    /** 首次 4 步 / 日常 2 步的总步数（进度文案用）。 */
    val totalSteps: Int get() = if (firstTime) 4 else 2

    val canGoNext: Boolean
        get() = when (step) {
            0 -> if (firstTime) projectName.isNotBlank() else commitMessage.isNotBlank()
            1 -> if (firstTime) repoName.isNotBlank() else true
            else -> true
        }
}

/**
 * 发布向导控制器（手动模式；Agent 模式经 PublishToolGateway 共用同一 ReleaseJobEngine，
 * 跨模式接管=同一状态机）。UI 只消费 [state]；状态经 `update{}` 原子改写。
 */
class PublishController(
    private val projectId: String,
    private val repo: ProjectRepository,
    private val git: GitRepo,
    private val api: GitHubApi,
    private val pats: PatStore,
    private val engine: ReleaseJobEngine,
    /** AI commit message 生成缝（null/失败→启发式兜底，规格 F11「AI 生成可改」）。 */
    private val aiCommitMessage: (suspend (String) -> String?)? = null,
    /** 最新导出 APK 解析缝（Release 附件来源；null/缺失→无附件 Release）。 */
    private val apkResolver: suspend (String) -> File? = { null },
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val onToast: (String) -> Unit = {},
) {

    private val _state = MutableStateFlow(PublishUiState(projectId = projectId))
    val state: StateFlow<PublishUiState> = _state

    /** 当前活动任务（推进循环中持有；cancel/进度展示用）。 */
    private var activeJob: ReleaseJob? = null

    init {
        refresh()
    }

    /** 读元数据/绑定/PAT/变更摘要/未完成任务（IO）。 */
    fun refresh() {
        scope.launch(io) {
            val meta = runCatching { repo.meta(projectId) }.getOrNull()
            val binding = meta?.repo
            val hasGit = binding != null && File(repo.projectDir(projectId), ".git").exists()
            val status = (if (hasGit) runCatching { git.status(repo.projectDir(projectId)) }.getOrNull() else null)
            val resumed = runCatching { engine.resume(projectId) }.getOrNull()
            val summary = status?.summary().orEmpty()
            val export = meta?.export
            val versionTag = export?.let { "v${it.versionName}" } ?: "v1.0.0"
            val apk = runCatching { apkResolver(projectId) }.getOrNull()?.takeIf { it.isFile }
            // AI 预填 commit message（可改）：失败静默回落启发式
            val aiMsg = aiCommitMessage?.let { gen ->
                runCatching { gen(summary.ifBlank { "初始化 ${meta?.name ?: projectId}" }) }.getOrNull()
            }
            _state.update { cur ->
                cur.copy(
                    projectName = meta?.name ?: projectId,
                    firstTime = binding == null,
                    hasPat = pats.hasPat(),
                    binding = binding,
                    status = status,
                    resumeStage = resumed?.stage,
                    resumeCanceled = resumed?.canceled ?: false,
                    repoName = cur.repoName.ifBlank { slug(meta?.name ?: "app") },
                    tag = versionTag,
                    releaseApk = apk,
                    commitMessage = cur.commitMessage.ifBlank {
                        aiMsg ?: "更新 ${meta?.name ?: projectId}（${status?.changes ?: 0} 处变更）"
                    },
                )
            }
        }
    }

    fun setCommitMessage(text: String) = _state.update { it.copy(commitMessage = text) }

    fun setRepoName(name: String) = _state.update { it.copy(repoName = name) }

    fun setRepoPrivate(isPrivate: Boolean) = _state.update { it.copy(repoPrivate = isPrivate) }

    fun setReadme(enabled: Boolean) = _state.update { it.copy(readme = enabled) }

    fun setLicense(enabled: Boolean) = _state.update { it.copy(license = enabled) }

    fun setWantRelease(enabled: Boolean) = _state.update { it.copy(wantRelease = enabled) }

    /** 步进（0 起日常 0→1；首次 0→1→2→3）。 */
    fun next() {
        _state.update { it.copy(step = (it.step + 1).coerceAtMost(it.totalSteps - 1)) }
    }

    fun back() {
        _state.update { it.copy(step = (it.step - 1).coerceAtLeast(0), error = null) }
    }

    /**
     * 确认推送（日常步2 / 首次步4）：首次先建仓（autoInit=false）+ README/LICENSE
     * 落项目目录 + 绑定写回，然后状态机全程推进。
     */
    fun confirmPush() {
        var accepted = false
        _state.update {
            if (it.running || it.done != null) it
            else {
                accepted = true
                it.copy(running = true, error = null, canceled = false, stage = ReleaseStage.PLANNED)
            }
        }
        if (!accepted) return
        val snapshot = _state.value
        scope.launch(io) {
            try {
                val binding = ensureBinding(snapshot)
                val remoteUrl = "https://github.com/${binding.owner}/${binding.repo}.git"
                // 断点任务接管（非取消的）；已取消/终态一律重新 plan（同项目新一次发布）
                val release = snapshot.wantRelease
                val apkPath = if (release) snapshot.releaseApk?.takeIf { it.isFile }?.absolutePath else null
                // 断点任务磁盘证据只存文件名：解析到真身则回注绝对路径（资产补传可达）
                val job = engine.resume(projectId)?.takeIf { !it.canceled }
                    ?.let { resumed -> apkPath?.let { resumed.copy(releaseAsset = it) } ?: resumed }
                    ?: engine.plan(
                        projectId = projectId,
                        remoteUrl = remoteUrl,
                        branch = binding.branch,
                        message = snapshot.commitMessage,
                        tag = if (release) snapshot.tag else null,
                        wantRelease = release,
                        releaseAsset = apkPath,
                    )
                activeJob = job
                var cur = job
                while (!cur.terminal) {
                    cur = engine.advance(cur)
                    activeJob = cur
                    _state.update { it.copy(stage = cur.stage) }
                }
                finishWith(cur, binding)
            } catch (e: ReleaseCanceledException) {
                _state.update { it.copy(running = false, canceled = true) }
            } catch (e: PublishException) {
                _state.update { it.copy(running = false, error = e.message) }
            } catch (t: Throwable) {
                _state.update { it.copy(running = false, error = t.message ?: "发布失败") }
            }
        }
    }

    /** 用户取消：状态机打 canceled 标记（≠失败，不自动重试）；推进循环收到异常收束。 */
    fun cancel() {
        val job = activeJob
        if (job != null && !job.terminal) {
            scope.launch(io) {
                runCatching { engine.cancel(job) }
                _state.update { it.copy(running = false, canceled = true) }
            }
        } else {
            _state.update { it.copy(canceled = true) }
        }
    }

    /** 横幅「本次不继续」：只收横幅，不动磁盘任务（下次进入仍会提示）。 */
    fun dismissResume() = _state.update { it.copy(resumeStage = null, resumeCanceled = false) }

    /** 断点续跑（横幅「继续」按钮）：resume 非终态任务并推进到终态。 */
    fun resumeLast() {
        if (_state.value.running) return
        _state.update { it.copy(running = true, error = null, canceled = false, resumeStage = null) }
        scope.launch(io) {
            try {
                val job = engine.resume(projectId) ?: return@launch
                activeJob = job
                var cur = job
                while (!cur.terminal) {
                    cur = engine.advance(cur)
                    activeJob = cur
                    _state.update { it.copy(stage = cur.stage) }
                }
                finishWith(cur, repo.meta(projectId).repo)
            } catch (e: ReleaseCanceledException) {
                _state.update { it.copy(running = false, canceled = true) }
            } catch (t: Throwable) {
                _state.update { it.copy(running = false, error = t.message ?: "续跑失败") }
            }
        }
    }

    private suspend fun ensureBinding(snapshot: PublishUiState): com.zhique.core.project.RepoBinding {
        repo.meta(projectId).repo?.let { return it }
        val pat = pats.pat() ?: throw PublishException("尚未配置 GitHub PAT——先在「发布与同步」完成引导")
        val created = api.createRepo(pat, snapshot.repoName, snapshot.repoPrivate, autoInit = false)
        val (owner, repoName) = api.ownerRepo(created.fullName)
        // README/LICENSE 生成进项目目录（建仓时 autoInit=false，以本地为准避免首推冲突）
        val dir = repo.projectDir(projectId)
        if (snapshot.readme && !File(dir, "README.md").exists()) {
            File(dir, "README.md").writeText("# ${snapshot.projectName}\n\n由织雀（Zhique）创建并发布。\n")
        }
        if (snapshot.license && !File(dir, "LICENSE").exists()) {
            File(dir, "LICENSE").writeText(licenseText(snapshot.projectName))
        }
        val binding = RepoBinding(owner = owner, repo = repoName)
        repo.bindRepo(projectId, binding)
        return binding
    }

    private fun finishWith(job: ReleaseJob, binding: com.zhique.core.project.RepoBinding?) {
        binding?.let { b ->
            runCatching { repo.bindRepo(projectId, b.copy(lastPushedSha = job.commitSha)) }
        }
        _state.update { it.copy(running = false, done = job, stage = job.stage) }
    }

    companion object {
        /** GitHub 仓库名 slug：仅 ASCII 字母数字与 - _ .（CJK/空格折叠为 -）。 */
        fun slug(name: String): String {
            val cleaned = name.trim()
                .map { c -> if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "-_.") c else '-' }
                .joinToString("")
                .lowercase()
                .trim('-', '_', '.')
            return cleaned.ifBlank { "app" }.take(64)
        }

        /** Apache-2.0 简明许可头（完整文本见仓库 LICENSE，此处为生成文件的合法摘引）。 */
        fun licenseText(projectName: String): String =
            """
            |Copyright 2026 ${projectName} authors
            |
            |Licensed under the Apache License, Version 2.0 (the "License");
            |you may not use this file except in compliance with the License.
            |You may obtain a copy of the License at
            |
            |    http://www.apache.org/licenses/LICENSE-2.0
            |
            |Unless required by applicable law or agreed to in writing, software
            |distributed under the License is distributed on an "AS IS" BASIS,
            |WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
            |See the License for the specific language governing permissions and
            |limitations under the License.
            """.trimMargin()
    }
}
