package com.zhique.runner.export

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.zhique.core.export.KeystoreManager
import com.zhique.core.project.ProjectRepository
import com.zhique.runner.ui.theme.ZqTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class CardSoftwareKey : com.zhique.core.common.crypto.KeyProvider {
    private val key: javax.crypto.SecretKey =
        javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    override fun masterKey(): javax.crypto.SecretKey = key
}

/**
 * 密钥库备份状态卡（决策29-3 常驻组件，导出中心/权限中心共用）：
 * lastBackupAt 渲染、超期提醒、恢复指纹不一致拒绝的错误提示条。
 * 控制器走真实 IO 线程，断言经 compose.waitUntil 等状态落地。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeystoreBackupCardTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private class Scene(
        val controller: KeystoreBackupController,
        val keystore: KeystoreManager,
        val repo: ProjectRepository,
        val foreignJks: java.io.File,
        val foreignPass: String,
    )

    private fun setup(): Scene {
        tmp.create()
        val root = tmp.newFolder()
        val repo = ProjectRepository(root)
        val keystore = KeystoreManager(root, com.zhique.core.common.crypto.CryptoStore(CardSoftwareKey()))
        // 另一个密钥库（不同指纹）作为「错误的恢复源」
        val foreign = KeystoreManager(tmp.newFolder(), com.zhique.core.common.crypto.CryptoStore(CardSoftwareKey()))
        foreign.ensureKeystore()
        val foreignJks = foreign.exportTo(java.io.File(tmp.newFolder(), "foreign.jks"))
        val foreignPass = foreign.password()
        val controller = KeystoreBackupController(
            keystore = keystore,
            existingFingerprints = {
                runCatching { repo.list() }.getOrDefault(emptyList())
                    .mapNotNull { it.export?.certSha256 }.filter { it.isNotBlank() }
            },
            scope = CoroutineScope(Dispatchers.Main),
            ioDispatcher = Dispatchers.Unconfined,
            onToast = {},
        )
        compose.setContent {
            ZqTheme {
                KeystoreBackupCard(controller = controller, testPrefix = "perm-keystore")
            }
        }
        return Scene(controller, keystore, repo, foreignJks, foreignPass)
    }

    @Test
    fun `状态渲染-超期提醒到已备份`() {
        val s = setup()
        // 密钥库生成 → 未备份 → 超期提醒（常驻真值）
        s.keystore.ensureKeystore()
        s.controller.refresh()
        compose.waitUntil(10_000) { s.controller.status.value?.backupDue == true }
        compose.onNodeWithTag("perm-keystore-backup-status")
            .assertTextContains("超期", substring = true)
        // 备份落地 → 显示已备份时间
        s.controller.markBackedUp()
        compose.waitUntil(10_000) { s.controller.status.value?.backupDue == false }
        compose.onNodeWithTag("perm-keystore-backup-status")
            .assertTextContains("已备份", substring = true)
        compose.onNodeWithTag("perm-keystore-backup-btn").assertExists()
        compose.onNodeWithTag("perm-keystore-restore-btn").assertExists()
    }

    @Test
    fun `恢复指纹不一致-拒绝生效并提示`() {
        val s = setup()
        s.keystore.ensureKeystore()
        // 既有导出记录的指纹（与 foreign 密钥库不同）
        val pid = s.repo.create("便签", "<p></p>").id
        s.repo.recordExport(
            pid,
            com.zhique.core.project.ExportRecord("com.zhique.export.app", 1, "1.0.1", 0, "min", "e".repeat(64)),
        )
        val before = s.keystore.certificateSha256()
        s.controller.restore(s.foreignJks.absolutePath, s.foreignPass)
        compose.waitUntil(10_000) { s.controller.restoreError.value != null }
        compose.onNodeWithTag("perm-keystore-restore-error")
            .assertTextContains("已拒绝生效", substring = true)
        // 密钥库保持原状（未被替换）
        assertEquals(before, s.keystore.certificateSha256())
    }
}
