package com.zhique.core.agent

import com.zhique.core.ai.ToolCall
import com.zhique.core.ai.ToolSchema
import com.zhique.core.agent.tools.WebTools
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 工具注册表（计划 Task 4.1）：按 [ToolContext.vision] 装配——
 * 纯文本模型工具箱不出现 screenshot_page（防幻觉硬隔离，规格 §4.5），
 * invoke 侧再拦一次（双保险）。
 */
class ToolRegistry(private val tools: List<Tool>) {

    private val byName: Map<String, Tool> = tools.associateBy { it.name }

    /** 该上下文可见的工具（vision 过滤）。 */
    fun visible(vision: Boolean): List<Tool> =
        tools.filter { it.name != WebTools.SCREENSHOT_PAGE || vision }

    operator fun get(name: String): Tool? = byName[name]

    /** 发给模型的工具 schema（三协议共用 JSON Schema 形态）。 */
    fun schemas(vision: Boolean): List<ToolSchema> = visible(vision).map { it.schema }

    /** 解析并执行一次工具调用；未知工具 / 越权工具抛 [ToolException]。 */
    suspend fun invoke(ctx: ToolContext, call: ToolCall): JsonElement {
        val tool = byName[call.name] ?: throw ToolException("未知工具：${call.name}")
        if (call.name == WebTools.SCREENSHOT_PAGE && !ctx.vision) {
            throw ToolException("当前模型无视觉能力，screenshot_page 未装配")
        }
        return tool.invoke(ctx, parseArgs(call.argumentsJson))
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** argumentsJson 解析：空/空白按空对象；非法 JSON 也按空对象（模型输出容错，循环不因参数崩）。 */
        fun parseArgs(raw: String?): JsonElement {
            if (raw.isNullOrBlank()) return JsonObject(emptyMap())
            return runCatching { json.parseToJsonElement(raw) }.getOrDefault(JsonObject(emptyMap()))
        }

        fun str(args: JsonElement, key: String): String? =
            ((args as? JsonObject)?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

        fun int(args: JsonElement, key: String): Int? {
            val v = (args as? JsonObject)?.get(key) as? JsonPrimitive ?: return null
            return if (v.isString) v.content.toIntOrNull() else v.content.toIntOrNull()
        }
    }
}
