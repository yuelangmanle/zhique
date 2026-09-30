package com.zhique.runner.onboarding

import com.zhique.core.ai.Protocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** 从粘贴的 API 配置识别出的预填草稿（字段缺失为 null，交给用户补齐）。 */
data class ApiDraft(
    val protocol: String?,
    val baseUrl: String?,
    val apiKey: String?,
    val model: String?,
) {
    val hasSomething: Boolean get() = baseUrl != null || apiKey != null || model != null
}

/**
 * 粘贴识别 → Provider 表单预填（X4 步骤 1）。
 * 支持：apiProfiles JSON、单配置 JSON（baseUrl/apiKey/model 键族）、cURL 命令、
 * 裸 Bearer/sk- 令牌 + URL 文本。识别失败返回全 null 草稿（不猜、不报错）。
 */
object ApiConfigParser {

    private val json = Json { ignoreUnknownKeys = true }

    private val BASE_KEYS = setOf("baseUrl", "base_url", "apiBase", "endpoint", "url")
    private val KEY_KEYS = setOf("apiKey", "api_key", "key", "secret.api_key", "secretKey")
    private val MODEL_KEYS = setOf("model", "models.default", "model_id", "modelId", "defaultModel", "default")

    fun parse(raw: String): ApiDraft {
        val text = raw.trim()
        if (text.isEmpty()) return ApiDraft(null, null, null, null)

        val fromJson = parseJson(text)
        val fromCurl = parseCurl(text)
        val fallback = parseFallback(text)

        val baseUrl = fromJson.baseUrl ?: fromCurl.baseUrl ?: fallback.baseUrl
        val apiKey = fromJson.apiKey ?: fromCurl.apiKey ?: fallback.apiKey
        val model = fromJson.model ?: fromCurl.model
        val protocol = guessProtocol(baseUrl)
        return ApiDraft(protocol, baseUrl?.let(::normalizeBase), apiKey, model)
    }

    private fun parseJson(text: String): ApiDraft {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return ApiDraft(null, null, null, null)
        // apiProfiles 数组：取第一条
        val profile = (root["apiProfiles"] as? JsonArray)?.firstOrNull() as? JsonObject
            ?: (root["providers"] as? JsonArray)?.firstOrNull() as? JsonObject
        val source = profile ?: root
        return ApiDraft(
            protocol = null,
            baseUrl = source.findString(BASE_KEYS),
            apiKey = source.findString(KEY_KEYS),
            model = source.findString(MODEL_KEYS),
        )
    }

    private fun JsonObject.findString(keys: Set<String>): String? {
        for ((k, v) in this) {
            if (k in keys && v is JsonPrimitive && !v.isStringOrNullBlank()) return v.content
        }
        // 嵌套一层（如 secret.api_key → {"secret":{"api_key":..}}）
        for ((_, v) in this) {
            if (v is JsonObject) v.findString(keys)?.let { return it }
        }
        return null
    }

    private fun JsonPrimitive.isStringOrNullBlank(): Boolean = content.isBlank() || content == "-"

    private val CURL_URL = Regex("""https?://[^\s"']+""")
    private val CURL_MODEL = Regex(""""model"\s*:\s*"([^"]+)"""")
    private val BEARER = Regex("""Bearer\s+([A-Za-z0-9._\-]{8,})""")
    private val SK_KEY = Regex("""(sk-[A-Za-z0-9_\-]{8,})""")

    private fun parseCurl(text: String): ApiDraft {
        if (!text.startsWith("curl")) return ApiDraft(null, null, null, null)
        val url = CURL_URL.find(text)?.value
        val key = BEARER.find(text)?.groupValues?.getOrNull(1) ?: SK_KEY.find(text)?.groupValues?.getOrNull(1)
        val model = CURL_MODEL.find(text)?.groupValues?.getOrNull(1)
        return ApiDraft(null, url, key, model)
    }

    private fun parseFallback(text: String): ApiDraft {
        val url = CURL_URL.find(text)?.value
        val key = BEARER.find(text)?.groupValues?.getOrNull(1) ?: SK_KEY.find(text)?.groupValues?.getOrNull(1)
        return ApiDraft(null, url, key, null)
    }

    internal fun guessProtocol(baseUrl: String?): String? = when {
        baseUrl == null -> null
        baseUrl.contains("anthropic", ignoreCase = true) -> Protocol.ANTHROPIC_MESSAGES
        baseUrl.contains("generativelanguage", ignoreCase = true) ||
            baseUrl.contains("gemini", ignoreCase = true) -> Protocol.GOOGLE_GENAI
        else -> Protocol.OPENAI_COMPATIBLE
    }

    /** URL 规范化：剥掉 /chat/completions、/v1 尾巴与查询串，保留 scheme+host（+首个路径段）。 */
    internal fun normalizeBase(url: String): String {
        var u = url.substringBefore('?').trimEnd('/')
        var stripped = true
        while (stripped) {
            stripped = false
            for (suffix in listOf("/chat/completions", "/messages", "/completions", "/v1", "/v1beta")) {
                if (u.endsWith(suffix)) {
                    u = u.removeSuffix(suffix)
                    stripped = true
                }
            }
        }
        return u.trimEnd('/')
    }
}
