package com.zhique.runner.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.zhique.core.ai.Protocol
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
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * AI 服务商屏（Task 3.6）：协议联动 base URL 预填、保存入库带协议与 modality 徽章、输出上限 0=默认。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProvidersScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeKeyProvider : KeyProvider {
        private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        override fun masterKey() = key
    }

    private fun newController(): ProvidersController {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(UnconfinedTestDispatcher()),
            produceFile = { File(tmp.root, "settings.preferences_pb") },
        )
        val store = ProviderStore(dataStore, CryptoStore(FakeKeyProvider()))
        return ProvidersController(
            store = store,
            fetcher = com.zhique.core.ai.ModelListFetcher(),
            scope = CoroutineScope(UnconfinedTestDispatcher()),
            io = UnconfinedTestDispatcher(),
        )
    }

    @Test
    fun `协议切换联动base预填_保存后列表带徽章`() = runTest {
        val c = newController()
        compose.setContent { com.zhique.runner.ui.theme.ZqTheme { ProvidersScreen(c, onBack = {}) } }
        compose.onNodeWithTag("provider-add").performClick()
        compose.onNodeWithTag("provider-name").performTextInput("我的服务")
        compose.onNodeWithTag("provider-model").performTextInput("deepseek-chat")
        compose.onNodeWithTag("proto-${Protocol.GOOGLE_GENAI}").performClick()
        compose.waitForIdle()
        assertEquals(
            ProtocolDefaults.baseUrl(Protocol.GOOGLE_GENAI),
            c.form.value?.baseUrl,
            "切协议联动预填",
        )
        compose.onNodeWithTag("provider-base").performTextClearance()
        compose.onNodeWithTag("provider-base").performTextInput("https://example.invalid")
        compose.onNodeWithTag("provider-key").performTextInput("test-key-" + "a".repeat(8))
        compose.onNodeWithTag("provider-form").performScrollToNode(hasTestTag("provider-save"))
        compose.onNodeWithTag("provider-save").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("我的服务").assertExists()
        compose.onNodeWithTag("provider-row-我的服务").assertExists()
        val saved = c.providers.value.single()
        assertEquals(Protocol.GOOGLE_GENAI, saved.protocol)
        assertTrue(saved.keyCipher.isNotEmpty() && "test-key" !in saved.keyCipher, "密文落库")
    }

    @Test
    fun `输出上限0表示默认_编辑往返`() = runTest {
        val c = newController()
        compose.setContent { com.zhique.runner.ui.theme.ZqTheme { ProvidersScreen(c, onBack = {}) } }
        compose.onNodeWithTag("provider-add").performClick()
        compose.onNodeWithTag("provider-name").performTextInput("A")
        compose.onNodeWithTag("provider-model").performTextInput("gpt-4o")
        compose.onNodeWithTag("provider-maxout").performTextInput("0")
        compose.onNodeWithTag("provider-form").performScrollToNode(hasTestTag("provider-save"))
        compose.onNodeWithTag("provider-save").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("provider-edit-A").performClick()
        compose.waitForIdle()
        assertEquals(0, c.form.value?.maxOutputManual, "0=按模型默认语义保留")
        // modality 徽章按知识库（gpt-4o → vision）
        assertTrue(c.form.value?.modalityBadge == "vision")
    }
}
