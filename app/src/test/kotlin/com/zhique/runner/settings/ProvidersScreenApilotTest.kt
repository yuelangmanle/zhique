package com.zhique.runner.settings

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.zhique.core.apilot.ApilotAuditStore
import com.zhique.core.common.crypto.CryptoStore
import com.zhique.core.common.crypto.KeyProvider
import java.io.File
import javax.crypto.KeyGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Task 8.1b：服务商页「Apilot 双向流转」区——两动作卡 + 安装检测 + 上次同步 + 记录清除。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProvidersScreenApilotTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeKeyProvider : KeyProvider {
        private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        override fun masterKey() = key
    }

    private var installed = true

    private fun newApilotController(): ApilotController {
        val dispatcher = UnconfinedTestDispatcher()
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(dispatcher),
            produceFile = { File(tmp.root, "settings-${System.nanoTime()}.preferences_pb") },
        )
        return ApilotController(
            store = ProviderStore(dataStore, CryptoStore(FakeKeyProvider())),
            audit = ApilotAuditStore(File(tmp.root, "audit-${System.nanoTime()}.jsonl")),
            sync = ApilotSyncStore(dataStore),
            checkInstalled = { installed },
            scope = CoroutineScope(dispatcher),
            io = dispatcher,
        )
    }

    @Test
    fun 双向流转区渲染_已安装时两卡可用() {
        installed = true
        val apilot = newApilotController()
        val providers = ProvidersController(
            store = ProviderStore(
                PreferenceDataStoreFactory.create(
                    scope = CoroutineScope(UnconfinedTestDispatcher()),
                    produceFile = { File(tmp.root, "providers-${System.nanoTime()}.preferences_pb") },
                ),
                CryptoStore(FakeKeyProvider()),
            ),
            fetcher = com.zhique.core.ai.ModelListFetcher(),
            scope = CoroutineScope(UnconfinedTestDispatcher()),
            io = UnconfinedTestDispatcher(),
        )
        compose.setContent { ProvidersScreen(providers, onBack = {}, apilot = apilot) }
        compose.onNodeWithTag("apilot-section").assertExists()
        compose.onNodeWithTag("apilot-import").assertExists().assertIsEnabled()
        compose.onNodeWithTag("apilot-sync").assertExists().assertIsEnabled()
        compose.onNodeWithTag("apilot-installed").assertExists()
        compose.onNodeWithText("已检测到 Apilot").assertExists()
        compose.onNodeWithTag("apilot-last-sync").assertExists()
        compose.onNodeWithText("接入 从未 · 推送 从未", substring = true).assertExists()
        compose.onNodeWithTag("apilot-clear").assertExists()
    }

    @Test
    fun 未安装时动作卡禁用并提示() {
        installed = false
        val apilot = newApilotController()
        val providers = ProvidersController(
            store = ProviderStore(
                PreferenceDataStoreFactory.create(
                    scope = CoroutineScope(UnconfinedTestDispatcher()),
                    produceFile = { File(tmp.root, "providers2-${System.nanoTime()}.preferences_pb") },
                ),
                CryptoStore(FakeKeyProvider()),
            ),
            fetcher = com.zhique.core.ai.ModelListFetcher(),
            scope = CoroutineScope(UnconfinedTestDispatcher()),
            io = UnconfinedTestDispatcher(),
        )
        compose.setContent { ProvidersScreen(providers, onBack = {}, apilot = apilot) }
        compose.onNodeWithTag("apilot-import").assertIsNotEnabled()
        compose.onNodeWithTag("apilot-sync").assertIsNotEnabled()
        compose.onNodeWithText("未检测到 Apilot").assertExists()
    }

    @Test
    fun 记录清除按钮落审计并出提示() {
        installed = true
        val apilot = newApilotController()
        val providers = ProvidersController(
            store = ProviderStore(
                PreferenceDataStoreFactory.create(
                    scope = CoroutineScope(UnconfinedTestDispatcher()),
                    produceFile = { File(tmp.root, "providers3-${System.nanoTime()}.preferences_pb") },
                ),
                CryptoStore(FakeKeyProvider()),
            ),
            fetcher = com.zhique.core.ai.ModelListFetcher(),
            scope = CoroutineScope(UnconfinedTestDispatcher()),
            io = UnconfinedTestDispatcher(),
        )
        compose.setContent { ProvidersScreen(providers, onBack = {}, apilot = apilot) }
        compose.onNodeWithTag("apilot-clear").performClick()
        compose.waitUntil { apilot.state.value.notice != null }
        compose.onNodeWithText("桥接记录已清除").assertExists()
        assertTrue(apilot.state.value.notice!!.contains("清除"))
        assertEquals("com.example.api_manager", com.zhique.core.apilot.ApilotBridge().apilotPackage)
    }
}
