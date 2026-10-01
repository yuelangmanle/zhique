package com.zhique.runner.onboarding

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.zhique.core.ai.ModelListFetcher
import com.zhique.core.common.crypto.CryptoStore
import com.zhique.core.common.crypto.KeyProvider
import com.zhique.runner.settings.ProviderStore
import java.io.File
import javax.crypto.KeyGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 首启引导（X4 / §5.3 屏 1）：粘贴识别预填、隐私卡勾选门禁、跳过玩示例、Apilot 占位。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OnboardingScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeKeyProvider : KeyProvider {
        private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        override fun masterKey() = key
    }

    private val testKey = "test-key-" + "b".repeat(8)

    private fun newEnv(): Triple<OnboardingController, ProviderStore, OnboardingPreferences> {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(UnconfinedTestDispatcher()),
            produceFile = { File(tmp.root, "settings.preferences_pb") },
        )
        val store = ProviderStore(dataStore, CryptoStore(FakeKeyProvider()))
        val prefs = OnboardingPreferences(dataStore)
        var done = 0
        val controller = OnboardingController(
            store = store,
            prefs = prefs,
            fetcher = ModelListFetcher(),
            scope = CoroutineScope(UnconfinedTestDispatcher()),
            io = UnconfinedTestDispatcher(),
            onDone = { done++ },
        )
        return Triple(controller, store, prefs)
    }

    private fun setContent(c: OnboardingController) {
        compose.setContent {
            com.zhique.runner.ui.theme.ZqTheme { OnboardingScreen(controller = c) }
        }
        compose.waitForIdle()
    }

    @Test
    fun `粘贴识别预填表单字段`() = runTest {
        val (c, _, _) = newEnv()
        setContent(c)
        compose.onNodeWithTag("onb-paste").performTextInput(
            """{"baseUrl":"https://api.example.invalid/v1","apiKey":"$testKey","model":"deepseek-chat"}""",
        )
        compose.onNodeWithTag("onb-recognize").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("已识别并预填，请核对后测连通").assertExists()
        assertEquals("https://api.example.invalid", c.state.value.baseUrl)
        assertEquals("deepseek-chat", c.state.value.model)
        // Apilot 接入卡（M8 已激活；未装 Apilot 时按钮禁用）与隐私卡在场
        compose.onNodeWithTag("onb-apilot-card").assertExists()
        compose.onNodeWithText("从 Apilot 接入").assertExists()
    }

    @Test
    fun `隐私未勾选不能完成_勾选后完成落Provider`() = runTest {
        val (c, store, prefs) = newEnv()
        setContent(c)
        // 识别 + 填齐草稿
        compose.onNodeWithTag("onb-paste").performTextInput(
            """{"baseUrl":"https://api.example.invalid","apiKey":"$testKey","model":"deepseek-chat"}""",
        )
        compose.onNodeWithTag("onb-recognize").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("onboarding-root")
            .performScrollToNode(androidx.compose.ui.test.hasTestTag("onb-finish"))
        compose.onNodeWithTag("onb-finish").performClick()
        compose.waitForIdle()
        assertEquals(false, prefs.isDone(), "未勾选隐私 → 完成被拦")
        compose.onNodeWithText("请先勾选隐私告知").assertExists()

        compose.onNodeWithTag("onboarding-root").performScrollToNode(androidx.compose.ui.test.hasTestTag("onb-privacy-check"))
        compose.onNodeWithTag("onb-privacy-check").performClick()
        compose.waitForIdle()
        assertTrue(prefs.privacyAckedAt.first() > 0, "隐私勾选记录落盘")
        compose.onNodeWithTag("onboarding-root").performScrollToNode(androidx.compose.ui.test.hasTestTag("onb-finish"))
        compose.onNodeWithTag("onb-finish").performClick()
        compose.waitForIdle()
        assertEquals(true, prefs.isDone())
        assertEquals(1, store.list().size, "草稿完整 → 落一个 Provider")
    }

    @Test
    fun `跳过玩示例_记录完成`() = runTest {
        val (c, store, prefs) = newEnv()
        setContent(c)
        compose.onNodeWithTag("onboarding-root").performScrollToNode(androidx.compose.ui.test.hasTestTag("onb-skip"))
        compose.onNodeWithTag("onb-skip").performClick()
        compose.waitForIdle()
        assertEquals(true, prefs.isDone())
        assertEquals(0, store.list().size, "跳过不落配置")
    }
}
