package com.zhique.core.publish

import com.zhique.core.project.ProjectRepository
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 发布状态机阶段（规格 §4.8）：RELEASED 为可选终态（wantRelease=false 时直接跳到）。 */
enum class ReleaseStage { PLANNED, CHECKED, COMMITTED, PUSHED, RELEASED }

/** 用户取消发布：≠ 失败语义，不触发自动重试（对齐 RESULT_CANCELED 纪律）。 */
class ReleaseCanceledException(val job: ReleaseJob) :
    Exception("发布已取消（停在 ${job.stage}，可随时恢复）")

/**
 * 持久化发布任务（计划 Task 7.2 契约）：每步完成即落盘
 * `projects/<id>/history/release-job.json`，崩溃/杀进程后可断点续跑、跨模式接管。
 */
@Serializable
data class ReleaseJob(
    val id: String,
    val projectId: String,
    val stage: ReleaseStage = ReleaseStage.PLANNED,
    val commitSha: String? = null,
    val remoteUrl: String? = null,
    val branch: String = GitRepo.DEFAULT_BRANCH,
    val tag: String? = null,
    val message: String? = null,
    val wantRelease: Boolean = false,
    val releaseAsset: String? = null,   // Release 附带 APK 文件名（可选）
    val canceled: Boolean = false,
    val error: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    /** 终态：RELEASED（可选项被跳过时同样落 RELEASED——「跳过」也是终态证据）。 */
    val terminal: Boolean get() = stage == ReleaseStage.RELEASED
}

/**
 * 发布状态机引擎（★M7 核心）：模式（手动向导 / Agent 工具 / 导出中心一键）只是编排器，
 * 状态机唯一——[advance] 单步推进并在每步前幂等核对（远端已含 sha/tag 则跳过重做），
 * [resume] 读非终态任务续跑，跨模式接管天然成立（同一份 release-job.json）。
 *
 * 契约对齐实施计划：`ReleaseJobEngine(repo, git, api)`；pats/io/now 为可注入缺省参
 * （纯 JVM 测试传 fake 协作者与 null PAT 源）。
 */
open class ReleaseJobEngine(
    private val repo: ProjectRepository,
    private val git: GitRepo,
    private val api: GitHubApi,
    private val pats: PatStore? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** 建一个 PLANNED 任务并立即落盘（首个证据点）。 */
    fun plan(
        projectId: String,
        remoteUrl: String,
        branch: String = GitRepo.DEFAULT_BRANCH,
        message: String,
        tag: String? = null,
        wantRelease: Boolean = false,
        releaseAsset: String? = null,
    ): ReleaseJob {
        val job = ReleaseJob(
            id = UUID.randomUUID().toString(),
            projectId = projectId,
            remoteUrl = remoteUrl,
            branch = branch,
            message = message,
            tag = tag,
            wantRelease = wantRelease,
            releaseAsset = releaseAsset,
            updatedAt = now(),
        )
        persist(job, job.stage)
        return job
    }

    /**
     * 推进一步。**per-project 互斥**（质量审查 Important-1）：向导与 Agent 可并发触发
     * 同一项目——引擎内按 projectId 取 [Mutex] 全程持锁，杜绝双 commit/交错 push。
     * 每步执行前幂等核对：
     * - 锁入口对齐磁盘最新快照（并发下另一方已推进时以磁盘为准，陈旧快照不重做）；
     * - PUSHED 前：远端分支已含本 sha → 跳过 push（重复 advance 不重推）；
     * - RELEASED 前：远端已有该 tag 的 Release → 跳过创建；Release 已存在但缺本
     *   APK 资产 → 按名补传（质量审查 Important-3：上传失败重试不得丢附件）。
     */
    open suspend fun advance(job: ReleaseJob): ReleaseJob {
        if (job.canceled) throw ReleaseCanceledException(job)
        return lockFor(job.projectId).withLock {
            withContext(io) {
                // 并发对齐：另一通道可能已把任务推进得更远——以磁盘为准，不重做已完成步
                val persisted = latest(job.projectId)
                val base = if (persisted != null && persisted.id == job.id &&
                    persisted.stage.ordinal > job.stage.ordinal
                ) {
                    persisted
                } else {
                    job
                }
                if (base.canceled) throw ReleaseCanceledException(base)
                try {
                    when (base.stage) {
                        ReleaseStage.PLANNED -> {
                            // check：变更核对（git status 非空即有可发布内容；空树也放行，交由用户确认语义）
                            val dir = projectDir(base)
                            if (File(dir, ".git").exists()) git.status(dir)
                            persist(base, ReleaseStage.CHECKED)
                        }
                        ReleaseStage.CHECKED -> {
                            val message = requireNotNull(base.message) { "CHECKED 前须有 commit message" }
                            val dir = projectDir(base)
                            git.initRepo(dir)
                            val sha = git.commit(dir, message)
                            persist(base, ReleaseStage.COMMITTED, sha = sha)
                        }
                        ReleaseStage.COMMITTED -> {
                            val sha = requireNotNull(base.commitSha) { "COMMITTED 阶段须有 sha" }
                            val pat = requirePat()
                            val remote = requireNotNull(base.remoteUrl) { "COMMITTED 阶段须有 remoteUrl" }
                            // 幂等核对：远端已含 sha → 跳过重推（断点续跑/重复触发安全）
                            if (git.remoteBranchSha(remote, pat, base.branch) != sha) {
                                git.push(projectDir(base), remote, pat, base.branch)
                            }
                            persist(base, ReleaseStage.PUSHED)
                        }
                        ReleaseStage.PUSHED -> {
                            var tag = base.tag
                            if (base.wantRelease) {
                                val t = requireNotNull(tag) { "wantRelease=true 须给 tag" }
                                val pat = requirePat()
                                val (owner, repoName) = api.ownerRepo(
                                    requireNotNull(base.remoteUrl) { "RELEASE 前须有 remoteUrl" },
                                )
                                val existing = api.listReleases(pat, owner, repoName).firstOrNull {
                                    it.tagName == t
                                }
                                val asset = base.releaseAsset?.takeIf { it.isNotBlank() }?.let { File(it) }
                                if (existing == null) {
                                    val release = api.createRelease(pat, owner, repoName, t, base.message ?: t)
                                    uploadAssetIfPresent(pat, release.uploadUrl, asset)
                                } else if (asset != null && asset.isFile) {
                                    // 幂等补传：Release 已在但缺本资产（上次 uploadAsset 失败）→ 按名补传一次
                                    val missing = existing.assets.none { it.name == asset.name }
                                    if (missing) uploadAssetIfPresent(pat, existing.uploadUrl, asset)
                                }
                                tag = t
                            }
                            persist(base, ReleaseStage.RELEASED, tag = tag)
                        }
                        ReleaseStage.RELEASED -> base // 终态幂等：重复 advance 原样返回
                    }
                } catch (e: ReleaseCanceledException) {
                    throw e
                } catch (t: Throwable) {
                    // 失败：阶段不动，错误证据落盘（重试从当前阶段继续，不回退）
                    persist(base, base.stage, error = t.message)
                    throw PublishException(SecretRedactor.redact(t.message ?: "发布失败"))
                }
            }
        }
    }

    /**
     * 断点续跑：读 `history/release-job.json`，非终态即返回（含 canceled=true 的任务，
     * 调用方先呈现「已取消可恢复」语义）；终态/无任务返回 null。
     */
    fun resume(projectId: String): ReleaseJob? {
        val raw = repo.readReleaseJobJson(projectId) ?: return null
        val job = runCatching { json.decodeFromString<ReleaseJob>(raw) }.getOrNull() ?: return null
        return if (job.terminal) null else job
    }

    /** 最近一次任务（含终态，进度页展示用）。 */
    fun latest(projectId: String): ReleaseJob? {
        val raw = repo.readReleaseJobJson(projectId) ?: return null
        return runCatching { json.decodeFromString<ReleaseJob>(raw) }.getOrNull()
    }

    /**
     * 用户取消：置 canceled=true 并落盘（与 advance 共用 per-project 锁，防交错写）。
     * 此后 advance 抛 [ReleaseCanceledException] 且不执行任何 Git/API 动作——
     * 取消不是失败，不触发自动重试。
     */
    fun cancel(job: ReleaseJob): ReleaseJob =
        runBlocking { lockFor(job.projectId).withLock { persist(job.copy(canceled = true), job.stage) } }

    /** 落盘证据：stage/sha/tag/时间戳一次写入（崩溃安全：原子替换）；成功路径清空 error。
     *  releaseAsset 只落文件名（质量审查 Important-4：设备绝对路径不得进 evidence/随 history 外泄）。 */
    private fun persist(
        job: ReleaseJob,
        stage: ReleaseStage,
        sha: String? = job.commitSha,
        tag: String? = job.tag,
        error: String? = null,
    ): ReleaseJob {
        // 返回值保留调用方的内存资产路径（本次推进后续步仍可用）；
        // 落盘证据只写文件名——设备绝对路径不进 release-job.json（质量审查 Important-4）
        val updated = job.copy(
            stage = stage,
            commitSha = sha,
            tag = tag,
            error = error,
            updatedAt = now(),
        )
        val evidence = updated.copy(releaseAsset = updated.releaseAsset?.let { File(it).name })
        repo.saveReleaseJobJson(job.projectId, json.encodeToString(ReleaseJob.serializer(), evidence))
        return updated
    }

    /** uploadAsset 安全包装：uploadUrl 缺失或文件不存在则静默跳过（Release 已建，资产可后补）。 */
    private fun uploadAssetIfPresent(pat: String, uploadUrl: String?, asset: File?) {
        if (asset == null || uploadUrl == null) return
        if (asset.isFile) api.uploadAsset(pat, uploadUrl, asset)
    }

    /** per-project 互斥锁（质量审查 Important-1）：同一项目同时只有一个 advance/cancel 在跑。 */
    private fun lockFor(projectId: String): Mutex =
        locks.computeIfAbsent(projectId) { Mutex() }

    private val locks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    private fun projectDir(job: ReleaseJob): File = repo.projectDir(job.projectId)

    private fun requirePat(): String =
        pats?.pat() ?: throw PublishException("尚未配置 GitHub PAT——先在「发布与同步」完成引导")
}
