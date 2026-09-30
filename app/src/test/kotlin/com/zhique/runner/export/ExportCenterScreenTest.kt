package com.zhique.runner.export

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.zhique.core.export.KeystoreManager
import com.zhique.core.project.ExportRecord
import com.zhique.core.project.ProjectRepository
import com.zhique.runner.ui.theme.ZqTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class CenterSoftwareKey : com.zhique.core.common.crypto.KeyProvider {
    private val key: javax.crypto.SecretKey =
        javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    override fun masterKey(): javax.crypto.SecretKey = key
}

/** 导出中心（规格 §5.3 屏 8）：备份状态置顶、导出物清单、推送更新入口（M7 接线）。 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExportCenterScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private data class Scene(val repo: ProjectRepository, val exportedId: String, val freshId: String)

    private var exportedTo: String? = null
    private var pushedTo: String? = null

    private fun setup(bound: Boolean = false): Scene {
        val root = tmp.newFolder()
        val repo = ProjectRepository(root)
        val a = repo.create("便签", "<p></p>")
        val b = repo.create("计算器", "<p></p>")
        repo.recordExport(
            a.id,
            ExportRecord("com.zhique.export.bianqian", 2, "1.0.2", 111L, "min", "b".repeat(64)),
        )
        if (bound) {
            repo.bindRepo(a.id, com.zhique.core.project.RepoBinding("alice", "bianqian", lastPushedSha = "abc1234def"))
        }
        val keystore = KeystoreManager(root, com.zhique.core.common.crypto.CryptoStore(CenterSoftwareKey()))
        compose.setContent {
            ZqTheme {
                ExportCenterScreen(
                    repo = repo,
                    keystore = keystore,
                    onExport = { exportedTo = it.id },
                    onPush = { pushedTo = it.id },
                    onToast = {},
                    ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
                )
            }
        }
        compose.waitForIdle()
        return Scene(repo, a.id, b.id)
    }

    /** LazyColumn 离屏条目不参与组合：滚动到目标才算数；数据未就绪则随轮询重试。 */
    private fun scroll(tag: String) {
        compose.waitUntil(10_000) {
            try {
                compose.onNodeWithTag("export-center-root").performScrollToNode(hasTestTag(tag))
                compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            } catch (_: Throwable) {
                false
            }
        }
    }

    @Test
    fun `备份状态置顶显示密钥库未生成`() {
        setup()
        compose.onNodeWithTag("center-backup-status").assertTextContains("密钥库", substring = true)
        compose.onNodeWithTag("center-backup-btn").assertExists()
    }

    @Test
    fun `导出物清单显示包名与版本`() {
        setup()
        scroll("center-list-anchor")
        compose.onNodeWithText("com.zhique.export.bianqian", substring = true).assertExists()
        compose.onNodeWithText("v2（1.0.2）", substring = true).assertExists()
    }

    @Test
    fun `点击导出按钮回调对应项目`() {
        val scene = setup()
        val tag = "center-export-${scene.exportedId}"
        scroll(tag)
        compose.onNodeWithTag(tag).performClick()
        assertEquals(scene.exportedId, exportedTo)
    }

    @Test
    fun `未导出项目在可导出区`() {
        val scene = setup()
        val tag = "center-export-${scene.freshId}"
        scroll(tag)
        compose.onNodeWithTag(tag).assertExists()
    }

    @Test
    fun `未绑定仓库时推送区给引导文案`() {
        setup(bound = false)
        scroll("center-push-card")
        compose.onNodeWithTag("center-push-card").assertExists()
        compose.onNodeWithTag("center-push-empty").assertExists()
    }

    @Test
    fun `绑定仓库显示远端与上次推送并可一键发起`() {
        val scene = setup(bound = true)
        scroll("center-push-card")
        compose.onNodeWithText("alice/bianqian").assertExists()
        compose.onNodeWithText("上次推送 abc1234", substring = true).assertExists()
        val tag = "center-push-${scene.exportedId}"
        compose.onNodeWithTag(tag).performClick()
        assertEquals(scene.exportedId, pushedTo)
    }
}
