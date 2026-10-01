package com.zhique.runner.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.zhique.runner.paste.PastePreferences
import com.zhique.runner.ui.theme.ZqTheme
import java.io.File
import kotlin.test.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** M9 Task 9.2：设置树逐项有 UI 且可进（§7 信息架构验收）。 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsFullTreeUiTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newDataStore() = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(UnconfinedTestDispatcher()),
        produceFile = { File(tmp.newFolder(), "s-${System.nanoTime()}.preferences_pb") },
    )

    @Test
    fun `设置根屏含§7全部入口`() {
        compose.setContent {
            ZqTheme {
                SettingsScreen(
                    onOpenChat = {}, onOpenProviders = {}, onOpenRoleRouter = {},
                    onOpenOutputContext = {}, onOpenTokenStats = {},
                    onOpenPermissionCenter = {}, onOpenPublishSync = {},
                    onOpenGeneral = {}, onOpenPrivacy = {}, onOpenAbout = {},
                    onOpenDeveloper = {},
                )
            }
        }
        listOf(
            "settings-chat", "settings-providers", "settings-router",
            "settings-output-context", "settings-token-stats",
            "settings-permissions", "settings-publish", "settings-about",
            "settings-general", "settings-privacy", "settings-developer",
        ).forEach { tag ->
            compose.onNodeWithTag(tag).assertExists()
        }
    }

    @Test
    fun `输出思考上下文页可进并展示规格默认值`() {
        compose.setContent {
            ZqTheme {
                OutputContextScreen(prefs = AiPreferences(newDataStore()), onBack = {})
            }
        }
        compose.onNodeWithTag("oc-max-tokens").assertIsDisplayed()
        listOf(
            "oc-thinking-collapse", "oc-segments-row", "oc-context-limit",
            "oc-budget-value", "oc-compact-value", "oc-save",
        ).forEach { compose.onNodeWithTag(it).assertExists() }
    }

    @Test
    fun `通用页六组齐备_通知三事件开关在场`() {
        compose.setContent {
            ZqTheme {
                GeneralScreen(
                    general = GeneralPreferences(newDataStore()),
                    paste = PastePreferences(newDataStore()),
                    web = WebPreferences(newDataStore()),
                    onBack = {},
                )
            }
        }
        compose.onNodeWithTag("general-theme").assertIsDisplayed()
        listOf(
            "general-layout-drawer", "general-bubble-remember",
            "general-immersive", "general-paste-autorun",
            "general-desktop-ua", "general-eruda",
            "general-notify-export", "general-notify-agent", "general-notify-version",
        ).forEach { tag ->
            compose.onNodeWithTag(tag).assertExists()
        }
    }

    @Test
    fun `隐私与安全页_应用锁入口与审计告知区块`() {
        val privacy = PrivacyPreferences(newDataStore())
        val auditDir = tmp.newFolder()
        compose.setContent {
            ZqTheme {
                PrivacyScreen(
                    privacy = privacy,
                    lock = AppLockController(
                        privacy,
                        CoroutineScope(UnconfinedTestDispatcher()),
                    ),
                    audit = com.zhique.core.apilot.ApilotAuditStore(
                        File(auditDir, "audit.jsonl"),
                    ),
                    onBack = {},
                )
            }
        }
        compose.onNodeWithTag("privacy-lock-setup").assertIsDisplayed()
        compose.onNodeWithTag("privacy-audit-row").assertExists()
        compose.onNodeWithTag("privacy-notice-row").assertExists()
        compose.onNodeWithTag("privacy-audit-view").performClick()
        compose.onNodeWithTag("privacy-audit-list").assertExists()
        compose.onNodeWithTag("privacy-notice-view").performClick()
        compose.onNodeWithTag("privacy-notice-list").assertExists()
    }

    @Test
    fun `开发者页_调试开关日志导出协议文档与意图测试器占位`() {
        compose.setContent {
            ZqTheme {
                DeveloperScreen(web = WebPreferences(newDataStore()), onBack = {})
            }
        }
        compose.onNodeWithTag("dev-log-export").assertIsDisplayed()
        compose.onNodeWithTag("dev-intent-placeholder").assertExists()
        compose.onNodeWithTag("dev-doc-camera").assertExists()
    }
}
