package com.zhique.core.paste

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * AI 兜底解析（规格 §4.1.3，F1 收口）：规则置信度 < PasteConfidence.AI_FALLBACK_THRESHOLD 时，
 * 交内置 AI（快循环角色）按固定提示词「仅解析重组」输出 JSON 结构化；失败保留原文入库。
 *
 * 本文件为纯 JVM：模型调用经 [FastChat] 注入（:app 侧适配 Provider 管线），
 * 不引入 :core:ai 依赖。异常向上透出，由调用方决定保留规则组装结果/原文。
 */
object AiFallback {

    /** 固定提示词：只解析不改逻辑，输出 JSON（title 可缺省）。 */
    const val SYSTEM_PROMPT =
        "你是粘贴解析器。对用户输入仅解析重组：整理为单个完整 HTML 文档" +
            "（补齐 <!DOCTYPE html>/<head>/<body>，代码归位 script/style），" +
            "不改写逻辑、不增删功能。只输出 JSON：" +
            "{\"title\":\"<标题，可省略>\",\"html\":\"<完整文档>\"}，不要解释、不要围栏。"

    /** 送解析的输入上限（超长粘贴防拖沓；超出部分不参与结构化）。 */
    const val INPUT_CLIP = 64 * 1024

    fun interface FastChat {
        /** 快循环角色通道：返回模型全文（失败/异常由实现决定）。maxTokens ≤0 = 实现自定。 */
        suspend fun complete(system: String, user: String, maxTokens: Int): String?
    }

    /** 粘贴预览侧的兜底解析口：无结构化结果返回 null（调用方保留原文/规则结果）。 */
    fun interface Parser {
        suspend fun parse(raw: String): Parsed?
    }

    /** 解析产物（[title] 可空，UI 兜底命名）。 */
    data class Parsed(val title: String?, val html: String)

    suspend fun parse(raw: String, fastChat: FastChat): Parsed {
        val out = fastChat.complete(SYSTEM_PROMPT, clipInput(raw), 0)
            ?: error("模型无输出")
        return parseModelOutput(out) ?: error("模型输出不是有效 JSON 结构")
    }

    /** 送解析的输入（截断保护）。 */
    fun clipInput(raw: String): String = if (raw.length > INPUT_CLIP) raw.take(INPUT_CLIP) else raw

    private val json = Json { ignoreUnknownKeys = true }

    /** 模型输出 → 结构化：容错 ``` 围栏；非法/缺 html 返回 null（调用方保留原文）。 */
    fun parseModelOutput(output: String): Parsed? {
        val body = extractJson(output) ?: return null
        val obj = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val html = (obj["html"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        if (html.isBlank()) return null
        val title = (obj["title"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return Parsed(title = title?.takeIf { it.isNotBlank() }, html = html)
    }

    private fun extractJson(output: String): String? {
        val fenced = Regex("```(?:json)?\\s*([\\s\\S]*?)```").find(output)
        val candidate = fenced?.groupValues?.get(1) ?: output
        val start = candidate.indexOf('{')
        val end = candidate.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return candidate.substring(start, end + 1)
    }
}
