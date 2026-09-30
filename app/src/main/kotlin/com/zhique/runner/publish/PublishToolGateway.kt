package com.zhique.runner.publish

import com.zhique.core.agent.tools.GitTools
import com.zhique.core.publish.GitHubApi
import com.zhique.core.publish.GitRepo
import com.zhique.core.publish.PatStore
import com.zhique.core.publish.ReleaseJobEngine
import com.zhique.core.publish.ReleaseStage
import com.zhique.core.project.ProjectRepository
import com.zhique.core.project.RepoBinding
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Agent git 工具的真实实现（M7 接线，Task 7.2）：容器构造时 `GitTools.bind`，
 * 工具仍走 requiresConfirm 批准闸。所有返回折叠为 `status/detail` JSON 供模型消费，
 * 凭据不出现在任何文本（底层 SecretRedactor 已兜底）。
 *
 * [apkResolver]：按项目解析最新导出的 APK（Release 附件来源；找不到则建无附件 Release）。
 */
class PublishToolGateway(
    private val repo: ProjectRepository,
    private val git: () -> GitRepo,
    private val api: () -> GitHubApi,
    private val pats: () -> PatStore,
    private val engine: () -> ReleaseJobEngine,
    private val apkResolver: suspend (String) -> File? = { null },
) : GitTools.Gateway {

    private val json = Json { encodeDefaults = true }

    override suspend fun createRepo(projectId: String, name: String, isPrivate: Boolean): JsonObject =
        runCatching {
            val pat = pats().pat() ?: error("尚未配置 GitHub PAT——请在「设置 → 发布与同步」完成引导")
            val created = api().createRepo(pat, name, isPrivate, autoInit = false)
            val (owner, repoName) = api().ownerRepo(created.fullName)
            repo.bindRepo(projectId, RepoBinding(owner = owner, repo = repoName))
            buildJsonObject {
                put("status", "ok")
                put("full_name", created.fullName)
                put("url", created.htmlUrl ?: "https://github.com/${created.fullName}")
                put("detail", "仓库已创建并绑定到项目")
            }
        }.getOrElse { e -> errorOf("create_repo", e) }

    override suspend fun push(
        projectId: String,
        message: String?,
        wantRelease: Boolean,
        tag: String?,
    ): JsonObject =
        runCatching {
            val meta = repo.meta(projectId)
            val binding = meta.repo
                ?: error("项目未绑定远端仓库——先 create_repo 或在发布向导中绑定")
            val remoteUrl = "https://github.com/${binding.owner}/${binding.repo}.git"
            val e = engine()
            val resolvedTag = tag ?: defaultTag(meta)
            val asset = if (wantRelease) apkResolver(projectId) else null
            val job = e.resume(projectId) ?: e.plan(
                projectId = projectId,
                remoteUrl = remoteUrl,
                branch = binding.branch,
                message = message ?: "更新 ${meta.name}",
                tag = if (wantRelease) resolvedTag else null,
                wantRelease = wantRelease,
                releaseAsset = asset?.takeIf { it.isFile }?.absolutePath,
            )
            var cur = job
            while (!cur.terminal) cur = e.advance(cur)
            repo.bindRepo(projectId, binding.copy(lastPushedSha = cur.commitSha))
            buildJsonObject {
                put("status", "ok")
                put("stage", cur.stage.name)
                put("sha", cur.commitSha ?: "")
                cur.tag?.let { put("tag", it) }
                put(
                    "detail",
                    "已推送 ${binding.owner}/${binding.repo}@${binding.branch}" +
                        (cur.tag?.let { "，Release $it 已创建" } ?: ""),
                )
            }
        }.getOrElse { err -> errorOf("push", err) }

    override suspend fun readReleases(projectId: String): JsonObject =
        runCatching {
            val binding = repo.meta(projectId).repo
                ?: error("项目未绑定远端仓库")
            val pat = pats().pat() ?: error("尚未配置 GitHub PAT")
            val releases = api().listReleases(pat, binding.owner, binding.repo)
            buildJsonObject {
                put("status", "ok")
                put("count", releases.size)
                put(
                    "releases",
                    buildJsonArray {
                        releases.take(10).forEach { r ->
                            add(
                                buildJsonObject {
                                    put("tag", r.tagName)
                                    put("name", r.name ?: r.tagName)
                                    put("prerelease", r.preRelease)
                                    put("notes", (r.body ?: "").take(200))
                                },
                            )
                        }
                    },
                )
            }
        }.getOrElse { e -> errorOf("read_releases", e) }

    /** 取消语义外露：Canceled 任务由引擎抛 ReleaseCanceledException → 明细直述，模型不重试。 */
    private fun errorOf(tool: String, e: Throwable): JsonObject = buildJsonObject {
        put("status", "error")
        put("tool", tool)
        put("detail", (e.message ?: e.javaClass.simpleName).take(300))
    }

    private fun error(message: String): Nothing = throw IllegalStateException(message)

    /** Release tag 缺省：导出记录版本号（无导出记录回退 v1.0.0）。 */
    private fun defaultTag(meta: com.zhique.core.project.ProjectMeta): String =
        meta.export?.let { "v${it.versionName}" } ?: "v1.0.0"
}
