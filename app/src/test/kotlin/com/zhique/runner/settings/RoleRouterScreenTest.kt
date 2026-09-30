package com.zhique.runner.settings

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.zhique.core.ai.AgentRole
import com.zhique.core.ai.Preset
import java.io.File
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
 * 角色路由屏（Task 3.6）：预设一键切换五槽联动、视觉槽纯文本硬拦、绑定持久化。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoleRouterScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newStore(): RoleBindingStore {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(UnconfinedTestDispatcher()),
            produceFile = { File(tmp.root, "roles.preferences_pb") },
        )
        return RoleBindingStore(dataStore)
    }

    private fun scrollTo(tag: String) {
        compose.onNodeWithTag("roles-root").performScrollToNode(hasTestTag(tag))
    }

    @Test
    fun `预设切换联动五槽默认`() = runTest {
        val store = newStore()
        val scope = CoroutineScope(UnconfinedTestDispatcher())
        compose.setContent {
            com.zhique.runner.ui.theme.ZqTheme {
                RoleRouterScreen(store = store, defaultProviderId = "p-default", scope = scope)
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("slot-current-CHAT").assertTextContains("gpt-4o", substring = true) // BALANCED 对话伙伴默认
        compose.onNodeWithTag("slot-current-FAST_LOOP").assertTextContains("deepseek-chat", substring = true)
        compose.onNodeWithTag("preset-${Preset.QUALITY.name}").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("slot-current-CHAT").assertTextContains("gpt-5", substring = true)
        compose.onNodeWithTag("slot-current-AGENT_MAIN").assertTextContains("claude-sonnet-4", substring = true)
        assertEquals("QUALITY", store.current().preset)
    }

    @Test
    fun `视觉槽绑纯文本模型硬拦_绑vision模型通过`() = runTest {
        val store = newStore()
        val scope = CoroutineScope(UnconfinedTestDispatcher())
        compose.setContent {
            com.zhique.runner.ui.theme.ZqTheme {
                RoleRouterScreen(store = store, defaultProviderId = "p-default", scope = scope)
            }
        }
        compose.waitForIdle()
        val inputTag = "slot-input-${AgentRole.VISION.name}"
        val saveTag = "slot-save-${AgentRole.VISION.name}"
        scrollTo(inputTag)
        compose.onNodeWithTag(inputTag).performTextClearance()
        compose.onNodeWithTag(inputTag).performTextInput("deepseek-chat")
        compose.onNodeWithTag(saveTag).performClick()
        compose.waitForIdle()
        scrollTo("slot-error-${AgentRole.VISION.name}")
        compose.onNodeWithTag("slot-error-${AgentRole.VISION.name}")
            .assertTextContains("视觉槽必须选择 vision 模型", substring = true)
        assertTrue(store.current().roles[AgentRole.VISION.name] == null, "硬拦不得落库")

        scrollTo(inputTag)
        compose.onNodeWithTag(inputTag).performTextClearance()
        compose.onNodeWithTag(inputTag).performTextInput("gpt-4o")
        compose.onNodeWithTag(saveTag).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("slot-error-${AgentRole.VISION.name}").assertDoesNotExist()
        assertEquals("gpt-4o", store.current().roles[AgentRole.VISION.name]?.model)
    }

    @Test
    fun `常规槽绑定落库`() = runTest {
        val store = newStore()
        val scope = CoroutineScope(UnconfinedTestDispatcher())
        compose.setContent {
            com.zhique.runner.ui.theme.ZqTheme {
                RoleRouterScreen(store = store, defaultProviderId = "p-default", scope = scope)
            }
        }
        compose.waitForIdle()
        val inputTag = "slot-input-${AgentRole.FAST_LOOP.name}"
        scrollTo(inputTag)
        compose.onNodeWithTag(inputTag).performTextClearance()
        compose.onNodeWithTag(inputTag).performTextInput("deepseek-chat")
        compose.onNodeWithTag("slot-save-${AgentRole.FAST_LOOP.name}").performClick()
        compose.waitForIdle()
        assertEquals("deepseek-chat", store.current().roles[AgentRole.FAST_LOOP.name]?.model)
    }
}
