package com.zhique.core.publish

import java.io.File
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider

/**
 * status 摘要（规格 §4.8「自动变更检查」）：A=新增 M=修改 D=删除，
 * 口径 = HEAD（无 HEAD 时为空树）→ 工作区。
 */
data class RepoStatus(
    val added: List<String>,
    val modified: List<String>,
    val deleted: List<String>,
) {
    val clean: Boolean get() = added.isEmpty() && modified.isEmpty() && deleted.isEmpty()
    val changes: Int get() = added.size + modified.size + deleted.size

    fun summary(): String = buildString {
        added.forEach { appendLine("A $it") }
        modified.forEach { appendLine("M $it") }
        deleted.forEach { appendLine("D $it") }
    }.trimEnd('\n')
}

/**
 * 项目目录上的 Git 底座（JGit，Task 7.1）。
 *
 * 纪律：
 * - 凭据固定 `x-access-token + PAT`（fine-grained PAT 口径），仅对 http(s) 远端挂载；
 * - **PAT 永不入日志**：本类不持有任何日志出口，JGit 不开 console/SLF4J 输出，
 *   异常文本对外抛出前不包含凭据（push 错误只带远端拒绝状态）；
 * - 方法均为 open：纯 JVM 测试用临时仓库打真 JGit，状态机测试用子类 fake。
 */
open class GitRepo {

    /** 在项目目录初始化仓库（幂等：已存在 .git 则跳过；默认分支固定 main，规格 §4.8）。 */
    open fun initRepo(projectDir: File) {
        if (File(projectDir, ".git").exists()) return
        projectDir.mkdirs()
        Git.init().setDirectory(projectDir).setInitialBranch(DEFAULT_BRANCH).call().use { }
    }

    /** diff 摘要：新增/修改/删除三清单。 */
    open fun status(projectDir: File): RepoStatus {
        Git.open(projectDir).use { git ->
            val s = git.status().call()
            return RepoStatus(
                added = (s.added + s.untracked).distinct().sorted(),
                modified = (s.changed + s.modified).distinct().sorted(),
                deleted = (s.removed + s.missing).distinct().sorted(),
            )
        }
    }

    /** 暂存全部变更（含删除）并提交，返回 commit sha。 */
    open fun commit(
        projectDir: File,
        message: String,
        authorName: String = "zhique",
        authorEmail: String = "zhique@local",
    ): String {
        Git.open(projectDir).use { git ->
            git.add().addFilepattern(".").call()
            git.add().setUpdate(true).addFilepattern(".").call()
            val id = git.commit()
                .setMessage(message)
                .setAuthor(authorName, authorEmail)
                .setCommitter(authorName, authorEmail)
                .setAllowEmpty(true)
                .call()
            return id.name
        }
    }

    /**
     * 推送本地分支到远端，返回推送后本地分支头 sha。
     * 凭据 `UsernamePasswordCredentialsProvider("x-access-token", pat)`，
     * 仅 http(s) 远端挂载（PAT 永不落日志/异常文本）。
     */
    open fun push(projectDir: File, remoteUrl: String, pat: String, branch: String): String {
        Git.open(projectDir).use { git ->
            val command = git.push()
                .setRemote(remoteUrl)
                .setRefSpecs(RefSpec("refs/heads/$branch:refs/heads/$branch"))
            if (remoteUrl.startsWith("http://") || remoteUrl.startsWith("https://")) {
                command.setCredentialsProvider(UsernamePasswordCredentialsProvider("x-access-token", pat))
            }
            val results = command.call()
            for (result in results) {
                for (update in result.remoteUpdates) {
                    when (update.status) {
                        org.eclipse.jgit.transport.RemoteRefUpdate.Status.OK,
                        org.eclipse.jgit.transport.RemoteRefUpdate.Status.UP_TO_DATE,
                        -> Unit
                        else -> throw PublishException(
                            "推送被远端拒绝（${update.status}）${update.message?.let { "：$it" } ?: ""}",
                        )
                    }
                }
            }
            val head = git.repository.resolve(Constants.R_HEADS + branch)
                ?: throw PublishException("本地分支不存在：$branch")
            return head.name
        }
    }

    /** 远端分支当前 sha；分支不存在返回 null（幂等核对用）。 */
    open fun remoteBranchSha(remoteUrl: String, pat: String, branch: String): String? {
        val command = Git.lsRemoteRepository()
            .setRemote(remoteUrl)
            .setHeads(true)
        if (remoteUrl.startsWith("http://") || remoteUrl.startsWith("https://")) {
            command.setCredentialsProvider(UsernamePasswordCredentialsProvider("x-access-token", pat))
        }
        command.call().forEach { ref ->
            if (ref.name == Constants.R_HEADS + branch) return ref.objectId.name()
        }
        return null
    }

    /** 远端 tag 是否已存在（Release 幂等核对：远端已有该 tag 则跳过重做）。 */
    open fun remoteTagExists(remoteUrl: String, pat: String, tag: String): Boolean {
        val command = Git.lsRemoteRepository()
            .setRemote(remoteUrl)
            .setTags(true)
        if (remoteUrl.startsWith("http://") || remoteUrl.startsWith("https://")) {
            command.setCredentialsProvider(UsernamePasswordCredentialsProvider("x-access-token", pat))
        }
        command.call().forEach { ref ->
            if (ref.name == Constants.R_TAGS + tag) return true
        }
        return false
    }

    companion object {
        const val DEFAULT_BRANCH = "main"
    }
}
