package com.zhique.runner.publish

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import com.zhique.core.publish.AssetInfo
import com.zhique.core.publish.GitHubApi
import com.zhique.core.publish.GitRepo
import com.zhique.core.publish.PatStore
import com.zhique.core.publish.ReleaseInfo
import com.zhique.core.publish.ReleaseJobEngine
import com.zhique.core.publish.ReleaseStage
import com.zhique.core.publish.RepoStatus
import com.zhique.core.common.crypto.CryptoStore
import com.zhique.core.common.crypto.KeyProvider
import com.zhique.core.project.ProjectRepository
import com.zhique.core.project.RepoBinding
import com.zhique.runner.ui.theme.ZqTheme
import java.io.File
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class WizardKey : KeyProvider {
    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    override fun masterKey(): SecretKey = key
}

private class WizardGit(var failPush: Boolean = false) : GitRepo() {
    var pushes = 0
    var commits = 0
    var head: String? = null
    override fun initRepo(projectDir: File) {}
    override fun status(projectDir: File) = RepoStatus(listOf("index.html"), emptyList(), emptyList())
    override fun commit(projectDir: File, message: String, authorName: String, authorEmail: String): String {
        commits++
        return "sha-000000$commits"
    }
    override fun push(projectDir: File, remoteUrl: String, pat: String, branch: String): String {
        if (failPush) throw java.io.IOException("远端拒绝（模拟网络失败）")
        pushes++
        head = "sha-000000$commits"
        return head!!
    }
    override fun remoteBranchSha(remoteUrl: String, pat: String, branch: String): String? = head
}

private class WizardApi : GitHubApi() {
    var created: Triple<String, Boolean, Boolean>? = null // name/private/autoInit
    var createCount = 0
    var uploadCount = 0
    var lastUpload: File? = null
    override fun createRepo(pat: String, name: String, isPrivate: Boolean, autoInit: Boolean) =
        com.zhique.core.publish.RepoCreated(
            id = 1,
            fullName = "alice/${name}",
            owner = com.zhique.core.publish.RepoCreated.Owner("alice"),
        ).also { created = Triple(name, isPrivate, autoInit) }

    var releases = mutableListOf<String>()

    override fun listReleases(pat: String, owner: String, repo: String): List<ReleaseInfo> =
        releases.map { ReleaseInfo(id = it.hashCode().toLong(), tagName = it) }

    override fun createRelease(
        pat: String, owner: String, repo: String, tag: String, notes: String, prerelease: Boolean,
    ): ReleaseInfo {
        createCount++
        releases += tag
        return ReleaseInfo(
            id = createCount.toLong(), tagName = tag, body = notes,
            uploadUrl = "https://uploads.example.com/releases/$createCount/assets{?name}",
        )
    }

    override fun uploadAsset(pat: String, uploadUrl: String, file: File, contentType: String): AssetInfo {
        uploadCount++
        lastUpload = file
        return AssetInfo(id = uploadCount.toLong(), name = file.name, size = file.length())
    }
}

/**
 * 发布向导（规格 §5.3 屏 12）：日常 2 步 / 首次 4 步、AI 预填可改、
 * 断点续跑横幅（含已取消恢复语义）、失败重试、绑定写回。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PublishWizardScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private data class Deps(
        val repo: ProjectRepository,
        val git: WizardGit,
        val api: WizardApi,
        val engine: ReleaseJobEngine,
        val projectId: String,
        val exportedApk: File? = null,
    )

    private fun build(bound: Boolean, failPush: Boolean = false): Deps {
        val root = tmp.newFolder()
        val repo = ProjectRepository(root)
        val projectId = repo.create("织雀便签", "<html></html>").id
        if (bound) {
            repo.bindRepo(projectId, RepoBinding("alice", "bianqian"))
            File(repo.projectDir(projectId), ".git").mkdirs()
            repo.recordExport(
                projectId,
                com.zhique.core.project.ExportRecord(
                    "com.zhique.export.bianqian", 2, "1.0.2", 111L, "min", "c".repeat(64),
                ),
            )
        }
        val exportedApk = if (bound) {
            File(root, "exports/com.zhique.export.bianqian-2.apk").apply {
                parentFile.mkdirs(); writeBytes(ByteArray(24))
            }
        } else {
            null
        }
        val git = WizardGit(failPush)
        val api = WizardApi()
        val pats = PatStore(File(root, "publish/pat.enc"), CryptoStore(WizardKey())).apply {
            save("github_pat_" + "Z".repeat(20))
        }
        val engine = ReleaseJobEngine(repo, git, api, pats = pats, io = Dispatchers.Unconfined)
        return Deps(repo, git, api, engine, projectId, exportedApk)
    }

    private fun setContent(
        deps: Deps,
        scheduler: TestCoroutineScheduler,
        backgroundScope: CoroutineScope,
    ): PublishController {
        val io = UnconfinedTestDispatcher(scheduler)
        val scope = backgroundScope
        val controller = PublishController(
            projectId = deps.projectId,
            repo = deps.repo,
            git = deps.git,
            api = deps.api,
            pats = PatStore(File(tmp.root, "pats.enc"), CryptoStore(WizardKey())).apply {
                save("github_pat_" + "Z".repeat(20))
            },
            engine = deps.engine,
            aiCommitMessage = { summary -> "AI：更新便签" },
            apkResolver = { deps.exportedApk },
            scope = scope,
            io = io,
            onToast = {},
        )
        compose.setContent {
            ZqTheme {
                PublishWizardScreen(controller = controller, onDone = {}, onToast = {})
            }
        }
        compose.waitForIdle()
        // IO refresh 就绪后再断言（projectName 由 refresh 填充）
        compose.waitUntil(10_000) { controller.state.value.projectName.isNotBlank() }
        compose.waitForIdle()
        return controller
    }

    private fun scroll(tag: String) {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `日常2步_变更检查加AI提交语可改_确认后完成并写回绑定`() = runTest {
        val deps = build(bound = true)
        val controller = setContent(deps, testScheduler, this)
        compose.onNodeWithTag("publish-step").assertTextContains("1/2", substring = true)
        compose.waitUntil(10_000) { controller.state.value.status != null }
        compose.waitForIdle()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("A index.html", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("A index.html", substring = true).assertExists()
        compose.onNodeWithTag("publish-commit-input").assertTextContains("AI：更新便签")
        // AI 预填可改（规格 F11）
        compose.onNodeWithTag("publish-commit-input").performTextReplacement("手改的说明")
        compose.onNodeWithTag("publish-next").performClick()
        compose.onNodeWithTag("publish-step").assertTextContains("2/2", substring = true)
        compose.onNodeWithTag("publish-confirm-repo").assertTextContains("alice/bianqian")
        compose.onNodeWithTag("publish-confirm-message").assertTextContains("手改的说明")
        compose.onNodeWithTag("publish-push").performClick()
        compose.waitUntil(10_000) { deps.git.pushes == 1 }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("publish-done").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("publish-done-detail").assertTextContains("已发布", substring = true)
        // 绑定记忆写回（lastPushedSha）
        assertEquals("sha-0000001", deps.repo.meta(deps.projectId).repo!!.lastPushedSha)
        assertEquals(1, deps.git.pushes)
    }

    @Test
    fun `首次4步_包信息到建仓推送_README与LICENSE落盘`() = runTest {
        val deps = build(bound = false)
        val controller = setContent(deps, testScheduler, this)
        compose.onNodeWithTag("publish-step").assertTextContains("1/4", substring = true)
        // 步1：包信息（PAT 已配置 → 无引导卡）
        compose.onNodeWithTag("publish-next").performClick()
        // 步2：建仓（预填 slug 名称/私有/README/LICENSE）
        compose.waitUntil(10_000) { controller.state.value.repoName.isNotBlank() }
        compose.waitForIdle()
        compose.onNodeWithTag("publish-repo-name").assertTextContains(
            PublishController.slug("织雀便签"),
            substring = true,
        )
        compose.onNodeWithTag("publish-private").performClick()
        compose.onNodeWithTag("publish-next").performClick()
        // 步3：确认（新建仓库 + 手改 message）
        val slug = PublishController.slug("织雀便签")
        compose.onNodeWithTag("publish-confirm-repo").assertTextContains("$slug（新建）", substring = true)
        compose.onNodeWithTag("publish-push").performClick()
        compose.waitUntil(10_000) { deps.api.created != null }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("publish-done").fetchSemanticsNodes().isNotEmpty()
        }
        // createRepo autoInit=false、README/LICENSE 生成、绑定写回
        assertEquals(slug to true, deps.api.created!!.first to deps.api.created!!.second)
        assertEquals(false, deps.api.created!!.third)
        assertTrue(File(deps.repo.projectDir(deps.projectId), "README.md").isFile)
        assertTrue(File(deps.repo.projectDir(deps.projectId), "LICENSE").readText().contains("Apache License"))
        assertEquals("alice", deps.repo.meta(deps.projectId).repo!!.owner)
        assertEquals(1, deps.git.pushes)
    }

    @Test
    fun `断点续跑横幅_继续后从非终态推进到完成`() = runTest {
        val deps = build(bound = true)
        // 手动制造停在 COMMITTED 的任务（模拟上次中断）
        deps.engine.plan(deps.projectId, "https://github.com/alice/bianqian.git", message = "上次中断")
        var job = deps.engine.resume(deps.projectId)!!
        job = deps.engine.advance(job) // CHECKED
        job = deps.engine.advance(job) // COMMITTED
        assertEquals(ReleaseStage.COMMITTED, job.stage)

        setContent(deps, testScheduler, this)
        scroll("publish-resume-banner")
        compose.onNodeWithTag("publish-resume-text").assertTextContains("已提交", substring = true)
        compose.onNodeWithTag("publish-resume-continue").performClick()
        compose.waitUntil(10_000) { deps.git.pushes == 1 }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("publish-done").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(1, deps.git.commits, "commit 只在手动预置阶段发生一次，续跑不重做")
        assertNotNull(deps.repo.meta(deps.projectId).repo?.lastPushedSha)
    }

    @Test
    fun `已取消任务显示恢复横幅_暂可收起`() = runTest {
        val deps = build(bound = true)
        val job = deps.engine.plan(deps.projectId, "https://github.com/alice/bianqian.git", message = "m")
        deps.engine.cancel(job)
        setContent(deps, testScheduler, this)
        scroll("publish-resume-banner")
        compose.onNodeWithTag("publish-resume-text").assertTextContains("已取消", substring = true)
        compose.onNodeWithTag("publish-resume-dismiss").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("publish-resume-banner").fetchSemanticsNodes().isEmpty()
        }
        compose.onAllNodesWithTag("publish-resume-banner").assertCountEquals(0)
    }

    @Test
    fun `审查补测_Release开关默认开_确认步可见tag并上传APK到RELEASED`() = runTest {
        val deps = build(bound = true)
        val controller = setContent(deps, testScheduler, this)
        compose.onNodeWithTag("publish-next").performClick()
        compose.waitUntil(10_000) { controller.state.value.releaseApk != null }
        compose.waitForIdle()
        // 确认步开关默认开，展示版本号与附件
        compose.onNodeWithTag("publish-want-release").assertExists()
        compose.onNodeWithTag("publish-release-tag")
            .assertTextContains("v1.0.2", substring = true)
        compose.onNodeWithTag("publish-release-tag")
            .assertTextContains("bianqian-2.apk", substring = true)
        compose.onNodeWithTag("publish-push").performClick()
        compose.waitUntil(10_000) { deps.git.pushes == 1 }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("publish-done").fetchSemanticsNodes().isNotEmpty()
        }
        // 状态机走满 RELEASED：Release 创建 + APK 上传各一次，tag 证据落盘
        assertEquals(ReleaseStage.RELEASED, deps.engine.latest(deps.projectId)!!.stage)
        assertEquals("v1.0.2", deps.engine.latest(deps.projectId)!!.tag)
        assertEquals(1, deps.api.createCount)
        assertEquals(1, deps.api.uploadCount)
        assertEquals(deps.exportedApk, deps.api.lastUpload)
    }

    @Test
    fun `审查补测_关闭Release开关则止步推送不建Release不上传`() = runTest {
        val deps = build(bound = true)
        val controller = setContent(deps, testScheduler, this)
        compose.onNodeWithTag("publish-next").performClick()
        compose.waitUntil(10_000) { controller.state.value.releaseApk != null }
        compose.waitForIdle()
        compose.onNodeWithTag("publish-want-release").performClick() // 关闭（默认开）
        compose.onNodeWithTag("publish-push").performClick()
        compose.waitUntil(10_000) { deps.git.pushes == 1 }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("publish-done").fetchSemanticsNodes().isNotEmpty()
        }
        // 无 Release 证据：不建 Release、不上传、无 tag（可选步被跳过）
        assertEquals(0, deps.api.createCount)
        assertEquals(0, deps.api.uploadCount)
        assertEquals(null, deps.engine.latest(deps.projectId)!!.tag)
    }

    @Test
    fun `失败显示重试_网络恢复后重试直达完成`() = runTest {
        val deps = build(bound = true, failPush = true)
        setContent(deps, testScheduler, this)
        compose.onNodeWithTag("publish-next").performClick()
        compose.onNodeWithTag("publish-push").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("publish-error").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("publish-error").assertTextContains("模拟网络失败", substring = true)
        compose.onNodeWithTag("publish-retry").assertExists()
        // 失败不回退阶段：磁盘仍停在 COMMITTED
        assertEquals(ReleaseStage.COMMITTED, deps.engine.latest(deps.projectId)!!.stage)

        deps.git.failPush = false
        compose.onNodeWithTag("publish-retry").performClick()
        compose.waitUntil(10_000) { deps.git.pushes == 1 }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("publish-done").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(ReleaseStage.RELEASED, deps.engine.latest(deps.projectId)!!.stage)
    }
}
