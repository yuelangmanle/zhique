package com.zhique.core.apilot

import com.zhique.core.ai.Protocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Task 8.1：V2/V1 Profile → 织雀 Provider 同构映射（provider.id×protocol.id 直通、scope 过滤、不按显示名重猜）。 */
class ProfileMapperTest {

    private fun v2Profile(
        providerId: String,
        displayName: String? = null,
        protocolId: String = Protocol.OPENAI_COMPATIBLE,
        apiKey: String? = null,
        selectedModel: String? = null,
        availableModels: List<String>? = null,
        name: String = "DeepSeek Production",
        baseUrl: String = "https://api.deepseek.com/v1",
    ) = ApiProfile(
        connection = ProfileConnection(name = name, baseUrl = baseUrl),
        provider = ProfileProvider(id = providerId, displayName = displayName),
        protocol = ProfileProtocol(id = protocolId),
        models = ProfileModels(selectedModel = selectedModel, availableModels = availableModels),
        secrets = ProfileSecrets(apiKey = apiKey),
    )

    // ---- provider.id 直通 ----

    @Test
    fun deepseek保持deepseek不降级为custom() {
        val m = ProfileMapper.mapV2Profile(v2Profile("deepseek"))
        assertEquals("deepseek", m.providerId)
    }

    @Test
    fun openai_anthropic_google直通() {
        assertEquals("openai", ProfileMapper.mapV2Profile(v2Profile("openai", baseUrl = "https://api.openai.com/v1")).providerId)
        assertEquals("anthropic", ProfileMapper.mapV2Profile(v2Profile("anthropic", baseUrl = "https://api.anthropic.com")).providerId)
        assertEquals("google", ProfileMapper.mapV2Profile(v2Profile("google", baseUrl = "https://generativelanguage.googleapis.com")).providerId)
    }

    @Test
    fun 未知providerId归为custom() {
        val m = ProfileMapper.mapV2Profile(v2Profile("moonshot"))
        assertEquals("custom", m.providerId)
    }

    @Test
    fun displayName绝不用于重猜() {
        // provider.id=deepseek 但显示名冒充 OpenAI —— 必须仍按 provider.id 落库
        val m = ProfileMapper.mapV2Profile(v2Profile("deepseek", displayName = "OpenAI 官方"))
        assertEquals("deepseek", m.providerId)
    }

    // ---- protocol.id ----

    @Test
    fun anthropic_messages协议保留() {
        val m = ProfileMapper.mapV2Profile(v2Profile("anthropic", protocolId = Protocol.ANTHROPIC_MESSAGES))
        assertEquals(Protocol.ANTHROPIC_MESSAGES, m.protocol)
    }

    @Test
    fun 未知协议兜底openai_compatible() {
        val m = ProfileMapper.mapV2Profile(v2Profile("custom", protocolId = "chat_ui_v9"))
        assertEquals(Protocol.OPENAI_COMPATIBLE, m.protocol)
        assertEquals("custom", m.providerId)
    }

    @Test
    fun custom加openai_compatible是唯一兜底形态() {
        val m = ProfileMapper.mapV2Profile(v2Profile("unknown-vendor", protocolId = "weird"))
        assertEquals("custom" to Protocol.OPENAI_COMPATIBLE, m.providerId to m.protocol)
    }

    // ---- scope 过滤 ----

    @Test
    fun 无secret_scope则强制无Key即使payload带了() {
        val m = ProfileMapper.mapV2Profile(
            v2Profile("deepseek", apiKey = "sk-secret"),
            grantedScopes = listOf(ApilotProtocol.SCOPE_CONNECTION, ApilotProtocol.SCOPE_MODELS_DEFAULT),
        )
        assertFalse(m.hasKey)
        assertNull(m.apiKey)
    }

    @Test
    fun secret_scope在列表时Key保留() {
        val m = ProfileMapper.mapV2Profile(
            v2Profile("deepseek", apiKey = "sk-secret"),
            grantedScopes = ApilotProtocol.DEFAULT_SCOPES,
        )
        assertTrue(m.hasKey)
        assertEquals("sk-secret", m.apiKey)
    }

    @Test
    fun 无models_all则目录清空() {
        val m = ProfileMapper.mapV2Profile(
            v2Profile("deepseek", selectedModel = "deepseek-chat", availableModels = listOf("deepseek-chat", "deepseek-reasoner")),
            grantedScopes = listOf(ApilotProtocol.SCOPE_CONNECTION, ApilotProtocol.SCOPE_MODELS_DEFAULT, ApilotProtocol.SCOPE_SECRET_API_KEY),
        )
        assertTrue(m.availableModels.isEmpty())
        assertEquals("deepseek-chat", m.model)
    }

    @Test
    fun 无models_default则默认模型为空() {
        val m = ProfileMapper.mapV2Profile(
            v2Profile("deepseek", selectedModel = "deepseek-chat"),
            grantedScopes = listOf(ApilotProtocol.SCOPE_CONNECTION),
        )
        assertNull(m.model)
    }

    // ---- 连接字段 ----

    @Test
    fun 空白连接名回落兜底名() {
        val m = ProfileMapper.mapV2Profile(v2Profile("deepseek", name = " "))
        assertTrue(m.name.startsWith("Apilot 方案"))
    }

    // ---- V1 兼容 ----

    @Test
    fun v1选择结果解析映射() {
        val m = ProfileMapper.mapV1(
            V1PickResult(
                apiConfig = V1ApiConfig(
                    name = "Legacy OpenAI",
                    baseUrl = "https://api.openai.com/v1",
                    apiKey = "sk-legacy",
                    models = listOf("gpt-4.1", "gpt-4.1-mini"),
                    selectedModel = "gpt-4.1",
                ),
            ),
        )
        assertEquals("openai", m.providerId)
        assertEquals(Protocol.OPENAI_COMPATIBLE, m.protocol)
        assertEquals("gpt-4.1", m.model)
        assertTrue(m.hasKey)
        assertEquals(listOf("gpt-4.1", "gpt-4.1-mini"), m.availableModels)
    }

    @Test
    fun v1无selectedModel时取首个模型() {
        val m = ProfileMapper.mapV1Config(V1ApiConfig(name = "x", baseUrl = "https://api.deepseek.com/v1", models = listOf("deepseek-chat")))
        assertEquals("deepseek-chat", m.model)
    }

    @Test
    fun v1按官方域名推测provider() {
        assertEquals("deepseek", ProfileMapper.mapV1Config(V1ApiConfig(name = "a", baseUrl = "https://api.deepseek.com/v1")).providerId)
        assertEquals("anthropic", ProfileMapper.mapV1Config(V1ApiConfig(name = "b", baseUrl = "https://api.anthropic.com")).providerId)
        assertEquals("google", ProfileMapper.mapV1Config(V1ApiConfig(name = "c", baseUrl = "https://generativelanguage.googleapis.com")).providerId)
    }

    @Test
    fun v1未知域名归custom() {
        val m = ProfileMapper.mapV1Config(V1ApiConfig(name = "自建", baseUrl = "https://llm.corp.local/v1"))
        assertEquals("custom", m.providerId)
        assertEquals(Protocol.OPENAI_COMPATIBLE, m.protocol)
    }

    @Test
    fun v1导入payload批量映射() {
        val mapped = listOf(
            V1ApiConfig(name = "ds", baseUrl = "https://api.deepseek.com/v1", apiKey = "k1", models = listOf("m1")),
            V1ApiConfig(name = "other", baseUrl = "https://x.example/v1", models = listOf("m2")),
        ).map { ProfileMapper.mapV1Config(it) }
        assertEquals(2, mapped.size)
        assertEquals("deepseek" to true, mapped[0].providerId to mapped[0].hasKey)
        assertEquals("custom" to false, mapped[1].providerId to mapped[1].hasKey)
    }

    // ---- host 推测 ----

    @Test
    fun hostProvider对子域同样命中() {
        assertEquals("openai", ProfileMapper.hostProvider("https://api.openai.com/v1"))
        assertEquals("google", ProfileMapper.hostProvider("https://generativelanguage.googleapis.com/x"))
    }

    @Test
    fun hostProvider对非法URL安全() {
        assertEquals("custom", ProfileMapper.hostProvider("not a url"))
    }
}
