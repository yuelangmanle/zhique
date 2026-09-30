package com.zhique.core.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
 * Anthropic Messages 协议适配器：`/v1/messages`，`x-api-key` + `anthropic-version`。
 *
 * 事件映射（官方流事件名）：`content_block_delta.thinking_delta` → ThinkingDelta；
 * `text_delta` → ContentDelta；`input_json_delta` → ToolCallDelta（元数据来自
 * `content_block_start` 的 tool_use 块）；`message_delta.stop_reason=="max_tokens"` →
 * Done(LENGTH)，其余 → Done(STOP)；`error` 事件 → Done(ERROR)。
 */
class AnthropicProvider(private val client: OkHttpClient = defaultHttpClient()) : Provider {

    override val id = Protocol.ANTHROPIC_MESSAGES

    override suspend fun chatStream(req: ChatRequest): Flow<StreamEvent> =
        sseChatFlow(client, buildHttpRequest(req)) { { payload -> parser().parse(payload) } }

    private fun parser(): AnthropicStreamParser = AnthropicStreamParser()

    internal fun buildHttpRequest(req: ChatRequest): Request = Request.Builder()
        .url(req.baseUrl.trimEnd('/') + PATH)
        .header("x-api-key", req.apiKey)
        .header("anthropic-version", API_VERSION)
        .header("Content-Type", "application/json")
        .header("Accept", "text/event-stream")
        .post(buildAnthropicRequestJson(req).toString().toRequestBody(JSON))
        .build()

    companion object {
        const val PATH = "/v1/messages"
        const val API_VERSION = "2023-06-01"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/** 请求体构造：system 消息置顶独立字段；tool 结果以 tool_result 块回传；思考模式省略 temperature（官方约束）。 */
internal fun buildAnthropicRequestJson(req: ChatRequest): JsonObject {
    val system = req.messages.filter { it.role == "system" }
    val rest = req.messages.filterNot { it.role == "system" }
    return buildJsonObject {
        put("model", req.model)
        put("max_tokens", req.maxTokens)
        put("stream", true)
        if (req.thinkingEnabled) {
            put("thinking", buildJsonObject {
                put("type", "enabled")
                put("budget_tokens", maxOf(MIN_THINKING_BUDGET, req.maxTokens / 2))
            })
        } else {
            put("temperature", req.temperature)
        }
        if (system.isNotEmpty()) put("system", system.joinToString("\n") { it.content })
        put("messages", buildJsonArray {
            rest.forEach { m ->
                add(buildJsonObject {
                    put("role", if (m.role == "tool") "user" else m.role)
                    when {
                        m.role == "tool" -> put("content", buildJsonArray {
                            add(buildJsonObject {
                                put("type", "tool_result")
                                put("tool_use_id", m.toolCallId ?: "")
                                put("content", m.content)
                            })
                        })
                        m.toolCallsJson != null -> put("content", buildJsonArray {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", m.content)
                            })
                            val calls = runCatching { Json.parseToJsonElement(m.toolCallsJson) }
                                .getOrNull() as? JsonArray
                            calls?.forEach { el ->
                                val o = el as? JsonObject ?: return@forEach
                                add(buildJsonObject {
                                    put("type", "tool_use")
                                    put("id", o["id"]?.jsonPrimitive?.contentOrNull ?: "")
                                    put("name", o["name"]?.jsonPrimitive?.contentOrNull ?: "")
                                    put("input", o["arguments"] ?: buildJsonObject { })
                                })
                            }
                        })
                        else -> if (m.images.isEmpty() || m.role != "user") {
                            put("content", m.content)
                        } else {
                            put("content", buildJsonArray {
                                m.images.forEach { dataUrl ->
                                    val (mediaType, base64) = parseDataUrl(dataUrl)
                                        ?: return@forEach
                                    add(buildJsonObject {
                                        put("type", "image")
                                        put("source", buildJsonObject {
                                            put("type", "base64")
                                            put("media_type", mediaType)
                                            put("data", base64)
                                        })
                                    })
                                }
                                if (m.content.isNotEmpty()) add(buildJsonObject {
                                    put("type", "text")
                                    put("text", m.content)
                                })
                            })
                        }
                    }
                })
            }
        })
    }
}

/** 单流解析器（持有 content_block_start 的 tool_use 元数据）。 */
internal class AnthropicStreamParser {
    private val toolMeta = mutableMapOf<Int, Pair<String?, String?>>() // index → (id, name)

    fun parse(payload: String): List<StreamEvent> {
        val obj = runCatching { Json.parseToJsonElement(payload) }.getOrNull() as? JsonObject
            ?: return listOf(StreamEvent.Done(StopReason.ERROR("无法解析的流帧：${payload.take(120)}")))
        return when (obj.str("type")) {
            "ping", "message_start", "message_stop", "content_block_stop", null -> emptyList()
            "error" -> listOf(
                StreamEvent.Done(
                    StopReason.ERROR((obj["error"] as? JsonObject).str("message") ?: obj.toString().take(120)),
                ),
            )
            "content_block_start" -> {
                val index = obj.int("index") ?: 0
                val block = obj["content_block"] as? JsonObject
                if (block.str("type") == "tool_use") {
                    val id = block.str("id")
                    val name = block.str("name")
                    toolMeta[index] = id to name
                    listOf(StreamEvent.ToolCallDelta(index, id, name, ""))
                } else {
                    emptyList()
                }
            }
            "content_block_delta" -> {
                val index = obj.int("index") ?: 0
                val delta = obj["delta"] as? JsonObject ?: return emptyList()
                when (delta.str("type")) {
                    "thinking_delta" -> delta.str("thinking")?.takeIf { it.isNotEmpty() }
                        ?.let { listOf(StreamEvent.ThinkingDelta(it)) } ?: emptyList()
                    "text_delta" -> delta.str("text")?.takeIf { it.isNotEmpty() }
                        ?.let { listOf(StreamEvent.ContentDelta(it)) } ?: emptyList()
                    "input_json_delta" -> listOf(
                        StreamEvent.ToolCallDelta(index, null, null, delta.str("partial_json") ?: ""),
                    )
                    else -> emptyList()
                }
            }
            "message_delta" -> {
                val stop = (obj["delta"] as? JsonObject).str("stop_reason")
                listOf(StreamEvent.Done(if (stop == "max_tokens") StopReason.LENGTH else StopReason.STOP))
            }
            else -> emptyList()
        }
    }
}

internal fun JsonObject?.str(key: String): String? =
    (this?.get(key) as? JsonPrimitive)?.contentOrNull

internal fun JsonObject?.int(key: String): Int? =
    (this?.get(key) as? JsonPrimitive)?.intOrNull

private const val MIN_THINKING_BUDGET = 1024
