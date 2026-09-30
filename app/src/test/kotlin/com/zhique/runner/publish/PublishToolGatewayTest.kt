package com.zhique.runner.publish

import com.zhique.core.common.crypto.CryptoStore
import com.zhique.core.common.crypto.KeyProvider
import com.zhique.core.publish.AssetInfo
import com.zhique.core.publish.GitHubApi
import com.zhique.core.publish.GitRepo
import com.zhique.core.publish.PatStore
import com.zhique.core.publish.ReleaseInfo
import com.zhique.core.publish.ReleaseJobEngine
import com.zhique.core.publish.ReleaseStage
import com.zhique.core.publish.RepoStatus
import com.zhique.core.project.ExportRecord
import com.zhique.core.project.ProjectRepository
import java.io.File
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.Rule
import org.junit.rules.TemporaryFolder

private class GwKey : KeyProvider {
    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    override fun masterKey(): SecretKey = key
}

private class GwGit : GitRepo() {
    var pushes = 0
    var commits = 0
    var head: String? = null
    override fun initRepo(projectDir: File) {}
    override fun status(projectDir: File) = RepoStatus(listOf("index.html"), emptyList(), emptyList())
    override fun commit(projectDir: File, message: String, authorName: String, authorEmail: String): String {
        commits++
        return "sha-${commits.toString().padStart(7, '0')}"
    }
    override fun push(projectDir: File, remoteUrl: String, pat: String, branch: String): String {
        pushes++
        head = "sha-${commits.toString().padStart(7, '0')}"
        return head!!
    }
    override fun remoteBranchSha(remoteUrl: String, pat: String, branch: String): String? = head
}

private class GwApi : GitHubApi() {
    var createCount = 0
    var uploadCount = 0
    var lastUpload: File? = null
    val assetsByTag = mutableMapOf<String, MutableList<String>>()
    private var lastTag: String? = null

    override fun listReleases(pat: String, owner: String, repo: String): List<ReleaseInfo> =
        assetsByTag.keys.map { tag ->
            ReleaseInfo(
                id = tag.hashCode().toLong(),
                tagName = tag,
                uploadUrl = "https://uploads.example.com/releases/${tag.hashCode()}/assets{?name}",
                assets = (assetsByTag[tag] ?: emptyList()).map { AssetInfo(id = 1, name = it) },
            )
        }

    override fun createRelease(
        pat: String, owner: String, repo: String, tag: String, notes: String, prerelease: Boolean,
    ): ReleaseInfo {
        createCount++
        lastTag = tag
        return ReleaseInfo(
            id = createCount.toLong(), tagName = tag, body = notes,
            uploadUrl = "https://uploads.example.com/releases/$createCount/assets{?name}",
        )
    }

    override fun uploadAsset(pat: String, uploadUrl: String, file: File, contentType: String): AssetInfo {
        uploadCount++
        lastUpload = file
        lastTag?.let { assetsByTag.getOrPut(it) { mutableListOf() } += file.name }
        return AssetInfo(id = uploadCount.toLong(), name = file.name, size = file.length())
    }
}

/**
 * Agent 发布通道（PublishToolGateway）：质量审查 Important-2——取消任务不作断点
 * （对齐手动向导），Agent 通道重新 plan 续跑；wantRelease/tag 透传与资产回注。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PublishToolGatewayTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var repo: ProjectRepository
    private lateinit var git: GwGit
    private lateinit var api: GwApi
    private lateinit var engine: ReleaseJobEngine
    private lateinit var gateway: PublishToolGateway
    private lateinit var projectId: String
    private lateinit var apk: File

    private fun setup(): Triple<ReleaseJobEngine, GwGit, GwApi> {
        tmp.create()
        val root = tmp.newFolder("root")
        repo = ProjectRepository(root)
        projectId = repo.create("织雀便签", "<html></html>").id
        repo.bindRepo(projectId, com.zhique.core.project.RepoBinding("alice", "bianqian"))
        repo.recordExport(
            projectId,
            ExportRecord("com.zhique.export.bianqian", 2, "1.0.2", 111L, "min", "d".repeat(64)),
        )
        apk = File(root, "exports/com.zhique.export.bianqian-2.apk").apply {
            parentFile.mkdirs(); writeBytes(ByteArray(16))
        }
        git = GwGit()
        api = GwApi()
        val pats = PatStore(File(root, "publish/pat.enc"), CryptoStore(GwKey())).apply {
            save("github_pat_" + "W".repeat(20))
        }
        engine = ReleaseJobEngine(repo, git, api, pats = pats, io = Dispatchers.Unconfined)
        gateway = PublishToolGateway(
            repo = repo,
            git = { git },
            api = { api },
            pats = { pats },
            engine = { engine },
            apkResolver = { apk },
        )
        return Triple(engine, git, api)
    }

    @Test
    fun `审查I2_取消任务后Agent通道重新plan并走满RELEASED`() = runTest {
        val (engine, git, api) = setup()
        // 手动向导先 plan 并取消（磁盘留 canceled=true 的非终态任务）
        val job = engine.plan(projectId, "https://github.com/alice/bianqian.git", message = "手动取消")
        engine.cancel(job)
        assertEquals(true, engine.resume(projectId)!!.canceled)

        // Agent 通道 push：不得接手取消任务（死锁源），重新 plan 并完成
        val out = gateway.push(projectId, message = "Agent 接手", wantRelease = true, tag = null)
        assertEquals("ok", out["status"].toString().removePrefix("\"").removeSuffix("\""))
        assertEquals(ReleaseStage.RELEASED, engine.latest(projectId)!!.stage)
        assertEquals(1, git.pushes, "新任务独立推进一次 push")
        assertEquals("v1.0.2", engine.latest(projectId)!!.tag, "tag 缺省取导出记录版本号")
        assertEquals(1, api.createCount)
        assertEquals(1, api.uploadCount, "解析器找到 APK → Release 附附件")
        assertEquals(apk, api.lastUpload)
    }

    @Test
    fun `push缺省wantRelease为true且消息可空自动生成`() = runTest {
        val (engine, git, api) = setup()
        val out = gateway.push(projectId, message = null, wantRelease = true, tag = null)
        assertTrue("ok" in out.toString())
        assertEquals(1, git.commits)
        val latest = engine.latest(projectId)!!
        assertTrue(latest.message!!.startsWith("更新 织雀便签"), "缺省 commit message=更新 <项目名>")
        assertEquals("v1.0.2", latest.tag)
        assertEquals(1, api.uploadCount)
    }

    @Test
    fun `wantRelease为false时止步无Release无上传`() = runTest {
        val (engine, _, api) = setup()
        val out = gateway.push(projectId, message = "仅推送", wantRelease = false, tag = null)
        assertTrue("ok" in out.toString())
        assertEquals(ReleaseStage.RELEASED, engine.latest(projectId)!!.stage)
        assertNull(engine.latest(projectId)!!.tag)
        assertEquals(0, api.createCount)
        assertEquals(0, api.uploadCount)
    }
}
