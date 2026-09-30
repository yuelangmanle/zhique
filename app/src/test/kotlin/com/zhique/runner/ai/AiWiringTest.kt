package com.zhique.runner.ai

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.zhique.core.ai.AgentRole
import com.zhique.core.ai.ModelCatalog
import com.zhique.core.ai.VisionRoleError
import com.zhique.core.common.crypto.CryptoStore
import com.zhique.core.common.crypto.KeyProvider
import com.zhique.runner.AppContainer
import com.zhique.runner.settings.ProviderConfig
import com.zhique.runner.settings.RoleBindingStore
import com.zhique.runner.settings.RoleBindings
import java.io.File
import javax.crypto.KeyGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

/** Task 4.4 接线：角色路由 → 调用通道 + UsageMeter 挂点 + 上下文窗口真值。 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiWiringTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeKeyProvider : KeyProvider {
        private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        override fun masterKey() = key
    }

    private fun newContainer(): AppContainer {
        val app = RobolectricContext.app
        return AppContainer(app, keyProvider = FakeKeyProvider())
    }

    private fun providerConfig(keyCipher: String = "") = ProviderConfig(
        id = "p1",
        name = "测试服务商",
        protocol = "openai_compatible",
        baseUrl = "https://example.invalid",
        keyCipher = keyCipher,
        model = "deepseek-chat",
    )

    @Test
    fun `未配置服务商返回null`() = runTest {
        val container = newContainer()
        assertNull(AiWiring(container).wire(AgentRole.CHAT))
    }

    @Test
    fun `wire按目录解析上限与上下文真值并挂UsageMeter`() = runTest {
        val container = newContainer()
        container.providerStore.upsert(
            providerConfig(keyCipher = container.providerStore.encryptKey("k")),
        )
        container.roleBindingStore.bind(
            AgentRole.CHAT.name,
            com.zhique.core.ai.ModelRef("p1", "deepseek-chat"),
        )
        val wired = AiWiring(container).wire(AgentRole.CHAT, projectId = "proj1")
        assertTrue(wired != null)
        assertEquals("deepseek-chat", wired.model)
        assertEquals(8_192, wired.template.maxTokens, "目录修正 deepseek-chat 输出上限")
        assertEquals(65_536, wired.contextWindow, "上下文窗口目录真值")
        assertFalse(wired.vision, "deepseek-chat 是纯文本模型")
        assertEquals(ModelCatalog.FAST_LOOP_MAX_OUTPUT, wired.fastTemplate.maxTokens, "快循环 4096")
        // UsageMeter.record 挂点（M3 遗留接线 b）
        wired.recordUsage(123)
        assertEquals(123, container.usageMeter.providerTotal("p1"))
        assertEquals(123, container.usageMeter.projectTotal("proj1"))
    }

    @Test
    fun `视觉槽误配纯文本模型硬拦`() = runTest {
        val container = newContainer()
        container.providerStore.upsert(
            providerConfig(keyCipher = container.providerStore.encryptKey("k")),
        )
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(UnconfinedTestDispatcher()),
            produceFile = { File(tmp.root, "roles.preferences_pb") },
        )
        container.roleBindingStore.bind(
            AgentRole.VISION.name,
            com.zhique.core.ai.ModelRef("p1", "deepseek-chat"),
        )
        assertFailsWith<VisionRoleError> {
            AiWiring(container).wire(AgentRole.VISION)
        }
    }

    @Test
    fun `手动覆盖modality生效`() = runTest {
        val container = newContainer()
        container.providerStore.upsert(
            providerConfig(keyCipher = container.providerStore.encryptKey("k"))
                .copy(modalityManual = "vision", model = "mystery-model"),
        )
        val wired = AiWiring(container).wire(AgentRole.VISION)
        assertTrue(wired != null && wired.vision, "手动 vision 覆盖：未知模型也认")
    }
}

private object RobolectricContext {
    val app: Application get() = org.robolectric.RuntimeEnvironment.getApplication() as Application
}
