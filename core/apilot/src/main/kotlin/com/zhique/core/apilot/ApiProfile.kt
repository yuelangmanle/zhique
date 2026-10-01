package com.zhique.core.apilot

import kotlinx.serialization.Serializable

/**
 * Apilot V2 语义化 API Profile（文档「V2 概念与规范」）：
 * 连接与服务商语义分离；provider.id × protocol.id 与织雀 Provider 层同构。
 *
 * 序列化纪律：encodeDefaults=true + explicitNulls=false——
 * `environment` 恒出现（与文档示例一致），null 字段整体省略（如未授权的 secrets）。
 */
@Serializable
data class ProfileSource(
    val appName: String,
    val packageName: String? = null,
    val signatureSha256: String? = null,
)

@Serializable
data class ProfileConnection(
    val name: String,
    val baseUrl: String,
    val environment: String = "production",
    val tags: List<String> = emptyList(),
)

/**
 * 提供商身份：[id] ∈ deepseek/openai/anthropic/google/custom。
 * [displayName] 仅展示用——调用方绝不按它重猜提供商（文档明令）。
 */
@Serializable
data class ProfileProvider(val id: String, val displayName: String? = null)

/** 协议 id ∈ openai_compatible / anthropic_messages / google_genai。 */
@Serializable
data class ProfileProtocol(val id: String)

@Serializable
data class ProfileModels(
    val selectedModel: String? = null,
    val availableModels: List<String>? = null,
    val catalogMode: String? = null,
    val source: String? = null,
)

@Serializable
data class ProfileSecrets(val apiKey: String? = null)

@Serializable
data class ProfileOrigin(val appName: String? = null, val trustLevel: String? = null)

@Serializable
data class ApiProfile(
    val connection: ProfileConnection,
    val provider: ProfileProvider,
    val protocol: ProfileProtocol,
    val models: ProfileModels = ProfileModels(),
    val secrets: ProfileSecrets = ProfileSecrets(),
    val origin: ProfileOrigin = ProfileOrigin(),
)

/** 推送到 Apilot 的 V2 导入 payload（apiProfiles；不要求模型列表）。 */
@Serializable
data class ApiProfilesPayload(
    val schemaVersion: Int = ApilotProtocol.SCHEMA_V2,
    val source: ProfileSource,
    val apiProfiles: List<ApiProfile>,
)

/** V2 选择结果信封：grantedScopes 决定哪些字段真的有值。 */
@Serializable
data class PickResult(
    val schemaVersion: Int,
    val requestId: String? = null,
    val grantedScopes: List<String> = emptyList(),
    val apiProfile: ApiProfile,
)

// ---- V1 兼容（apiConfigs 原始形态；V1 选择方案后回传 API Key） ----

@Serializable
data class V1ApiConfig(
    val name: String,
    val baseUrl: String,
    val apiKey: String? = null,
    val models: List<String> = emptyList(),
    val environment: String? = null,
    val selectedModel: String? = null,
)

/** V1 选择结果：`apiConfig.models` 与第一个 `selectedModel`（doc「V1 兼容」）。 */
@Serializable
data class V1PickResult(
    val schemaVersion: Int = ApilotProtocol.SCHEMA_V1,
    val requestId: String? = null,
    val apiConfig: V1ApiConfig,
)
