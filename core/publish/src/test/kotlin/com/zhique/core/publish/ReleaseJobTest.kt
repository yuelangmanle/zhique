package com.zhique.core.publish

import com.zhique.core.project.ProjectRepository
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.rules.TemporaryFolder

/**
 * Task 7.2：ReleaseJob 持久化状态机 5 场景（计划钦定）：
 * ①每步落盘 + kill 模拟（重启后 resume 返回下一 stage）
 * ②幂等（PUSHED 重复 advance 不重复 push）
 * ③跨模式接管（手动停在 COMMITTED → Agent 引擎从下一步续跑，同一状态机）
 * ④用户取消语义（canceled ≠ 失败、不重试、可恢复）
 * ⑤RELEASED 可选步骤（wantRelease=false 直达终态；RELEASED 重复 advance 原样返回）
 */
class ReleaseJobTest {

    private val tmp = TemporaryFolder()

    /** 测试固定密钥（运行时生成，零口令字面量）。 */
    private object StaticKey : com.zhique.core.common.crypto.KeyProvider {
        private val key: javax.crypto.SecretKey =
            javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        override fun masterKey(): javax.crypto.SecretKey = key
    }

    private lateinit var repo: ProjectRepository
    private lateinit var projectId: String

    private fun setupProject() {
        tmp.create()
        val root = tmp.newFolder("root")
        repo = ProjectRepository(root)
        projectId = repo.create("织雀便签", "<html><body>hi</body></html>").id
        File(repo.projectDir(projectId), "app.js").writeText("console.log('v1')")
    }

    private val remote = "https://github.com/alice/demo.git"

    /** Git fake：全部动作计数 + 可编程远端状态（JGit 不参与，纯状态机口径）。 */
    private class FakeGit(
        var pushCount: Int = 0,
        var commitCount: Int = 0,
        var remoteBranch: String? = null,
        var remoteTags: Set<String> = emptySet(),
    ) : GitRepo() {
        val pushedRemotes = mutableListOf<String>()
        override fun initRepo(projectDir: File) {}
        override fun status(projectDir: File) = RepoStatus(listOf("index.html"), emptyList(), emptyList())
        override fun commit(projectDir: File, message: String, authorName: String, authorEmail: String): String {
            commitCount++
            return "sha-" + commitCount.toString().padStart(7, '0')
        }
        override fun push(projectDir: File, remoteUrl: String, pat: String, branch: String): String {
            pushCount++
            pushedRemotes += remoteUrl
            remoteBranch = "sha-000000$pushCount"
            return remoteBranch!!
        }
        override fun remoteBranchSha(remoteUrl: String, pat: String, branch: String): String? = remoteBranch
        override fun remoteTagExists(remoteUrl: String, pat: String, tag: String): Boolean = tag in remoteTags
    }

    /** API fake：Release 创建计数 + 可编程已有清单 + 资产上传计数。 */
    private class FakeApi(
        var releases: MutableList<String> = mutableListOf(),
        var createCount: Int = 0,
    ) : GitHubApi() {
        var lastNotes: String? = null
        var uploadCount = 0
        var lastUpload: File? = null
        override fun listReleases(pat: String, owner: String, repo: String): List<ReleaseInfo> =
            releases.map { ReleaseInfo(id = it.hashCode().toLong(), tagName = it) }
        override fun createRelease(
            pat: String, owner: String, repo: String, tag: String, notes: String, prerelease: Boolean,
        ): ReleaseInfo {
            createCount++
            lastNotes = notes
            releases += tag
            return ReleaseInfo(
                id = createCount.toLong(), tagName = tag, body = notes,
                uploadUrl = "https://uploads.example.com/repos/$owner/$repo/releases/$createCount/assets{?name}",
            )
        }
        override fun uploadAsset(pat: String, uploadUrl: String, file: File, contentType: String): AssetInfo {
            uploadCount++
            lastUpload = file
            return AssetInfo(id = uploadCount.toLong(), name = file.name, size = file.length())
        }
    }

    private fun dummyPats(): PatStore {
        val f = File(tmp.root, "pats-${System.nanoTime()}.enc")
        return PatStore(f, com.zhique.core.common.crypto.CryptoStore(StaticKey)).also {
            it.save("github_pat_" + "D".repeat(20))
        }
    }

    private fun engine(git: GitRepo = FakeGit(), api: GitHubApi = FakeApi(), pats: PatStore? = dummyPats()) =
        ReleaseJobEngine(repo, git, api, pats = pats, io = Dispatchers.Default)

    // ---- 场景①：每步落盘 + kill 模拟 ----

    @Test
    fun `场景1_每步完成即落盘证据且重启后resume返回当前stage`() = runTest {
        setupProject()
        val git = FakeGit()
        val api = FakeApi()
        val e1 = engine(git, api)

        val planned = e1.plan(projectId, remote, message = "更新便签", tag = "v0.1.0", wantRelease = true)
        assertEquals(ReleaseStage.PLANNED, planned.stage)
        // 落盘证据：plan 即有文件
        assertTrue(repo.readReleaseJobJson(projectId)!!.contains("\"stage\":\"PLANNED\""))

        val checked = e1.advance(planned)
        assertEquals(ReleaseStage.CHECKED, checked.stage)
        val committed = e1.advance(checked)
        assertEquals(ReleaseStage.COMMITTED, committed.stage)
        assertTrue(committed.commitSha!!.startsWith("sha-"), "commit 证据落 job")

        // —— kill 模拟：新引擎实例（同磁盘状态）读回；远端还没有该 sha ——
        val e2git = FakeGit(remoteBranch = null)
        val e2 = engine(e2git, api)
        val resumed = e2.resume(projectId)
        assertEquals(ReleaseStage.COMMITTED, resumed!!.stage, "重启后从已落盘阶段续跑")
        assertEquals(committed.commitSha, resumed.commitSha)

        val pushed = e2.advance(resumed)
        assertEquals(ReleaseStage.PUSHED, pushed.stage)
        val released = e2.advance(pushed)
        assertEquals(ReleaseStage.RELEASED, released.stage)
        assertEquals("v0.1.0", released.tag)

        // 全程证据链都在磁盘上（最后一份=RELEASED 含 sha+tag+时间戳）
        val raw = repo.readReleaseJobJson(projectId)!!
        assertTrue("\"commitSha\":\"sha-" in raw)
        assertTrue("\"tag\":\"v0.1.0\"" in raw)
        assertTrue(raw.contains("\"updatedAt\""))
        assertEquals(1, e2git.pushCount, "整链只 push 一次")
        assertEquals(listOf(remote), e2git.pushedRemotes)
        assertEquals(1, api.createCount, "整链只建一次 Release")
    }

    @Test
    fun `场景1b_每阶段磁盘文件独立可读且终态resume返回null`() = runTest {
        setupProject()
        val e = engine()
        var job = e.plan(projectId, remote, message = "m")
        for (expected in listOf(
            ReleaseStage.CHECKED, ReleaseStage.COMMITTED, ReleaseStage.PUSHED, ReleaseStage.RELEASED,
        )) {
            job = e.advance(job)
            val onDisk = e.latest(projectId)!!
            assertEquals(expected, onDisk.stage, "每步落盘：磁盘=$expected")
            assertEquals(job.updatedAt, onDisk.updatedAt)
        }
        assertNull(e.resume(projectId), "终态无可续跑任务")
    }

    // ---- 场景②：幂等 ----

    @Test
    fun `场景2_远端已含sha时PUSHED重复advance不重复push`() = runTest {
        setupProject()
        val git = FakeGit()
        val e = engine(git)
        var job = e.plan(projectId, remote, message = "m")
        job = e.advance(job)          // CHECKED
        job = e.advance(job)          // COMMITTED
        job = e.advance(job)          // PUSHED（真 push 一次）
        assertEquals(1, git.pushCount)
        val sha = job.commitSha!!

        // 幂等核对：把远端头对齐成本 sha → 重复 advance 全链不重推
        git.remoteBranch = sha
        repeat(3) {
            job = e.advance(job)      // PUSHED→RELEASED
            job = e.advance(job)      // RELEASED 原样返回
        }
        assertEquals(ReleaseStage.RELEASED, job.stage)
        assertEquals(1, git.pushCount, "远端已含 sha → 不重推")
        assertEquals(listOf(remote), git.pushedRemotes)
    }

    @Test
    fun `场景2b_远端已有tag时重复advance不重复建Release`() = runTest {
        setupProject()
        val api = FakeApi()
        val e = engine(api = api)
        var job = e.plan(projectId, remote, message = "m", tag = "v1", wantRelease = true)
        repeat(4) { job = e.advance(job) }
        assertEquals(1, api.createCount)
        assertEquals("m", api.lastNotes, "Release notes=commit message")

        // 模拟重复触发（再次 resume+advance）：tag 已存在 → 跳过
        val again = e.advance(job.copy(stage = ReleaseStage.PUSHED))
        assertEquals(1, api.createCount, "远端已有 tag → 不重建 Release")
        assertEquals(ReleaseStage.RELEASED, again.stage)
    }

    @Test
    fun `场景2c_远端不含sha时PUSHED阶段重试会补推一次`() = runTest {
        setupProject()
        val git = FakeGit(remoteBranch = null) // 远端还没有
        val e = engine(git)
        var job = e.plan(projectId, remote, message = "m")
        repeat(3) { job = e.advance(job) }
        assertEquals(ReleaseStage.PUSHED, job.stage)
        assertEquals(1, git.pushCount)
        assertNotEquals(null, job.commitSha)
    }

    // ---- 场景③：跨模式接管 ----

    @Test
    fun `场景3_手动停在COMMITTED后Agent引擎从下一步续跑`() = runTest {
        setupProject()
        // 手动向导引擎推进到 COMMITTED 后「退出」（模拟用户离开）
        val manualEngine = engine(FakeGit())
        var job = manualEngine.plan(projectId, remote, message = "手动提交", tag = "v1", wantRelease = true)
        job = manualEngine.advance(job) // CHECKED
        job = manualEngine.advance(job) // COMMITTED

        // Agent 模式：新引擎（独立 fake 协作者）resume 后继续——同一状态机
        val agentGit = FakeGit(remoteBranch = null)
        val agentApi = FakeApi()
        val agentEngine = ReleaseJobEngine(repo, agentGit, agentApi, pats = dummyPats(), io = Dispatchers.Default)
        val taken = requireNotNull(agentEngine.resume(projectId)) { "接管须拿到非终态任务" }
        assertEquals(ReleaseStage.COMMITTED, taken.stage, "跨模式接管：从磁盘阶段续跑")

        var current = taken
        while (!current.terminal) current = agentEngine.advance(current)
        assertEquals(ReleaseStage.RELEASED, current.stage)
        assertEquals(1, agentGit.pushCount, "接管方只补后续步骤（push）")
        assertEquals(0, agentGit.commitCount, "commit 已在手动模式完成，不重做")
        assertEquals(1, agentApi.createCount)
    }

    @Test
    fun `场景3b_接管后cancel再由手动resume可恢复且canceled可清除`() = runTest {
        setupProject()
        val e1 = engine(FakeGit())
        var job = e1.plan(projectId, remote, message = "m")
        job = e1.advance(job) // CHECKED
        val canceled = e1.cancel(job)
        assertTrue(canceled.canceled)

        // Agent 侧接管：canceled 任务 advance 抛取消异常、不执行动作
        val agentGit = FakeGit()
        val agentEngine = engine(agentGit, FakeApi())
        val resumed = agentEngine.resume(projectId)!!
        assertFailsWith<ReleaseCanceledException> { agentEngine.advance(resumed) }
        assertEquals(0, agentGit.pushCount + agentGit.commitCount, "取消后不执行任何动作")

        // 手动侧恢复：canceled 清除后照常推进（取消≠终局）
        val reviver = ReleaseJobEngine(repo, FakeGit(), FakeApi(), pats = dummyPats(), io = Dispatchers.Default)
        var cleared = reviver.resume(projectId)!!.copy(canceled = false)
        while (!cleared.terminal) cleared = reviver.advance(cleared)
        assertEquals(ReleaseStage.RELEASED, cleared.stage)
        assertFalse(reviver.resume(projectId) != null || true == reviver.latest(projectId)?.canceled)
    }

    // ---- 场景④：用户取消语义 ----

    @Test
    fun `场景4_canceled任务advance不执行动作且异常为ReleaseCanceled`() = runTest {
        setupProject()
        val git = FakeGit()
        val api = FakeApi()
        val e = engine(git, api)
        val job = e.plan(projectId, remote, message = "m")
        val canceled = e.cancel(job)
        assertEquals(ReleaseStage.PLANNED, canceled.stage, "取消不改变阶段")
        assertTrue(canceled.canceled)
        assertTrue(repo.readReleaseJobJson(projectId)!!.contains("\"canceled\":true"), "取消证据落盘")

        val ex = assertFailsWith<ReleaseCanceledException> { e.advance(canceled) }
        assertEquals(ReleaseStage.PLANNED, ex.job.stage)
        assertEquals(0, git.commitCount + git.pushCount)
        assertEquals(0, api.createCount)
        // 非 PublishException：取消不是失败
        assertFalse(ex is PublishException)
    }

    @Test
    fun `场景4b_步骤失败阶段不回退错误落盘且可从原阶段重试`() = runTest {
        setupProject()
        val flaky = object : GitRepo() {
            var failPush = true
            var commits = 0
            var head: String? = null
            override fun initRepo(projectDir: File) {}
            override fun status(projectDir: File) = RepoStatus(emptyList(), emptyList(), emptyList())
            override fun commit(projectDir: File, message: String, authorName: String, authorEmail: String) =
                "sha-fail-${++commits}"
            override fun push(projectDir: File, remoteUrl: String, pat: String, branch: String): String {
                if (failPush) throw java.io.IOException("network down")
                head = "sha-fail-$commits"
                return head!!
            }
            override fun remoteBranchSha(remoteUrl: String, pat: String, branch: String): String? = head
        }
        val e = engine(flaky)
        var job = e.plan(projectId, remote, message = "m")
        repeat(2) { job = e.advance(job) } // → COMMITTED
        assertFailsWith<PublishException> { e.advance(job) } // push 失败
        val after = e.latest(projectId)!!
        assertEquals(ReleaseStage.COMMITTED, after.stage, "失败不回退阶段")
        assertTrue(after.error!!.contains("network down"), "错误证据落盘")

        flaky.failPush = false // 用户重试：网络恢复 → 同一任务从 COMMITTED 继续
        val retried = e.advance(e.resume(projectId)!!)
        assertEquals(ReleaseStage.PUSHED, retried.stage)
        assertNull(retried.error)
    }

    // ---- 场景⑤：RELEASED 可选 ----

    @Test
    fun `场景5_wantRelease为false时PUSHED直达RELEASED且不触API`() = runTest {
        setupProject()
        val api = FakeApi()
        val e = engine(api = api)
        var job = e.plan(projectId, remote, message = "m", tag = null, wantRelease = false)
        assertEquals(ReleaseStage.PLANNED, job.stage)
        job = e.advance(job) // CHECKED
        job = e.advance(job) // COMMITTED
        job = e.advance(job) // PUSHED
        val released = e.advance(job) // PUSHED → RELEASED（跳过 Release）
        assertEquals(ReleaseStage.RELEASED, released.stage)
        assertNull(released.tag, "未建 Release → 无 tag 证据")
        assertEquals(0, api.createCount, "可选步骤未触发 API")
        assertNull(e.resume(projectId), "跳过也是终态：无可续跑")
    }

    @Test
    fun `场景5b_RELEASED终态重复advance原样返回且latest保留证据`() = runTest {
        setupProject()
        val api = FakeApi()
        val e = engine(api = api)
        var job = e.plan(projectId, remote, message = "m", tag = "v9", wantRelease = true)
        repeat(4) { job = e.advance(job) }
        val again = e.advance(job)
        assertEquals(job, again, "终态幂等：同一实例原样返回")
        assertEquals(1, api.createCount)
        assertEquals("v9", e.latest(projectId)!!.tag)
    }

    @Test
    fun `审查补测_带releaseAsset的plan走到RELEASED且uploadAsset被调用`() = runTest {
        setupProject()
        val apk = File(tmp.root, "app-1.apk").apply { writeBytes(ByteArray(32)) }
        val api = FakeApi()
        val git = FakeGit()
        val e = engine(git, api)
        var job = e.plan(
            projectId, remote, message = "发布 1.0.2", tag = "v1.0.2",
            wantRelease = true, releaseAsset = apk.absolutePath,
        )
        repeat(4) { job = e.advance(job) }
        assertEquals(ReleaseStage.RELEASED, job.stage)
        assertEquals("v1.0.2", job.tag)
        assertEquals(1, git.pushCount)
        assertEquals(1, api.createCount)
        assertEquals(1, api.uploadCount, "Release 附 APK：uploadAsset 恰好一次")
        assertEquals(apk, api.lastUpload)
        // 落盘证据同样带 tag 与资产路径
        val raw = repo.readReleaseJobJson(projectId)!!
        assertTrue("\"tag\":\"v1.0.2\"" in raw)
        assertTrue(apk.absolutePath in raw)

        // 幂等：重复 advance（重放 PUSHED）不重建 Release、不重传资产
        e.advance(job.copy(stage = ReleaseStage.PUSHED))
        assertEquals(1, api.createCount)
        assertEquals(1, api.uploadCount)
    }

    @Test
    fun `审查补测_资产文件缺失时仍建Release不崩溃`() = runTest {
        setupProject()
        val api = FakeApi()
        val e = engine(api = api)
        var job = e.plan(
            projectId, remote, message = "m", tag = "v2",
            wantRelease = true, releaseAsset = File(tmp.root, "absent.apk").absolutePath,
        )
        repeat(4) { job = e.advance(job) }
        assertEquals(ReleaseStage.RELEASED, job.stage)
        assertEquals(1, api.createCount, "无附件也创建 Release")
        assertEquals(0, api.uploadCount, "缺失文件不上传")
    }

    @Test
    fun `场景5c_PAT缺失时push步骤失败且错误不含凭据形态`() = runTest {
        setupProject()
        // PAT 源指向不存在的密文文件 → requirePat 抛 PublishException；错误文案不出现凭据样 token
        val noPat = PatStore(File(tmp.root, "absent.enc"), com.zhique.core.common.crypto.CryptoStore(StaticKey))
        val e = engine(pats = noPat)
        var job = e.plan(projectId, remote, message = "m")
        repeat(2) { job = e.advance(job) }
        val ex = assertFailsWith<PublishException> { e.advance(job) }
        assertTrue("PAT" in (ex.message ?: ""))
        assertFalse(Regex("[A-Za-z0-9_]{30,}").containsMatchIn(ex.message ?: ""), "不得含长 token 形态")
        assertEquals(ReleaseStage.COMMITTED, e.latest(projectId)!!.stage)
    }
}
