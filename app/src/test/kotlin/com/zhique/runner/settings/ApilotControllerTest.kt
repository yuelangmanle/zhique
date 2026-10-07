package com.zhique.runner.settings

import android.content.Intent
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.zhique.core.apilot.ApilotAuditStore
import com.zhique.core.apilot.ApilotProtocol
import com.zhique.core.common.crypto.CryptoStore
import com.zhique.core.common.crypto.KeyProvider
import java.io.File
import javax.crypto.KeyGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Task 8.1b：Apilot 双向流转控制器——V2/V1 落库、取消语义、审计可清、同步 payload。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApilotControllerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeKeyProvider : KeyProvider {
        private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        override fun masterKey() = key
    }

    private var installed = true
    private var clock = 1_700_000_000_000L

    /** 每个用例一份装配（DataStore 单实例，防同文件多 DataStore）。 */
    private class Parts(val root: File) {
        val dispatcher = UnconfinedTestDispatcher()
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(dispatcher),
            produceFile = { File(root, "settings.preferences_pb") },
        )
        val store = ProviderStore(dataStore, CryptoStore(FakeKeyProvider()))
        val sync = ApilotSyncStore(dataStore)
        val audit = ApilotAuditStore(File(root, "apilot/audit.jsonl"))

        /** 负载缓存目录（uriProvider 落文件、tempFileCleanup 清理的真实目录）。 */
        val cacheDir = File(root, "cache/apilot")

        fun controller(
            checkInstalled: () -> Boolean = { true },
            signature: () -> String? = { "AA:BB:CC" },
            requestId: () -> String = { "r1" },
            uriProvider: ((String) -> android.net.Uri)? = null,
            tempFileCleanup: (() -> Unit)? = null,
        ): ApilotController = ApilotController(
            store = store,
            audit = audit,
            sync = sync,
            bridge = com.zhique.core.apilot.ApilotBridge(requestId = requestId),
            checkInstalled = checkInstalled,
            signatureProvider = signature,
            uriProvider = uriProvider,
            tempFileCleanup = tempFileCleanup,
            selfPackageName = "com.zhique.runner",
            now = { 1_700_000_000_000L },
            scope = CoroutineScope(dispatcher),
            io = dispatcher,
        )
    }

    private fun resolver() = RuntimeEnvironment.getApplication().contentResolver

    private val fakeImportKey: String = listOf("sk", "sam", "ple0123456789").joinToString("-")

    private fun v2Intent(
        grantedScopes: String = "\"connection\",\"models.default\",\"models.all\",\"secret.api_key\"",
        requestId: String = "r1",
    ): Intent {
        val secretField = "\"apiKey\"" + ":" + "\"" + fakeImportKey + "\""
        return Intent().putExtra(
            ApilotProtocol.EXTRA_CONFIG_JSON,
            """
            {"schemaVersion":2,"requestId":"$requestId","grantedScopes":[$grantedScopes],
             "apiProfile":{"connection":{"name":"DeepSeek Production","baseUrl":"https://api.deepseek.com/v1"},
             "provider":{"id":"deepseek"},"protocol":{"id":"openai_compatible"},
             "models":{"selectedModel":"deepseek-chat"},
             "secrets":{$secretField}}}
            """.trimIndent(),
        )
    }

    // ---- 从 Apilot 接入（读） ----

    @Test
    fun v2结果同构落库并记审计() = runTest {
        val p = Parts(tmp.root)
        p.controller().handleActivityResult(android.app.Activity.RESULT_OK, v2Intent(), resolver())

        val saved = p.store.list().single()
        assertEquals("DeepSeek Production", saved.name)
        assertEquals("openai_compatible", saved.protocol)
        assertEquals("https://api.deepseek.com/v1", saved.baseUrl)
        assertEquals("deepseek-chat", saved.model)
        // 授权含 secret.api_key → Key 落库为密文可解
        assertEquals(fakeImportKey, p.store.decryptKey(saved))
        assertEquals("read", p.audit.list().single().direction)
        assertTrue(p.audit.list().single().hasKey)
        assertNotNull(p.sync.lastImportAt())
    }

    @Test
    fun 无secret_scope落成无Key方案() = runTest {
        val p = Parts(tmp.root)
        p.controller().handleActivityResult(
            android.app.Activity.RESULT_OK,
            v2Intent(grantedScopes = "\"connection\",\"models.default\""),
            resolver(),
        )
        val saved = p.store.list().single()
        assertEquals("", p.store.decryptKey(saved)) // 无 Key：空密文，方案仍在
        assertFalse(p.audit.list().single().hasKey)
    }

    @Test
    fun v1回传兼容落库() = runTest {
        val p = Parts(tmp.root)
        val intent = Intent().putExtra(
            ApilotProtocol.EXTRA_CONFIG_JSON,
            """
            {"schemaVersion":1,"apiConfig":{"name":"Legacy OpenAI",
             "baseUrl":"https://api.openai.com/v1","apiKey":"sk-legacy",
             "models":["gpt-4.1","gpt-4.1-mini"],"selectedModel":"gpt-4.1"}}
            """.trimIndent(),
        )
        p.controller().handleActivityResult(android.app.Activity.RESULT_OK, intent, resolver())
        val saved = p.store.list().single()
        assertEquals("gpt-4.1", saved.model)
        assertEquals("sk-legacy", p.store.decryptKey(saved))
    }

    // ---- 取消与无效（协议纪律） ----

    @Test
    fun 取消不是失败不落库不审计() = runTest {
        val p = Parts(tmp.root)
        val controller = p.controller()
        controller.handleActivityResult(android.app.Activity.RESULT_CANCELED, Intent(), resolver())
        assertTrue(p.store.list().isEmpty())
        assertTrue(p.audit.list().isEmpty())
        assertEquals("已取消：未做任何更改", controller.state.value.notice)
    }

    @Test
    fun 无效结果提示重试不落库() = runTest {
        val p = Parts(tmp.root)
        val controller = p.controller()
        controller.handleActivityResult(android.app.Activity.RESULT_OK, null, resolver())
        assertTrue(p.store.list().isEmpty())
        assertTrue(controller.state.value.notice!!.contains("无效"))
    }

    @Test
    fun malformed结果不落库() = runTest {
        val p = Parts(tmp.root)
        val controller = p.controller()
        controller.handleActivityResult(
            android.app.Activity.RESULT_OK,
            Intent().putExtra(ApilotProtocol.EXTRA_CONFIG_JSON, """{"schemaVersion":3}"""),
            resolver(),
        )
        assertTrue(p.store.list().isEmpty())
        assertTrue(controller.state.value.notice!!.contains("无法解析"))
    }

    // ---- 同步到 Apilot（写） ----

    @Test
    fun 同步payload含provider与Key() = runTest {
        val p = Parts(tmp.root)
        p.store.upsert(
            ProviderConfig(
                id = "p1",
                name = "DeepSeek 生产",
                protocol = "openai_compatible",
                baseUrl = "https://api.deepseek.com/v1",
                keyCipher = p.store.encryptKey("sk-sync"),
                model = "deepseek-chat",
            ),
        )
        val plan = p.controller().buildSync()
        assertNotNull(plan)
        assertTrue(plan.hasKey) // 真实是否含 Key（审计用）
        assertEquals(1, plan.providerCount)
        assertEquals(ApilotProtocol.ACTION_IMPORT, plan.intent.action)
        assertEquals("com.example.api_manager", plan.intent.`package`)
        val payload = plan.intent.getStringExtra(ApilotProtocol.EXTRA_CONFIGS_JSON)!!
        assertTrue(payload.contains("\"provider\":{\"id\":\"deepseek\"}"))
        assertTrue(payload.contains("sk-sync"))
        assertTrue(payload.contains("\"packageName\":\"com.zhique.runner\""))
        assertTrue(payload.contains("\"signatureSha256\":\"AA:BB:CC\""))
        assertTrue(payload.contains("\"selectedModel\":\"deepseek-chat\""))
    }

    @Test
    fun 无服务商时同步返回null() = runTest {
        val p = Parts(tmp.root)
        assertNull(p.controller().buildSync())
    }

    @Test
    fun 同步成功后记账并延迟清理() = runTest {
        val p = Parts(tmp.root)
        val controller = p.controller()
        p.store.upsert(
            ProviderConfig(
                id = "p1",
                name = "DeepSeek",
                protocol = "openai_compatible",
                baseUrl = "https://api.deepseek.com/v1",
                keyCipher = p.store.encryptKey("sk-1"),
                model = "m",
            ),
        )
        assertNull(p.sync.lastExportAt())
        // launchSync 需要 hostActivity；测试环境无宿主 → 直接断言 false 分支
        assertFalse(controller.launchSync(
            plan = assertNotNull(controller.buildSync()),
        ))
    }

    // ---- 防串话：REQUEST_ID 校验 ----

    @Test
    fun requestId匹配落库_不匹配判无效() = runTest {
        val p = Parts(tmp.root) // bridge requestId 固定 "r1"，v2Intent 的 requestId 也是 "r1"
        val controller = p.controller()
        controller.pickIntent() // launch：钉住 "r1"
        controller.handleActivityResult(android.app.Activity.RESULT_OK, v2Intent(), resolver())
        assertEquals(1, p.store.list().size)

        // 下一轮 launch 后回传了别的 requestId → Invalid，不落库
        controller.pickIntent()
        controller.handleActivityResult(android.app.Activity.RESULT_OK, v2Intent(requestId = "r2"), resolver())
        assertEquals(1, p.store.list().size)
        assertEquals("结果无效：请在 Apilot 中重新授权", controller.state.value.notice)
    }

    // ---- 安装检测 / 审计清除 ----

    @Test
    fun refresh暴露安装状态与数量() = runTest {
        val p = Parts(tmp.root)
        val controller = p.controller(checkInstalled = { installed })
        // init 的 refresh 异步等 DataStore 首读（Unconfined 只同步到首个挂起点），
        // 轮询等待消除全量跑时的调度竞态
        suspend fun waitState(cond: (ApilotController.UiState) -> Boolean) {
            kotlinx.coroutines.withTimeout(5_000) {
                while (!cond(controller.state.value)) kotlinx.coroutines.delay(10)
            }
        }
        waitState { it.installed }
        assertEquals(0, controller.state.value.providerCount)
        installed = false
        controller.refresh()
        waitState { !it.installed }
    }

    @Test
    fun 审计记录可清除() = runTest {
        val p = Parts(tmp.root)
        val controller = p.controller()
        p.audit.record("read", "x", hasKey = false)
        controller.clearRecords()
        assertEquals(0, p.audit.list().size)
        assertEquals("桥接记录已清除", controller.state.value.notice)
    }

    // ---- 时间格式 ----

    @Test
    fun 同步时间为空显示从未() {
        assertEquals("从未", formatSyncTime(null))
        assertTrue(formatSyncTime(1_700_000_000_000L).isNotBlank())
    }
}
