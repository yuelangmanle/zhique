package com.zhique.runner.export

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasTestTag
import com.zhique.core.export.ExportOutcome
import com.zhique.core.export.KeystoreManager
import com.zhique.core.export.SignatureMismatchException
import com.zhique.core.permission.PermissionRegistry
import com.zhique.core.project.ExportRecord
import com.zhique.core.project.ProjectRepository
import com.zhique.runner.ui.theme.ZqTheme
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class UiSoftwareKey : com.zhique.core.common.crypto.KeyProvider {
    private val key: javax.crypto.SecretKey =
        javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    override fun masterKey(): javax.crypto.SecretKey = key
}

/**
 * 导出向导 UI（规格 §5.3 屏 7）：三步流转、备份状态置顶、变体选择、
 * 签名不一致阻断页、完成页交付按钮。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExportWizardScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var repo: ProjectRepository
    private lateinit var controller: ExportController
    private lateinit var projectId: String

    private fun setup(
        scheduler: TestCoroutineScheduler,
        backgroundScope: CoroutineScope,
        executor: suspend (String, String, String) -> ExportOutcome = { _, _, _ ->
            throw UnsupportedOperationException()
        },
    ) {
        val root = tmp.newFolder()
        repo = ProjectRepository(root)
        val registry = PermissionRegistry(repo)
        projectId = repo.create("织雀便签", "<p></p>").id
        val keystore = KeystoreManager(root, com.zhique.core.common.crypto.CryptoStore(UiSoftwareKey()))
        controller = ExportController(
            projectId = projectId,
            repo = repo,
            registry = registry,
            keystore = keystore,
            executor = executor,
            scope = backgroundScope,
            ioDispatcher = UnconfinedTestDispatcher(scheduler),
        )
        compose.setContent {
            ZqTheme {
                ExportWizardScreen(controller = controller, onDone = {}, onToast = {})
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `步1显示应用信息与图标预览并可进入步2`() = runTest {
        setup(testScheduler, this)
        compose.onNodeWithTag("wizard-step").assertTextContains("1/3", substring = true)
        compose.onNodeWithTag("wizard-name").performTextInput("便签plus")
        compose.onNodeWithTag("wizard-color").performTextInput("#334455")
        compose.onNodeWithTag("wizard-next").performClick()
        compose.onNodeWithTag("wizard-step").assertTextContains("2/3", substring = true)
        // 步2置顶：备份状态 + 变体
        compose.onNodeWithTag("wizard-backup").assertExists()
        compose.onNodeWithTag("wizard-variant-full").performClick()
        compose.onNodeWithTag("wizard-variant-min").performClick()
    }

    @Test
    fun `备份状态置顶且超期提醒可见`() = runTest {
        setup(testScheduler, this)
        compose.onNodeWithTag("wizard-next").performClick()
        compose.onNodeWithTag("wizard-backup-status")
            .assertTextContains("密钥", substring = true)
        compose.onNodeWithTag("wizard-backup-btn").assertExists()
    }

    @Test
    fun `打包完成页展示包信息与交付动作`() = runTest {
        setup(testScheduler, this) { p, _, _ ->
            ExportOutcome(
                apk = File(tmp.newFolder(), "out.apk").apply { writeBytes(ByteArray(4)) },
                record = ExportRecord("com.zhique.export.app", 1, "1.0.1", 42, "min", "a".repeat(64)),
            ).also { repo.recordExport(p, it.record) }
        }
        compose.onNodeWithTag("wizard-name").performTextInput("便签plus")
        compose.onNodeWithTag("wizard-next").performClick()
        compose.onNodeWithTag("wizard-pack").performClick()
        compose.waitUntil(10_000) { controller.state.value.result != null }
        compose.onNodeWithTag("wizard-done").assertExists()
        compose.onNodeWithTag("wizard-install").assertExists()
        compose.onNodeWithTag("wizard-save").assertExists()
        compose.onNodeWithTag("wizard-share").assertExists()
        compose.onNodeWithTag("wizard-shortcut").assertExists()
        compose.onNodeWithText("v1（1.0.1）", substring = true).assertExists()
    }

    @Test
    fun `签名不一致出现阻断页说明出路`() = runTest {
        setup(testScheduler, this) { _, _, _ ->
            throw SignatureMismatchException("com.zhique.export.app", "ks", listOf("other"))
        }
        compose.onNodeWithTag("wizard-name").performTextInput("便签plus")
        compose.onNodeWithTag("wizard-next").performClick()
        compose.onNodeWithTag("wizard-pack").performClick()
        compose.waitUntil(10_000) { controller.state.value.mismatch }
        compose.onNodeWithTag("wizard-mismatch").assertTextContains("签名不一致", substring = true)
        compose.onAllNodesWithText("卸载", substring = true).assertCountEquals(2)
        compose.onNodeWithTag("wizard-mismatch-back").performClick()
        compose.onNodeWithTag("wizard-step").assertTextContains("2/3", substring = true)
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertExists(): Boolean {
        assertExists()
        return true
    }
}
