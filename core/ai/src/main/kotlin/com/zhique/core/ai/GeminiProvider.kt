package com.zhique.core.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Gemini 原生适配器：`/v1beta/models/{model}:streamGenerateContent?alt=sse`。
 *
 * Key 经 `x-goog-api-key` 头发送（官方同样支持 header 形态）——规格 §6 隐私纪律
 * 「Key 永不出现在 URL/日志」优先于计划中的 `?key=` 查询参数写法（偏差已记录）。
 *
 * 事件映射：`candidates[0].content.parts[]`：`thought==true` → ThinkingDelta；
 * `text` → ContentDelta；`functionCall` → ToolCallDelta；
 * `finishReason=="MAX_TOKENS"` → Done(LENGTH)，其余 → Done(STOP)；
 * 流内 `{"error":{...}}` → Done(ERROR)。
 */
class GeminiProvider(private val client: OkHttpClient = defaultHttpClient()) : Provider {

    override val id = Protocol.GOOGLE_GENAI

    override suspend fun chatStream(req: ChatRequest): Flow<StreamEvent> =
        sseChatFlow(client, buildHttpRequest(req)) { { payload -> GeminiStreamParser().parse(payload) } }

    internal fun buildHttpRequest(req: ChatRequest): Request = Request.Builder()
        .url(ApiUrls.join(req.baseUrl, VERSION, "/models/${req.model}:streamGenerateContent?alt=sse"))
        .header("x-goog-api-key", req.apiKey)
        .header("Content-Type", "application/json")
        .post(buildGeminiRequestJson(req).toString().toRequestBody(JSON))
        .build()

    companion object {
        const val VERSION = "/v1beta"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/** 请求体构造：system → systemInstruction；user/model 角色；generationConfig.maxOutputTokens。 */
internal fun buildGeminiRequestJson(req: ChatRequest): JsonObject {
    val system = req.messages.filter { it.role == "system" }
    val rest = req.messages.filterNot { it.role == "system" }
    return buildJsonObject {
        if (system.isNotEmpty()) {
            put("systemInstruction", buildJsonObject {
                put("parts", buildJsonArray {
                    system.forEach { add(buildJsonObject { put("text", it.content) }) }
                })
            })
        }
        put("contents", buildJsonArray {
            rest.forEach { m ->
                add(buildJsonObject {
                    put("role", if (m.role == "assistant") "model" else "user")
                    put("parts", buildJsonArray {
                        if (m.content.isNotEmpty()) add(buildJsonObject { put("text", m.content) })
                        m.images.forEach { dataUrl ->
                            val (mimeType, base64) = parseDataUrl(dataUrl) ?: return@forEach
                            add(buildJsonObject {
                                put("inlineData", buildJsonObject {
                                    put("mimeType", mimeType)
                                    put("data", base64)
                                })
                            })
                        }
                        // assistant 历史的工具调用 → functionCall parts
                        // （Gemini 约束：functionResponse 的前一条 model 轮必须含对应 functionCall）
                        if (m.role == "assistant" && m.toolCallsJson != null) {
                            val calls = runCatching { Json.parseToJsonElement(m.toolCallsJson) }
                                .getOrNull() as? JsonArray
                            calls?.forEach { el ->
                                val o = el as? JsonObject ?: return@forEach
                                val argsRaw = o["arguments"]?.let { (it as? JsonPrimitive)?.contentOrNull }
                                add(buildJsonObject {
                                    put("functionCall", buildJsonObject {
                                        put("name", o.str("name") ?: "")
                                        put("args", runCatching { Json.parseToJsonElement(argsRaw ?: "{}") }
                                            .getOrElse { buildJsonObject { } })
                                    })
                                })
                            }
                        }
                        if (m.role == "tool") {
                            add(buildJsonObject {
                                put("functionResponse", buildJsonObject {
                                    put("name", m.toolCallId ?: "")
                                    put("response", buildJsonObject { put("result", m.content) })
                                })
                            })
                        }
                    })
                })
            }
        })
        put("generationConfig", buildJsonObject {
            put("maxOutputTokens", req.maxTokens)
            put("temperature", req.temperature)
        })
        if (req.tools.isNotEmpty()) {
            put("tools", buildJsonArray {
                add(buildJsonObject {
                    put("functionDeclarations", buildJsonArray {
                        req.tools.forEach { t ->
                            add(buildJsonObject {
                                put("name", t.name)
                                put("description", t.description)
                                put("parameters", runCatching { Json.parseToJsonElement(t.parametersJson) }
                                    .getOrElse { buildJsonObject { } })
                            })
                        }
                    })
                })
            })
        }
    }
}

/** 单流解析器（functionCall 无 index，按出现顺序编号）。 */
internal class GeminiStreamParser {
    private var toolIndex = 0

    fun parse(payload: String): List<StreamEvent> {
        val obj = runCatching { Json.parseToJsonElement(payload) }.getOrNull() as? JsonObject
            ?: return listOf(StreamEvent.Done(StopReason.ERROR("无法解析的流帧：${payload.take(120)}")))
        (obj["error"] as? JsonObject)?.let { err ->
            val msg = err.str("message") ?: err.toString().take(120)
            return listOf(StreamEvent.Done(StopReason.ERROR(msg)))
        }
        val events = mutableListOf<StreamEvent>()
        val candidate = (obj["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject
        val parts = ((candidate?.get("content") as? JsonObject)?.get("parts") as? JsonArray)
        parts?.forEach { el ->
            val part = el as? JsonObject ?: return@forEach
            val text = part.str("text")
            val thought = (part["thought"] as? JsonPrimitive)?.booleanOrNull ?: false
            if (!text.isNullOrEmpty()) {
                events += if (thought) StreamEvent.ThinkingDelta(text) else StreamEvent.ContentDelta(text)
            }
            (part["functionCall"] as? JsonObject)?.let { fc ->
                events += StreamEvent.ToolCallDelta(
                    index = toolIndex++,
                    id = null,
                    name = fc.str("name") ?: "",
                    argsDelta = fc["args"]?.toString() ?: "",
                )
            }
        }
        val finish = candidate.str("finishReason")
        if (finish != null) {
            events += StreamEvent.Done(if (finish == "MAX_TOKENS") StopReason.LENGTH else StopReason.STOP)
        }
        return events
    }
}
