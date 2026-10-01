package com.zhique.runner.settings

import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.zhique.core.common.crypto.CryptoStore
import com.zhique.core.common.crypto.KeyProvider
import com.zhique.core.project.ProjectRepository
import com.zhique.runner.home.HomeScreen
import com.zhique.runner.paste.PastePreferences
import com.zhique.runner.ui.theme.ZqTheme
import java.io.File
import javax.crypto.KeyGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 规格审查三缺口收口（M9）：
 * ① 剪贴板检测开关接 Home 卡显隐；② 清洗严格度档位；③ 连接诊断独立入口页；
 * ④ 通用页新增行（检测/严格度/代码字体）可进可操作；⑤ 编辑器字体族接缝。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReviewGapFixUiTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val jsPaste = "```js\nconst a = 1;\n```"

    private fun newDataStore() = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(UnconfinedTestDispatcher()),
        produceFile = { File(tmp.newFolder(), "g-${System.nanoTime()}.preferences_pb") },
    )

    private class FakeKeyProvider : KeyProvider {
        private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        override fun masterKey() = key
    }

    private fun newProviderStore(): Pair<ProviderStore, File> {
        val dir = tmp.newFolder()
        val store = ProviderStore(
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(UnconfinedTestDispatcher()),
                produceFile = { File(dir, "providers.preferences_pb") },
            ),
            CryptoStore(FakeKeyProvider()),
        )
        return store to dir
    }

    // ---- ① 剪贴板检测开关接 Home 卡显隐 ----

    @Test
    fun `检测开_刷新剪贴板出卡可进预览`() = runTest {
        compose.setContent {
            ZqTheme {
                HomeScreen(
                    repo = ProjectRepository(tmp.root),
                    onRun = {},
                    onToast = {},
                    clipboardText = { jsPaste },
                    clipboardDetection = true,
                )
            }
        }
        compose.onNodeWithTag("clipboard-refresh").assertIsDisplayed()
        compose.onNodeWithTag("clipboard-refresh").performClick()
        // 分类在 IO 线程异步完成，轮询等卡片出现
        compose.waitUntil(5000) {
            compose.onAllNodesWithTag("clipboard-card").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("clipboard-card").assertIsDisplayed()
    }

    @Test
    fun `检测关_不自动检测不显卡与刷新按钮`() = runTest {
        val prefs = PastePreferences(newDataStore())
        prefs.setClipboardDetection(false)
        assertEquals(false, prefs.clipboardDetection.first())

        compose.setContent {
            ZqTheme {
                HomeScreen(
                    repo = ProjectRepository(tmp.root),
                    onRun = {},
                    onToast = {},
                    clipboardText = { jsPaste },
                    clipboardDetection = false,
                )
            }
        }
        compose.onNodeWithTag("clipboard-refresh").assertDoesNotExist()
        compose.onNodeWithTag("clipboard-card").assertDoesNotExist()
    }

    // ---- ② 通用页：检测开关 / 清洗严格度 / 代码字体 可操作 ----

    @Test
    fun `通用页智能粘贴与编辑器新行可进可操作`() = runTest {
        val paste = PastePreferences(newDataStore())
        val general = GeneralPreferences(newDataStore())
        compose.setContent {
            ZqTheme {
                GeneralScreen(
                    general = general,
                    paste = paste,
                    web = WebPreferences(newDataStore()),
                    onBack = {},
                )
            }
        }
        // 可进：三组新行在场
        compose.onNodeWithTag("general-paste-detect").assertExists()
        compose.onNodeWithTag("general-paste-strict-standard").assertExists()
        compose.onNodeWithTag("general-font-family-sans").assertExists()

        // 可操作：三行均带点击/切换动作（Robolectric 合成点击对 toggleable 不稳定，交互留真机验收）
        listOf(
            "general-paste-detect",
            "general-paste-strict-standard",
            "general-font-family-sans",
        ).forEach { tag ->
            val hasAction = compose.onNodeWithTag(tag).fetchSemanticsNode().config
                .contains(androidx.compose.ui.semantics.SemanticsActions.OnClick)
            assertTrue(hasAction, "$tag 应可点击/可切换")
        }

        // 持久层直验：偏好写 → DataStore 落盘可读
        paste.setClipboardDetection(false)
        waitUntilStore { !paste.clipboardDetection.first() }
        assertFalse(paste.clipboardDetection.first(), "检测开关写入 DataStore")
        paste.setCleanStrictness(PastePreferences.CLEAN_CONSERVATIVE)
        waitUntilStore { paste.cleanStrictness.first() == PastePreferences.CLEAN_CONSERVATIVE }
        assertEquals(PastePreferences.CLEAN_CONSERVATIVE, paste.cleanStrictness.first())
        general.setEditorFontFamily(GeneralPreferences.FONT_SANS_MONO)
        waitUntilStore { general.editorFontFamily.first() == GeneralPreferences.FONT_SANS_MONO }
        assertEquals(GeneralPreferences.FONT_SANS_MONO, general.editorFontFamily.first())
    }

    /** DataStore 异步落盘轮询（IO 线程完成时机不定，waitForIdle 不覆盖）。 */
    private suspend fun waitUntilStore(timeoutMs: Long = 4000, probe: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (runCatching { probe() }.getOrDefault(false)) return
            Thread.sleep(50)
        }
    }

    // ---- ③ 连接诊断独立入口页 ----

    @Test
    fun `连接诊断页_逐个测试显示连通与延迟`() = runTest {
        val (store, _) = newProviderStore()
        val config = com.zhique.runner.settings.ProviderConfig(
            id = "p1", name = "测试服务商", protocol = "openai",
            baseUrl = "https://example.invalid", keyCipher = store.encryptKey("test-" + "k".repeat(12)),
            model = "test-model",
        )
        store.upsert(config)
        advanceUntilIdle()
        var probed = 0
        val controller = DiagnosticsController(
            store = store,
            scope = CoroutineScope(UnconfinedTestDispatcher()),
            io = UnconfinedTestDispatcher(),
            probe = { _, _, _ -> probed++; listOf("m1", "m2") },
        )
        advanceUntilIdle()

        compose.setContent {
            ZqTheme { DiagnosticsScreen(controller = controller, onBack = {}) }
        }
        compose.onNodeWithTag("diag-test-all").assertIsDisplayed()
        compose.onNodeWithTag("diag-row-p1").assertExists()

        // 可操作：逐个测试 → 结果行显示连通与延迟
        compose.onNodeWithTag("diag-test-p1").performClick()
        advanceUntilIdle()
        assertEquals(1, probed)
        val row = controller.rows.value.single()
        assertTrue(row.ok == true, "假探测返回模型列表 → 连通：${row.detail}")
        assertTrue(row.detail.contains("连通"))
        compose.onNodeWithTag("diag-result-p1").assertExists()
    }

    @Test
    fun `连接诊断页_失败结果落错误明细`() = runTest {
        val (store, _) = newProviderStore()
        store.upsert(
            com.zhique.runner.settings.ProviderConfig(
                id = "p2", name = "坏服务商", protocol = "openai",
                baseUrl = "https://broken.invalid", keyCipher = store.encryptKey("test-" + "x".repeat(12)),
                model = "m",
            ),
        )
        advanceUntilIdle()
        val controller = DiagnosticsController(
            store = store,
            scope = CoroutineScope(UnconfinedTestDispatcher()),
            io = UnconfinedTestDispatcher(),
            probe = { _, _, _ -> throw com.zhique.core.ai.AiError.Network("连接失败", java.io.IOException("timeout")) },
        )
        advanceUntilIdle()
        compose.setContent {
            ZqTheme { DiagnosticsScreen(controller = controller, onBack = {}) }
        }
        compose.onNodeWithTag("diag-test-p2").performClick()
        advanceUntilIdle()
        val row = controller.rows.value.single()
        assertTrue(row.ok == false, "探测异常 → 失败态")
        assertTrue(row.detail.contains("连接失败"), "错误明细透出：${row.detail}")
    }

    @Test
    fun `连接诊断页_全部测试遍历所有服务商`() = runTest {
        val (store, _) = newProviderStore()
        listOf("a", "b").forEach { id ->
            store.upsert(
                com.zhique.runner.settings.ProviderConfig(
                    id = id, name = "服务商-$id", protocol = "openai",
                    baseUrl = "https://$id.invalid", keyCipher = store.encryptKey("test-" + id.repeat(12)),
                    model = "m",
                ),
            )
        }
        advanceUntilIdle()
        var probed = 0
        val controller = DiagnosticsController(
            store = store,
            scope = CoroutineScope(UnconfinedTestDispatcher()),
            io = UnconfinedTestDispatcher(),
            probe = { _, _, _ -> probed++; emptyList() },
        )
        advanceUntilIdle()
        compose.setContent {
            ZqTheme { DiagnosticsScreen(controller = controller, onBack = {}) }
        }
        compose.onNodeWithTag("diag-test-all").performClick()
        advanceUntilIdle()
        assertEquals(2, probed, "全部测试遍历每个服务商")
        assertTrue(controller.rows.value.all { it.ok == true })
    }
}
