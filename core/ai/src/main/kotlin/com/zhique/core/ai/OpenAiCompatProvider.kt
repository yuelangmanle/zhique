package com.zhique.core.ai

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * OpenAI 兼容协议适配器（覆盖 OpenAI/DeepSeek/GLM/Kimi/Qwen/Ollama 等）。
 * `/v1/chat/completions`，SSE 流式，`Authorization: Bearer`。
 *
 * 事件映射：`delta.reasoning_content`（或 o 系列 `reasoning`）→ ThinkingDelta；
 * `delta.content` → ContentDelta；`delta.tool_calls` → ToolCallDelta 增量；
 * `finish_reason=="length"` → Done(LENGTH)，其余 → Done(STOP)；
 * 流内 `{"error":{...}}` → Done(ERROR)（HTTP 状态错误直接抛 [AiError]）。
 */
class OpenAiCompatProvider(private val client: OkHttpClient = defaultClient()) : Provider {

    override val id = Protocol.OPENAI_COMPATIBLE

    override suspend fun chatStream(req: ChatRequest): Flow<StreamEvent> =
        sseChatFlow(client, buildHttpRequest(req)) { { payload -> parseChunk(payload) } }

    internal fun buildHttpRequest(req: ChatRequest): Request = Request.Builder()
        .url(req.baseUrl.trimEnd('/') + PATH)
        .header("Authorization", "Bearer ${req.apiKey}")
        .header("Content-Type", "application/json")
        .header("Accept", "text/event-stream")
        .post(buildRequestJson(req).toString().toRequestBody(JSON))
        .build()

    companion object {
        const val PATH = "/v1/chat/completions"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = defaultHttpClient()
    }
}

/** 三协议协议标识（Provider 设置与 RoleRouter 存这个 id）。 */
object Protocol {
    const val OPENAI_COMPATIBLE = "openai_compatible"
    const val ANTHROPIC_MESSAGES = "anthropic_messages"
    const val GOOGLE_GENAI = "google_genai"
}

/** 请求体构造（internal 供测试断言形态）。 */
internal fun buildRequestJson(req: ChatRequest): JsonObject = buildJsonObject {
    put("model", req.model)
    put("stream", true)
    put("max_tokens", req.maxTokens)
    put("temperature", req.temperature)
    put("messages", buildJsonArray {
        req.messages.forEach { m ->
            add(buildJsonObject {
                put("role", m.role)
                put("content", m.content)
                m.toolCallId?.let { put("tool_call_id", it) }
                m.toolCallsJson?.let { put("tool_calls", runCatching { Json.parseToJsonElement(it) }.getOrElse { JsonArray(emptyList()) }) }
            })
        }
    })
    if (req.tools.isNotEmpty()) {
        put("tools", buildJsonArray {
            req.tools.forEach { t ->
                add(buildJsonObject {
                    put("type", "function")
                    put("function", buildJsonObject {
                        put("name", t.name)
                        put("description", t.description)
                        put("parameters", runCatching { Json.parseToJsonElement(t.parametersJson) }.getOrElse { buildJsonObject { } })
                    })
                })
            }
        })
    }
}

/** 单个 SSE data 帧解析为 0..n 个流事件（internal 供测试）。 */
internal fun parseChunk(payload: String): List<StreamEvent> {
    val obj = runCatching { Json.parseToJsonElement(payload) }.getOrNull() as? JsonObject
        ?: return listOf(StreamEvent.Done(StopReason.ERROR("无法解析的流帧：${payload.take(120)}")))
    (obj["error"] as? JsonObject)?.let { err ->
        val msg = err["message"]?.jsonPrimitive?.contentOrNull ?: err.toString()
        return listOf(StreamEvent.Done(StopReason.ERROR(msg)))
    }
    val events = mutableListOf<StreamEvent>()
    val choice = (obj["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
    val delta = choice?.get("delta") as? JsonObject
    if (delta != null) {
        for (key in THINKING_KEYS) {
            val t = (delta[key] as? JsonPrimitive)?.contentOrNull
            if (!t.isNullOrEmpty()) {
                events += StreamEvent.ThinkingDelta(t)
                break
            }
        }
        val content = (delta["content"] as? JsonPrimitive)?.contentOrNull
        if (!content.isNullOrEmpty()) events += StreamEvent.ContentDelta(content)
        (delta["tool_calls"] as? JsonArray)?.forEach { el ->
            val tc = el as? JsonObject ?: return@forEach
            val fn = tc["function"] as? JsonObject
            events += StreamEvent.ToolCallDelta(
                index = (tc["index"] as? JsonPrimitive)?.intOrNull ?: 0,
                id = (tc["id"] as? JsonPrimitive)?.contentOrNull,
                name = fn?.get("name")?.let { (it as? JsonPrimitive)?.contentOrNull },
                argsDelta = fn?.get("arguments")?.let { (it as? JsonPrimitive)?.contentOrNull } ?: "",
            )
        }
    }
    val finish = (choice?.get("finish_reason") as? JsonPrimitive)?.contentOrNull
    if (finish != null) {
        events += StreamEvent.Done(if (finish == "length") StopReason.LENGTH else StopReason.STOP)
    }
    return events
}

/** HTTP 状态码 → [AiError] 分类（三适配器共用，供退避策略）。 */
object HttpErrors {
    fun fromCode(code: Int, body: String?): AiError {
        val msg = extractMessage(body) ?: "HTTP $code"
        return when {
            code == 401 || code == 403 -> AiError.Auth(msg)
            code == 429 -> AiError.RateLimit(msg)
            code >= 500 -> AiError.Server(msg)
            else -> AiError.Http(code, msg)
        }
    }

    /** 从错误体提取 message（兼容 {"error":{"message":..}} 与 {"message":..}）。 */
    fun extractMessage(body: String?): String? = runCatching {
        val el = Json.parseToJsonElement(body ?: return@runCatching null)
        if (el is JsonObject) {
            (el["error"] as? JsonObject)?.get("message")?.let { (it as? JsonPrimitive)?.contentOrNull }
                ?: (el["message"] as? JsonPrimitive)?.contentOrNull
        } else {
            null
        }
    }.getOrNull()
}

private val THINKING_KEYS = listOf("reasoning_content", "reasoning")
