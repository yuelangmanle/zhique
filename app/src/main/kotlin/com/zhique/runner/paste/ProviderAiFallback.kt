package com.zhique.runner.paste

import com.zhique.core.ai.ChatMessage
import com.zhique.core.paste.AiFallback
import com.zhique.runner.ai.AiWiring

/**
 * AI 兜底解析的 Provider 适配（规格 §4.1.3，Task 4.6）：
 * 快循环角色通道 + FAST_LOOP 4096 输出上限；解析失败由调用方保留原文入库。
 */
class ProviderAiFallback(private val wired: AiWiring.Wired) : AiFallback.Parser {

    override suspend fun parse(raw: String): AiFallback.Parsed? {
        val req = wired.fastTemplate.copy(
            messages = listOf(
                ChatMessage("system", AiFallback.SYSTEM_PROMPT),
                ChatMessage("user", AiFallback.clipInput(raw)),
            ),
            tools = emptyList(),
        )
        var out = ""
        wired.fastChat(req).collect { e ->
            if (e is com.zhique.core.ai.StreamEvent.ContentDelta) out += e.text
        }
        return AiFallback.parseModelOutput(out)
    }
}
