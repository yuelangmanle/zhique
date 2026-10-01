package com.zhique.core.apilot

import com.zhique.core.ai.Protocol

/**
 * V2/V1 Profile → 织雀 Provider 同构映射（Task 8.1）。
 *
 * 铁律（官方文档明令 + 规格 §4.9）：
 * - `provider.id` 优先且**绝不按 displayName 重猜**——`deepseek` 就存成 DeepSeek，
 *   `custom` 才是无法识别的通用服务；
 * - `protocol.id` 白名单直通；协议未知 → `openai_compatible` 兜底；
 * - provider.id 非白名单 → `custom`；`custom + openai_compatible` 是唯一兜底形态；
 * - scope 过滤：`grantedScopes` 未含的维度一律丢弃（`secret.api_key` 未勾 → 强制无 Key，
 *   即使 payload 里带了 Key 也剔除——纵深防御）；
 * - V1 无 provider/protocol：按官方域名推测（与 Apilot 自身口径一致），否则 custom+openai_compatible。
 */
object ProfileMapper {

    /** V2 provider.id 白名单（文档「V2 概念与规范」表）。 */
    val PROVIDER_IDS = setOf("deepseek", "openai", "anthropic", "google", "custom")

    /** V2 protocol.id 白名单（与织雀 Protocol 枚举同构）。 */
    val PROTOCOL_IDS = setOf(Protocol.OPENAI_COMPATIBLE, Protocol.ANTHROPIC_MESSAGES, Protocol.GOOGLE_GENAI)

    const val FALLBACK_PROVIDER = "custom"

    /** 映射后的 Provider 配置（App 层据此落 ProviderStore；apiKey=null 表示无 Key 方案）。 */
    data class MappedProvider(
        val providerId: String,
        val name: String,
        val protocol: String,
        val baseUrl: String,
        val apiKey: String?,
        val model: String?,
        val availableModels: List<String>,
        val grantedScopes: List<String>,
    ) {
        val hasKey: Boolean get() = !apiKey.isNullOrBlank()
    }

    // ---- V2 ----

    fun mapV2(result: PickResult): MappedProvider =
        mapV2Profile(result.apiProfile, result.grantedScopes, result.requestId)

    fun mapV2Profile(
        profile: ApiProfile,
        grantedScopes: List<String> = ApilotProtocol.DEFAULT_SCOPES,
        requestId: String? = null,
    ): MappedProvider {
        val providerId = profile.provider.id.takeIf { it in PROVIDER_IDS } ?: FALLBACK_PROVIDER
        val protocolId = profile.protocol.id.takeIf { it in PROTOCOL_IDS } ?: Protocol.OPENAI_COMPATIBLE
        val hasDefault = ApilotProtocol.SCOPE_MODELS_DEFAULT in grantedScopes
        val hasAll = ApilotProtocol.SCOPE_MODELS_ALL in grantedScopes
        val hasSecret = ApilotProtocol.SCOPE_SECRET_API_KEY in grantedScopes
        return MappedProvider(
            providerId = providerId,
            name = profile.connection.name.ifBlank { fallbackName(providerId, requestId) },
            protocol = protocolId,
            baseUrl = profile.connection.baseUrl,
            // 纵深防御：scope 未授予时即使 payload 带了 Key 也剔除
            apiKey = profile.secrets.apiKey?.takeIf { hasSecret && it.isNotBlank() },
            model = profile.models.selectedModel?.takeIf { hasDefault && it.isNotBlank() },
            availableModels = profile.models.availableModels.orEmpty().filter { it.isNotBlank() }
                .takeIf { hasAll } ?: emptyList(),
            grantedScopes = grantedScopes,
        )
    }

    // ---- V1（apiConfigs；V1 选择后总是回传 Key） ----

    fun mapV1(result: V1PickResult): MappedProvider = mapV1Config(result.apiConfig)

    fun mapV1Config(config: V1ApiConfig, requestId: String? = null): MappedProvider {
        val providerId = hostProvider(config.baseUrl)
        return MappedProvider(
            providerId = providerId,
            name = config.name.ifBlank { fallbackName(providerId, requestId) },
            protocol = Protocol.OPENAI_COMPATIBLE, // V1 时代只有 OpenAI 兼容形态
            baseUrl = config.baseUrl,
            apiKey = config.apiKey?.takeIf { it.isNotBlank() },
            model = config.selectedModel?.takeIf { it.isNotBlank() } ?: config.models.firstOrNull(),
            availableModels = config.models,
            grantedScopes = listOf(
                ApilotProtocol.SCOPE_CONNECTION,
                ApilotProtocol.SCOPE_MODELS_DEFAULT,
                ApilotProtocol.SCOPE_MODELS_ALL,
                ApilotProtocol.SCOPE_SECRET_API_KEY,
            ),
        )
    }

    // ---- 官方域名推测（仅 V1 / 推送时 provider.id 缺失场景使用） ----

    /**
     * 按 baseUrl 官方域名推测 provider.id（未指定时 Apilot 也这么做）。
     * 未识别 → [FALLBACK_PROVIDER]。
     */
    fun hostProvider(baseUrl: String): String {
        val host = runCatching { java.net.URI(baseUrl.trim()).host?.lowercase() }.getOrNull()
        return when {
            host == null -> FALLBACK_PROVIDER
            host.endsWith("deepseek.com") -> "deepseek"
            host.endsWith("openai.com") -> "openai"
            host.endsWith("anthropic.com") -> "anthropic"
            host.endsWith("googleapis.com") -> "google"
            else -> FALLBACK_PROVIDER
        }
    }

    private fun fallbackName(providerId: String, requestId: String?): String =
        if (requestId.isNullOrBlank()) "Apilot 方案（$providerId）" else "Apilot 方案 $requestId"
}
