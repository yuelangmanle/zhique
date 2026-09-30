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
                baseUrl.trimEnd('/') + "/v1/models" to Request.Builder()
                    .header("Authorization", "Bearer $apiKey")
            Protocol.ANTHROPIC_MESSAGES ->
                baseUrl.trimEnd('/') + "/v1/models" to Request.Builder()
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
            Protocol.GOOGLE_GENAI ->
                baseUrl.trimEnd('/') + "/v1beta/models" to Request.Builder()
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
}
