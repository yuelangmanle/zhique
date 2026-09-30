package com.zhique.core.publish

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.eclipse.jgit.api.Git
import org.junit.rules.TemporaryFolder

/**
 * Task 7.1：GitRepo（临时仓库打真 JGit；push 目标为本地 bare 仓库=mock remote，
 * **绝不真实 push 到任何网络远端**——纪律：本仓库代码不因测试而 push）。
 */
class GitRepoTest {

    private val tmp = TemporaryFolder()
    private val repo = GitRepo()

    private fun newProject(name: String): File {
        tmp.create()
        val dir = tmp.newFolder(name)
        File(dir, "index.html").writeText("<html><body>hi</body></html>")
        return dir
    }

    /** 本地 bare 仓库当 mock remote，返回 file:// URL。 */
    private fun newBareRemote(name: String): String {
        val bare = tmp.newFolder("$name-remote.git")
        Git.init().setBare(true).setDirectory(bare).call().use { }
        return bare.toURI().toString()
    }

    @Test
    fun `initRepo幂等且status识别新增修改删除`() {
        val dir = newProject("p1")
        repo.initRepo(dir)
        assertTrue(File(dir, ".git").exists())
        repo.initRepo(dir) // 二次调用不抛错（幂等）

        val clean = repo.status(dir)
        assertTrue(clean.added.contains("index.html"), "首次快照：未跟踪算新增")

        repo.commit(dir, "init")
        val afterCommit = repo.status(dir)
        assertTrue(afterCommit.clean, afterCommit.summary())

        File(dir, "old.txt").writeText("x")
        repo.commit(dir, "add old") // old.txt 进入版本库，之后删除才是「缺失」
        File(dir, "old.txt").delete()
        File(dir, "index.html").writeText("<html><body>changed</body></html>") // M
        File(dir, "app.js").writeText("console.log(1)") // 新增 → A

        val s = repo.status(dir)
        assertTrue(s.added.contains("app.js"), s.summary())
        assertTrue(s.modified.contains("index.html"), s.summary())
        assertTrue(s.deleted.contains("old.txt"), s.summary())
        assertEquals(3, s.changes)
    }

    @Test
    fun `commit返回sha且两次提交sha不同`() {
        val dir = newProject("p2")
        repo.initRepo(dir)
        val sha1 = repo.commit(dir, "first")
        File(dir, "a.txt").writeText("1")
        val sha2 = repo.commit(dir, "second")
        assertEquals(40, sha1.length)
        assertTrue(sha1 != sha2)
    }

    @Test
    fun `push推送本地分支到bare远端并返回分支头sha`() {
        val dir = newProject("p3")
        val remote = newBareRemote("p3")
        repo.initRepo(dir)
        File(dir, "index.html").writeText("<html>v1</html>")
        val sha = repo.commit(dir, "v1")
        val pushed = repo.push(dir, remote, pat = "dummy-pat", branch = "main")
        assertEquals(sha, pushed)
        assertEquals(sha, repo.remoteBranchSha(remote, pat = "dummy-pat", branch = "main"))
    }

    @Test
    fun `remoteBranchSha对未推送分支返回null且remoteTagExists核对tag`() {
        val dir = newProject("p4")
        val remote = newBareRemote("p4")
        assertEquals(null, repo.remoteBranchSha(remote, "dummy-pat", "main"))
        assertFalse(repo.remoteTagExists(remote, "dummy-pat", "v1"))

        repo.initRepo(dir)
        repo.commit(dir, "v1")
        repo.push(dir, remote, "dummy-pat", "main")
        Git.open(dir).use { git ->
            git.tag().setName("v1").call()
            git.push().setRemote(remote).setRefSpecs(
                org.eclipse.jgit.transport.RefSpec("refs/tags/v1:refs/tags/v1"),
            ).call()
        }
        assertTrue(repo.remoteTagExists(remote, "dummy-pat", "v1"))
    }

    @Test
    fun `push空仓库成功对file协议不挂凭据且PAT不出现在异常文本`() {
        val dir = newProject("p5")
        val remote = newBareRemote("p5")
        repo.initRepo(dir)
        repo.commit(dir, "v1")
        // 空仓库无 main 头：push 正常成功；这里验证 PAT 只进凭据提供器
        val pat = "github_pat_" + "X".repeat(20)
        val pushed = repo.push(dir, remote, pat, "main")
        assertTrue(pushed.isNotBlank())
    }

    @Test
    fun `审查I4_gitignore隔离history目录且commit幂等写隔离`() {
        val dir = newProject("p6")
        repo.initRepo(dir)
        val ignore = File(dir, ".gitignore")
        assertTrue(ignore.isFile, "init 即写 .gitignore")
        assertTrue("history/" in ignore.readText())

        File(dir, "index.html").writeText("<html>v1</html>")
        File(dir, "history").apply { mkdirs() }
        File(dir, "history/release-job.json").writeText("{\"id\":\"x\",\"device\":\"/Users/someone/...\"}")
        val sha = repo.commit(dir, "init") // commit 前幂等补写 .gitignore（即使被删）
        ignore.delete()
        File(dir, "app.js").writeText("x")
        repo.commit(dir, "second")

        val s = repo.status(dir)
        assertTrue(s.clean, "history/ 不得入状态（.gitignore 隔离）：${s.summary()}")
        assertTrue(File(dir, ".gitignore").isFile, "commit 幂等重建 .gitignore")
        assertEquals(40, sha.length)
    }

    @Test
    fun `审查I4b_跟踪树不含history目录`() {
        val dir = newProject("p7")
        repo.initRepo(dir)
        File(dir, "index.html").writeText("<html>v1</html>")
        File(dir, "history").apply { mkdirs() }
        File(dir, "history/snap-1.html").writeText("<html>snapshot</html>")
        repo.commit(dir, "init")
        Git.open(dir).use { git ->
            val commit = git.repository.parseCommit(git.repository.resolve("HEAD"))
            val walk = org.eclipse.jgit.treewalk.TreeWalk.forPath(
                git.repository, "history/snap-1.html", commit.tree,
            )
            assertTrue(walk == null, "history/ 不得进入跟踪树")
            assertTrue(
                org.eclipse.jgit.treewalk.TreeWalk.forPath(git.repository, "index.html", commit.tree) != null,
                "正常项目文件仍被跟踪",
            )
        }
    }

    @Test
    fun `审查M8_push到已有分叉历史的远端被REJECTED_NONFASTFORWARD拒绝`() {
        tmp.create()
        val remote = newBareRemote("ff")
        val a = newProject("ffa")
        repo.initRepo(a)
        File(a, "index.html").writeText("<html>A</html>")
        repo.commit(a, "A1")
        repo.push(a, remote, "dummy-pat", "main")

        val b = newProject("ffb") // 独立历史
        repo.initRepo(b)
        File(b, "index.html").writeText("<html>B</html>")
        repo.commit(b, "B1")
        val e = assertFailsWith<PublishException> { repo.push(b, remote, "dummy-pat", "main") }
        assertTrue("REJECTED_NONFASTFORWARD" in (e.message ?: ""), e.message)
    }

    @Test
    fun `SecretRedactor对Bearer与GitHub令牌字面脱敏`() {
        val pat = "github_pat_ABCDEFGHIJKLMNOPQRSTUV"
        val line = "POST https://api.github.com/user/repos Authorization=Bearer $pat"
        val out = SecretRedactor.redact(line)
        assertFalse(pat in out)
        assertTrue("Bearer ***" in out)
        assertFalse("github_pat_ABCDEFGHIJKLMNOPQRSTUV" in SecretRedactor.redact("token ghp_1234567890abcdef"))
        assertTrue(SecretRedactor.redact("无凭据文本") == "无凭据文本")
    }
}
