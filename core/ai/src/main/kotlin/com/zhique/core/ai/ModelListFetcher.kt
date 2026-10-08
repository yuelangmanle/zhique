package com.zhique.core.ai

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 「拉取模型列表」（Provider 设置屏按钮）：三协议各自的 /models 端点。
 * Key 只走请求头（与隐私纪律一致），错误抛 [AiError] 供 UI 分类提示。
 */
class ModelListFetcher(private val client: OkHttpClient = defaultHttpClient()) {

    suspend fun fetch(protocol: String, baseUrl: String, apiKey: String): List<String> =
        withContext(Dispatchers.IO) { fetchBlocking(protocol, baseUrl, apiKey) }

    private fun fetchBlocking(protocol: String, baseUrl: String, apiKey: String): List<String> {
        val (url, builder) = when (protocol) {
            Protocol.OPENAI_COMPATIBLE ->
                modelsUrl(baseUrl, "/v1") to Request.Builder()
                    .header("Authorization", "Bearer $apiKey")
            Protocol.ANTHROPIC_MESSAGES ->
                modelsUrl(baseUrl, "/v1") to Request.Builder()
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
            Protocol.GOOGLE_GENAI ->
                modelsUrl(baseUrl, "/v1beta") to Request.Builder()
                    .header("x-goog-api-key", apiKey)
            else -> throw AiError.Protocol("未知协议：$protocol")
        }
        val request = builder
            .url(url)
            .header("Accept", "application/json")
            .get()
            .build()
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw AiError.Network("连接失败：${e.message}", e)
        }
        response.use { resp ->
            if (!resp.isSuccessful) throw HttpErrors.fromCode(resp.code, resp.body?.string())
            val body = resp.body?.string() ?: return emptyList()
            return when (protocol) {
                Protocol.GOOGLE_GENAI -> parseGeminiModels(body)
                else -> parseOpenAiStyleModels(body)
            }
        }
    }

    private fun parseOpenAiStyleModels(body: String): List<String> =
        runCatching {
            val obj = Json.parseToJsonElement(body).jsonObject
            (obj["data"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { (it as? JsonObject)?.get("id")?.let { id -> (id as? JsonPrimitive)?.content } }
                .orEmpty()
        }.getOrDefault(emptyList())

    private fun parseGeminiModels(body: String): List<String> =
        runCatching {
            val obj = Json.parseToJsonElement(body).jsonObject
            obj["models"]?.jsonArray
                ?.mapNotNull { row ->
                    val name = (row as? JsonObject)?.get("name")?.let { (it as? JsonPrimitive)?.content }
                    name?.removePrefix("models/")
                }
                .orEmpty()
        }.getOrDefault(emptyList())

    companion object {
        /**
         * 模型列表 URL 智能拼接（真机循环修复：baseUrl 已带 /v1 或 /v1beta 时
         * 旧逻辑拼出 /v1/v1/models 404）。委托 [ApiUrls.join]。
         */
        fun modelsUrl(baseUrl: String, versionSegment: String): String =
            ApiUrls.join(baseUrl, versionSegment, "/models")

        private val verifyClient: OkHttpClient = defaultHttpClient()

        /**
         * 最小 chat 探测（Key 有效性真值）：POST /chat/completions max_tokens=1 流式，
         * 读首事件——流内 `{"error":…}` → [AiError.Auth]（部分网关如魔搭对坏 Key 的
         * /models 也回 200，只测 models 会把坏 Key 误报「连通」）；HTTP 非 2xx →
         * [HttpErrors] 分类。仅 openai_compatible 需要（其余协议 /models 本身严格）。
         * 模型名不存在等非认证错误按服务错误抛出，好 Key 不被误判。
         */
        fun verifyChatKey(protocol: String, baseUrl: String, apiKey: String, model: String) {
            if (protocol != Protocol.OPENAI_COMPATIBLE) return
            val url = ApiUrls.join(baseUrl, "/v1", "/chat/completions")
            val body = kotlinx.serialization.json.buildJsonObject {
                put("model", JsonPrimitive(model.ifBlank { "default" }))
                put("stream", JsonPrimitive(true))
                put("max_tokens", JsonPrimitive(1))
                put("messages", kotlinx.serialization.json.buildJsonArray {
                    add(kotlinx.serialization.json.buildJsonObject {
                        put("role", JsonPrimitive("user"))
                        put("content", JsonPrimitive("ping"))
                    })
                })
            }.toString()
            val request = okhttp3.Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            val response = try {
                verifyClient.newCall(request).execute()
            } catch (e: IOException) {
                throw AiError.Network("连接失败：${e.message}", e)
            }
            response.use { r ->
                if (!r.isSuccessful) throw HttpErrors.fromCode(r.code, r.body?.string())
                val source = r.body?.source() ?: return
                // 只读 SSE 前几行拿到首条事件即可判定，不消费整个流；
                // EOF/读异常 ≠ 验证通过——中断的连接不得误报「Key 已验证」
                repeat(20) {
                    val line = try {
                        source.readUtf8Line()
                    } catch (e: IOException) {
                        throw AiError.Network("探测流中断：${e.message}", e)
                    } ?: throw AiError.Network("探测流提前结束（连接中断）")
                    if (line.startsWith("data:")) {
                        val payload = line.removePrefix("data:").trim()
                        if (payload.contains("\"error\"")) {
                            throw AiError.Auth("Key 被拒绝（最小对话探测）")
                        }
                        return // 正常首事件：Key 有效
                    }
                }
                throw AiError.Network("探测无响应事件（无法判定 Key 有效性）")
            }
        }
    }
}

private fun String.toRequestBody(media: okhttp3.MediaType): okhttp3.RequestBody =
    okhttp3.RequestBody.create(media, this)

private fun String.toMediaType(): okhttp3.MediaType =
    okhttp3.MediaType.Companion.run { this@toMediaType.toMediaTypeOrNull() }
        ?: okhttp3.MediaType.Companion.run { "application/json".toMediaTypeOrNull() }!!

/**
 * 端点 URL 智能拼接（对话流修复）：baseUrl 已含版本段（/v1、/v1beta）时只补
 * 方法路径，否则补「版本段 + 方法路径」。模型列表与三协议 chat 端点共用——
 * OpenAI 兼容生态里「base 已带 /v1」是主流填法（DeepSeek/OpenAI 官方文档即如此），
 * 无脑拼版本段会产出 /v1/v1/chat/completions 404。
 */
object ApiUrls {
    fun join(baseUrl: String, versionSegment: String, tail: String): String {
        val base = baseUrl.trimEnd('/')
        val version = versionSegment.trimEnd('/')
        if (base.endsWith(version)) return "$base$tail"
        return "$base$version$tail"
    }
}