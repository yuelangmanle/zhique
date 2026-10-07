package com.zhique.core.apilot

import android.app.Activity
import android.content.Intent
import org.robolectric.RuntimeEnvironment
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Task 8.1：V2 桥 Intent 构建与结果解析（mock Intent/Result，Robolectric）。
 * 常量逐字对齐官方文档；取消语义按协议纪律建模。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApilotBridgeTest {

    private val resolver get() = RuntimeEnvironment.getApplication().contentResolver

    private fun bridge(packageName: String = "com.example.api_manager") =
        ApilotBridge(apilotPackage = packageName, requestId = { "request-42" })

    // ---- 常量与文档逐字一致 ----

    @Test
    fun 协议常量与官方文档逐字一致() {
        assertEquals("com.apilot.intent.action.IMPORT_API_CONFIGS", ApilotProtocol.ACTION_IMPORT)
        assertEquals("com.apilot.intent.action.PICK_API_CONFIG", ApilotProtocol.ACTION_PICK)
        assertEquals("application/vnd.apilot.api-configs+json", ApilotProtocol.MIME_IMPORT)
        assertEquals("application/vnd.apilot.api-profile+json", ApilotProtocol.MIME_PROFILE_RESULT)
        assertEquals("com.apilot.extra.API_CONFIGS_JSON", ApilotProtocol.EXTRA_CONFIGS_JSON)
        assertEquals("com.apilot.extra.API_CONFIG_JSON", ApilotProtocol.EXTRA_CONFIG_JSON)
        assertEquals("com.apilot.extra.SOURCE_NAME", ApilotProtocol.EXTRA_SOURCE_NAME)
        assertEquals("com.apilot.extra.REQUEST_ID", ApilotProtocol.EXTRA_REQUEST_ID)
        assertEquals("com.apilot.extra.SCHEMA_VERSION", ApilotProtocol.EXTRA_SCHEMA_VERSION)
        assertEquals("com.apilot.extra.REQUESTED_SCOPES", ApilotProtocol.EXTRA_REQUESTED_SCOPES)
        assertEquals("com.apilot.extra.RETURN_TRANSPORT", ApilotProtocol.EXTRA_RETURN_TRANSPORT)
        assertEquals("com.apilot.extra.SOURCE_SIGNATURE_SHA256", ApilotProtocol.EXTRA_SOURCE_SIGNATURE_SHA256)
        assertEquals("apilot://import", ApilotProtocol.DEEP_LINK)
    }

    @Test
    fun 默认包名来自BuildConfig可配置() {
        assertEquals("com.example.api_manager", ApilotBridge().apilotPackage)
        assertEquals("com.other.app", bridge("com.other.app").apilotPackage)
    }

    // ---- PICK intent ----

    @Test
    fun pickIntent携带全部约定extras() {
        val intent = bridge().buildPickIntent(requestIdValue = "request-42")
        assertEquals(ApilotProtocol.ACTION_PICK, intent.action)
        assertEquals("com.example.api_manager", intent.`package`)
        assertEquals("织雀", intent.getStringExtra(ApilotProtocol.EXTRA_SOURCE_NAME))
        assertEquals("request-42", intent.getStringExtra(ApilotProtocol.EXTRA_REQUEST_ID))
        assertEquals(2, intent.getIntExtra(ApilotProtocol.EXTRA_SCHEMA_VERSION, 0))
        assertEquals<List<String>?>(
            listOf("connection", "models.default", "models.all", "secret.api_key"),
            intent.getStringArrayListExtra(ApilotProtocol.EXTRA_REQUESTED_SCOPES),
        )
        assertEquals("auto", intent.getStringExtra(ApilotProtocol.EXTRA_RETURN_TRANSPORT))
    }

    @Test
    fun pickIntent默认自动生成请求id且scopes可收窄() {
        val a = ApilotBridge().buildPickIntent()
        val b = ApilotBridge().buildPickIntent()
        assertNotEquals(a.getStringExtra(ApilotProtocol.EXTRA_REQUEST_ID), b.getStringExtra(ApilotProtocol.EXTRA_REQUEST_ID))

        val narrow = bridge().buildPickIntent(scopes = listOf(ApilotProtocol.SCOPE_CONNECTION))
        assertEquals<List<String>?>(listOf("connection"), narrow.getStringArrayListExtra(ApilotProtocol.EXTRA_REQUESTED_SCOPES))
    }

    // ---- IMPORT intent ----

    @Test
    fun 小负载走JSONextra() {
        val payload = """{"schemaVersion":2,"apiProfiles":[]}"""
        val plan = bridge().buildImportIntent(payload, signatureSha256 = "AA:BB")
        assertFalse(plan.viaUri)
        assertEquals(payload, plan.intent.getStringExtra(ApilotProtocol.EXTRA_CONFIGS_JSON))
        assertNull(plan.intent.data)
        assertEquals(ApilotProtocol.ACTION_IMPORT, plan.intent.action)
        assertEquals("com.example.api_manager", plan.intent.`package`)
        assertEquals("AA:BB", plan.intent.getStringExtra(ApilotProtocol.EXTRA_SOURCE_SIGNATURE_SHA256))
        // Apilot 的 IMPORT filter 带 mimeType 约束：extra 分支缺 type 会被
        // ActivityStarter 直接中止（TV 实证「同步已取消」根因）
        assertEquals(ApilotProtocol.MIME_IMPORT, plan.intent.type)
    }

    @Test
    fun 大负载走contentURI并带读授权flag() {
        val payload = "x".repeat(ApilotProtocol.PAYLOAD_URI_THRESHOLD_BYTES + 1)
        val plan = bridge().buildImportIntent(payload, uriProvider = { json ->
            assertEquals(payload, json)
            android.net.Uri.parse("content://com.example.api_manager.zqfile/apilot/p.json")
        })
        assertTrue(plan.viaUri)
        assertEquals("content://com.example.api_manager.zqfile/apilot/p.json", plan.intent.data.toString())
        assertEquals(ApilotProtocol.MIME_IMPORT, plan.intent.type)
        assertTrue((plan.intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
        assertNull(plan.intent.getStringExtra(ApilotProtocol.EXTRA_CONFIGS_JSON))
    }

    @Test
    fun 大负载无uriProvider时显式抛错不回落extra() {
        val payload = "x".repeat(ApilotProtocol.PAYLOAD_URI_THRESHOLD_BYTES + 1)
        val e = assertFailsWith<IllegalArgumentException> {
            bridge().buildImportIntent(payload)
        }
        assertTrue(e.message!!.contains("负载过大且未配置 URI 通道"))
    }

    @Test
    fun payload构建符合V2schema() {
        val json = bridge().buildImportPayload(
            profiles = listOf(
                ApiProfile(
                    connection = ProfileConnection(name = "DeepSeek Production", baseUrl = "https://api.deepseek.com/v1"),
                    provider = ProfileProvider(id = "deepseek"),
                    protocol = ProfileProtocol(id = "openai_compatible"),
                    models = ProfileModels(selectedModel = "deepseek-chat"),
                    secrets = ProfileSecrets(apiKey = "sk-1"),
                ),
            ),
            selfPackageName = "com.zhique.runner",
            signatureSha256 = "AA:BB:CC",
        )
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(json) as kotlinx.serialization.json.JsonObject
        assertEquals("2", (obj["schemaVersion"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("com.zhique.runner", (obj["source"] as kotlinx.serialization.json.JsonObject)["packageName"]?.toString()?.removeSurrounding("\""))
        val profile = (obj["apiProfiles"] as kotlinx.serialization.json.JsonArray).single() as kotlinx.serialization.json.JsonObject
        assertEquals("deepseek", (((profile["provider"] as kotlinx.serialization.json.JsonObject)["id"]) as kotlinx.serialization.json.JsonPrimitive).content)
        val models = profile["models"] as kotlinx.serialization.json.JsonObject
        assertEquals("deepseek-chat", (models["selectedModel"] as kotlinx.serialization.json.JsonPrimitive).content)
        // null 字段整体省略（explicitNulls=false）：未给的 availableModels/catalogMode 不出现
        assertFalse("availableModels" in models)
        assertFalse("catalogMode" in models)
    }

    // ---- 结果解析：取消语义 ----

    @Test
    fun RESULT_CANCELED映射为取消而非失败() {
        val outcome = bridge().parsePickResult(Activity.RESULT_CANCELED, Intent(), resolver)
        assertIs<PickOutcome.Canceled>(outcome)
    }

    @Test
    fun RESULT_OK但intent为空为无效结果() {
        assertIs<PickOutcome.Invalid>(bridge().parsePickResult(Activity.RESULT_OK, null, resolver))
        assertIs<PickOutcome.Invalid>(bridge().parsePickResult(Activity.RESULT_OK, Intent(), resolver))
    }

    // ---- 结果解析：V2 extra / content URI ----

    private val v2Json = """
        {"schemaVersion":2,"requestId":"request-42","grantedScopes":["connection","models.default"],
         "apiProfile":{"connection":{"name":"DeepSeek Production","baseUrl":"https://api.deepseek.com/v1","environment":"production"},
         "provider":{"id":"deepseek","displayName":"DeepSeek"},"protocol":{"id":"openai_compatible"},
         "models":{"selectedModel":"deepseek-chat","catalogMode":"remote","source":"refreshed"},"secrets":{},
         "origin":{"appName":"Apilot","trustLevel":"system_package"}}}
    """.trimIndent()

    @Test
    fun v2extraJSON解析成功() {
        val intent = Intent().putExtra(ApilotProtocol.EXTRA_CONFIG_JSON, v2Json)
        val outcome = bridge().parsePickResult(Activity.RESULT_OK, intent, resolver)
        val v2 = assertIs<PickOutcome.V2>(outcome)
        assertEquals("request-42", v2.result.requestId)
        assertEquals(listOf("connection", "models.default"), v2.result.grantedScopes)
        assertEquals("deepseek", v2.result.apiProfile.provider.id)
        assertEquals("deepseek-chat", v2.result.apiProfile.models.selectedModel)
        assertNull(v2.result.apiProfile.secrets.apiKey)
    }

    @Test
    fun requestId匹配则通过_不匹配判无效() {
        val intent = Intent().putExtra(ApilotProtocol.EXTRA_CONFIG_JSON, v2Json)
        val match = bridge().parsePickResult(Activity.RESULT_OK, intent, resolver, expectedRequestId = "request-42")
        assertIs<PickOutcome.V2>(match)

        val mismatch = bridge().parsePickResult(Activity.RESULT_OK, intent, resolver, expectedRequestId = "other-request")
        assertIs<PickOutcome.Invalid>(mismatch) // 防串话：不是本次请求的回传
    }

    @Test
    fun 无connection_scope整条拒收() {
        val noConnection = """
            {"schemaVersion":2,"requestId":"request-42","grantedScopes":["models.default","secret.api_key"],
             "apiProfile":{"connection":{"name":"X","baseUrl":"https://api.deepseek.com/v1"},
             "provider":{"id":"deepseek"},"protocol":{"id":"openai_compatible"},"secrets":{}}}
        """.trimIndent()
        val intent = Intent().putExtra(ApilotProtocol.EXTRA_CONFIG_JSON, noConnection)
        val outcome = bridge().parsePickResult(Activity.RESULT_OK, intent, resolver)
        assertIs<PickOutcome.Invalid>(outcome)
    }

    @Test
    fun 回传超1MiB判无效() {
        val oversized = " ".repeat(ApilotProtocol.RESULT_MAX_BYTES + 1)
        val intent = Intent().putExtra(ApilotProtocol.EXTRA_CONFIG_JSON, oversized)
        val outcome = bridge().parsePickResult(Activity.RESULT_OK, intent, resolver)
        assertIs<PickOutcome.Invalid>(outcome)
    }

    @Test
    fun 默认URI读取器1MiB封顶() {
        val uri = android.net.Uri.parse("content://mock.apilot/big.json")
        val shadow = org.robolectric.Shadows.shadowOf(resolver)
        // 正常大小：读出全文
        shadow.registerInputStream(uri, v2Json.byteInputStream(Charsets.UTF_8))
        assertEquals(v2Json, ApilotBridge.defaultReadUri(uri, resolver))
        // 超限：返回 null（判无效）
        shadow.registerInputStream(uri, "x".repeat(ApilotProtocol.RESULT_MAX_BYTES + 1).byteInputStream())
        assertNull(ApilotBridge.defaultReadUri(uri, resolver))
    }

    @Test
    fun v2contentURI立即读取且不持久化() {
        // 模拟 Apilot 的临时只读 URI：读入即返回；桥不保存 URI 引用、不落盘
        val uri = android.net.Uri.parse("content://mock.apilot/payload.json")
        var readUri: android.net.Uri? = null
        val intent = Intent().setData(uri)
        val outcome = bridge().parsePickResult(Activity.RESULT_OK, intent, resolver) { u, _ ->
            readUri = u
            v2Json
        }
        val v2 = assertIs<PickOutcome.V2>(outcome)
        assertEquals("deepseek", v2.result.apiProfile.provider.id)
        assertEquals(uri, readUri)
    }

    @Test
    fun contentURI读不到内容按无效结果处理() {
        val uri = android.net.Uri.parse("content://mock.apilot/gone.json")
        val intent = Intent().setData(uri)
        val outcome = bridge().parsePickResult(Activity.RESULT_OK, intent, resolver) { _, _ -> null }
        assertIs<PickOutcome.Invalid>(outcome)
    }

    // ---- 结果解析：V1 兼容 ----

    @Test
    fun v1回传解析成功且带Key() {
        val v1 = """
            {"schemaVersion":1,"requestId":"r1","apiConfig":{"name":"Legacy OpenAI",
             "baseUrl":"https://api.openai.com/v1","apiKey":"sk-legacy",
             "models":["gpt-4.1","gpt-4.1-mini"],"selectedModel":"gpt-4.1"}}
        """.trimIndent()
        val intent = Intent().putExtra(ApilotProtocol.EXTRA_CONFIG_JSON, v1)
        val outcome = bridge().parsePickResult(Activity.RESULT_OK, intent, resolver)
        val v1Out = assertIs<PickOutcome.V1>(outcome)
        assertEquals("sk-legacy", v1Out.result.apiConfig.apiKey)
        assertEquals(2, v1Out.result.apiConfig.models.size)
    }

    // ---- 结果解析：malformed ----

    @Test
    fun schemaVersion非1或2为malformed() {
        val intent = Intent().putExtra(ApilotProtocol.EXTRA_CONFIG_JSON, """{"schemaVersion":3,"x":1}""")
        val outcome = bridge().parsePickResult(Activity.RESULT_OK, intent, resolver)
        assertIs<PickOutcome.Malformed>(outcome)
    }

    @Test
    fun 损坏JSON为malformed且文案固定不带输入片段() {
        val broken = """{"schemaVersion":1,"apiConfig":{"name":"密 sk-abcfrag"}}{not-json"""
        val intent = Intent().putExtra(ApilotProtocol.EXTRA_CONFIG_JSON, broken)
        val outcome = bridge().parsePickResult(Activity.RESULT_OK, intent, resolver)
        val m = assertIs<PickOutcome.Malformed>(outcome)
        // 固定文案：不回显序列化异常原文（防 payload/Key 片段进 UI/日志）
        assertEquals(ApilotBridge.MALFORMED_PARSE, m.reason)
        assertFalse(m.reason.contains("sk-abcfrag"))
    }

    // ---- 签名 ----

    @Test
    fun 本包签名sha256为冒号分隔十六进制() {
        val context = RuntimeEnvironment.getApplication()
        val sha = ApilotBridge.packageSignatureSha256(context.packageManager, context.packageName)
        // Robolectric 测试包可能无签名配置 → 允许 null；有值时必须是 64 字节 hex 的冒号分隔形态
        sha?.let {
            assertEquals(95, it.length) // 32 字节 → 32×2 hex + 31 个冒号
            assertTrue(it.matches(Regex("([0-9A-F]{2}:){31}[0-9A-F]{2}")))
        }
    }

    // ---- 协议纪律常量 ----

    @Test
    fun 应用锁PIN停留不算无响应_选择超时已放宽() {
        // 文档：应用锁（1.24.0+）跳转 Apilot 先停留 PIN 解锁页 —— 常规看门狗时限不得判失败
        assertTrue(ApilotProtocol.PICK_TIMEOUT_MS >= 60_000L)
        assertEquals(10, ApilotProtocol.RESULT_URI_TTL_MINUTES)
        assertEquals(64 * 1024, ApilotProtocol.PAYLOAD_URI_THRESHOLD_BYTES)
    }

    @Test
    fun isInstalled对缺失包返回false() {
        val context = RuntimeEnvironment.getApplication()
        assertFalse(ApilotBridge.isInstalled(context, "com.definitely.not.installed"))
    }
}
