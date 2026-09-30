package com.zhique.core.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * 流终止原因（M3 契约，M4 Continuer/Orchestrator 共用）。
 * ERROR 携带流内错误文案（区别于传输层直接抛出的 [AiError]）。
 */
sealed interface StopReason {
    data object STOP : StopReason
    data object LENGTH : StopReason
    data class ERROR(val msg: String) : StopReason
    data object CANCELLED : StopReason
}

/**
 * 统一流事件（规格 §4.4.1 思考流）：思考与正文分流，
 * 三协议各自映射到同一组事件（ThinkingDelta/ContentDelta/ToolCallDelta/Done）。
 */
sealed interface StreamEvent {
    data class ThinkingDelta(val text: String) : StreamEvent
    data class ContentDelta(val text: String) : StreamEvent
    data class ToolCallDelta(
        val index: Int,
        val id: String?,
        val name: String?,
        val argsDelta: String,
    ) : StreamEvent

    data class Done(val stopReason: StopReason) : StreamEvent
}

/** 对话消息；role ∈ system/user/assistant/tool。tool 结果带 [toolCallId]，assistant 带工具调用时给 [toolCallsJson]（原始 JSON 数组）。[images] 为 data URL（视觉探测与截图回看用）。 */
@Serializable
data class ChatMessage(
    val role: String,
    val content: String,
    val toolCallId: String? = null,
    val toolCallsJson: String? = null,
    val images: List<String> = emptyList(),
)

/** 工具 JSON Schema（三协议共用形态，parametersJson 为 JSON Schema 原文）。 */
@Serializable
data class ToolSchema(
    val name: String,
    val description: String = "",
    val parametersJson: String = "{}",
)

/** 一次会话请求（BYOK：baseUrl/apiKey 由 Provider 设置注入，密钥零日志零 URL）。 */
@Serializable
data class ChatRequest(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolSchema> = emptyList(),
    val maxTokens: Int,
    val temperature: Double = 0.3,
    val thinkingEnabled: Boolean = true,
)

/** 可插拔 Provider（规格 §4.4 三协议：openai_compatible / anthropic_messages / google_genai）。 */
interface Provider {
    val id: String
    suspend fun chatStream(req: ChatRequest): Flow<StreamEvent>
}

/** 传输/HTTP 层错误，按退避策略分类（401/429/5xx/网络）。 */
sealed class AiError(msg: String, cause: Throwable? = null) : Exception(msg, cause) {
    /** 401/403：Key 无效或无权限，不重试。 */
    class Auth(msg: String) : AiError(msg)

    /** 429：限流，指数退避重试。 */
    class RateLimit(msg: String) : AiError(msg)

    /** 5xx：服务端错误，指数退避重试。 */
    class Server(msg: String) : AiError(msg)

    /** 连接失败/超时，指数退避重试。 */
    class Network(msg: String, cause: Throwable? = null) : AiError(msg, cause)

    /** 其余状态码。 */
    class Http(val code: Int, msg: String) : AiError(msg)

    /** 响应无法按协议解析。 */
    class Protocol(msg: String) : AiError(msg)
}

/** 流内错误事件（Done(ERROR)）转出的异常，续写器与编排器据此中断。 */
class AiErrorException(msg: String) : Exception(msg)

/** 解析 data URL（`data:<mime>;base64,<payload>`）为 (mime, base64)，非法返回 null（三协议图片块共用）。 */
internal fun parseDataUrl(url: String): Pair<String, String>? {
    val prefix = "data:"
    if (!url.startsWith(prefix)) return null
    val rest = url.substring(prefix.length)
    val comma = rest.indexOf(',')
    if (comma <= 0) return null
    val meta = rest.substring(0, comma)
    val payload = rest.substring(comma + 1)
    if (!meta.endsWith(";base64") || payload.isEmpty()) return null
    return meta.removeSuffix(";base64") to payload
}

/** 聚合完成的工具调用（argumentsJson 为分片拼接后的原文，由调用方解析）。 */
data class ToolCall(val id: String, val name: String, val argumentsJson: String)

/** tool_calls 流增量聚合：按 index 拼接 id/name/arguments 分片（M4 parseToolCalls 复用）。 */
class ToolCallAggregator {
    private class Acc {
        var id: String? = null
        var name: String? = null
        val args = StringBuilder()
    }

    private val accs = mutableMapOf<Int, Acc>()

    fun accept(e: StreamEvent.ToolCallDelta) {
        val acc = accs.getOrPut(e.index) { Acc() }
        if (e.id != null) acc.id = e.id
        if (e.name != null) acc.name = e.name
        acc.args.append(e.argsDelta)
    }

    fun build(): List<ToolCall> = accs.toSortedMap().map { (idx, acc) ->
        ToolCall(id = acc.id ?: "call_$idx", name = acc.name ?: "", argumentsJson = acc.args.toString())
    }
}
