package com.zhique.core.agent

import com.zhique.core.ai.AiErrorException
import com.zhique.core.ai.ChatMessage
import com.zhique.core.ai.ChatRequest
import com.zhique.core.ai.ModelCatalog
import com.zhique.core.ai.StreamEvent
import com.zhique.core.ai.ToolCall
import com.zhique.core.ai.ToolSchema
import com.zhique.core.ai.TruncationContinuer
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 模型输出的工具调用（argumentsJson 为参数 JSON 原文）。 */
data class ParsedCall(val name: String, val argsJson: String)

/**
 * Agent 编排循环（规格 §4.5，计划 Task 4.2 ★）：目标 → 跑 → 采集 → 改 → 验证；
 * 三道安全带：Budget 三闸硬顶（[Budget.exhausted] 单一事实源）、每步变更前快照
 * 可回滚任意步（[SnapshotPolicy]）、审计日志完整可回放（HistoryStore.appendAudit）。
 *
 * 未闭合代码钩子（M3 遗留接线 d）：edit_file 抛 [Tool.UnclosedCode] →
 * 调 continuer.continueOnce 强制续写（maxTokens = 续写段上限常量）→ 合并后重放一次。
 */
class Orchestrator(
    private val llm: suspend (ChatRequest) -> Flow<StreamEvent>,
    private val tools: ToolRegistry,
    private val budget: Budget,
    private val continuer: TruncationContinuer = TruncationContinuer(llm),
    private val snapshotPolicy: SnapshotPolicy = SnapshotPolicy(),
    private val recordUsage: (suspend (tokens: Int) -> Unit)? = null,
) {

    suspend fun run(goal: String, ctx: AgentContext): Flow<AgentEvent> = channelFlow {
        budget.start()
        val history = ctx.toolCtx.history
        val projectId = ctx.projectId
        var latestTemplate: ChatRequest? = null
        try {
            while (true) {
                if (budget.exhausted()) {
                    send(AgentEvent.BudgetHit)
                    return@channelFlow
                }
                val round = budget.beginRound()
                val template = ctx.assembler.build(goal, round)
                latestTemplate = template
                val req = template.withTools(tools.schemas(ctx.toolCtx.vision))
                val out = try {
                    continuer.generate(req)
                } catch (e: AiErrorException) {
                    send(AgentEvent.Failed(e.message ?: "模型流内错误"))
                    return@channelFlow
                }
                budget.recordTokens(estimateTokens(out.content + out.thinking))
                recordUsage?.invoke(estimateTokens(out.content + out.thinking))
                send(AgentEvent.Round(round, out.thinking, out.content))
                // 本轮模型输出进会话记忆（下轮上下文与压缩素材）
                ctx.assembler.appendTurn("assistant", out.content)

                var calls = parseToolCalls(out.content)
                if (calls == null) {
                    // 容错：JSON 解析失败 → 修正提示重试一次（仍失败按纯文本收束）
                    val fix = runFixRetry(req, out.content)
                    if (fix != null) {
                        calls = parseToolCalls(fix)
                        if (calls == null) calls = emptyList()
                        send(AgentEvent.Round(round, "", fix))
                    } else {
                        calls = emptyList()
                    }
                }
                if (calls.isEmpty()) {
                    send(AgentEvent.Finished(out.content))
                    return@channelFlow
                }

                for (pc in calls) {
                    if (budget.exhausted()) {
                        send(AgentEvent.BudgetHit)
                        return@channelFlow
                    }
                    val c = ToolCall(id = "step-r$round-${pc.name}", name = pc.name, argumentsJson = pc.argsJson)
                    val tool = tools[c.name]
                    if (tool == null) {
                        val msg = "未知工具：${c.name}"
                        ctx.assembler.appendError(msg)
                        send(AgentEvent.StepResult(round, c.name, ok = false, detail = msg))
                        continue
                    }
                    if (tool.requiresConfirm && !ctx.autoApproved) {
                        // 外发动作默认逐项确认：发 AwaitConfirm 暂停，未批准不执行（M7 接真实批准流）
                        appendAudit(history, projectId, "await-confirm:${c.name}", c.argumentsJson)
                        send(AgentEvent.AwaitConfirm(c.name, c.argumentsJson))
                        continue
                    }
                    val snapshotId = snapshotPolicy.snapshotBefore(
                        history, projectId, c, round,
                    ) { path -> runCatching { ctx.toolCtx.repo.readFile(projectId, path) }.getOrDefault("") }
                    send(AgentEvent.Step(snapshotId, c.name, c.argumentsJson))

                    val outcome = executeWithRewrite(ctx, c, latestTemplate)
                    outcome.fold(
                        onSuccess = { result ->
                            val detail = result.toString()
                            appendAudit(history, projectId, "tool:${c.name}:ok", detail)
                            ctx.assembler.appendTurn("tool", "${c.name} 结果：$detail")
                            budget.recordTokens(estimateTokens(detail))
                            send(AgentEvent.StepResult(round, c.name, ok = true, detail = detail))
                        },
                        onFailure = { e ->
                            val detail = e.message ?: e::class.simpleName ?: "工具失败"
                            appendAudit(history, projectId, "tool:${c.name}:fail", detail)
                            ctx.assembler.appendError("${c.name}：$detail")
                            send(AgentEvent.StepResult(round, c.name, ok = false, detail = detail))
                        },
                    )
                }
            }
        } catch (e: CancellationException) {
            appendAudit(history, projectId, "orchestrator:cancelled", goal)
            throw e
        } finally {
            ctx.memory?.writeBack(projectId)
        }
    }

    /** 执行工具；UnclosedCode → 强制续写合并后重放一次；其余异常按失败收敛。 */
    private suspend fun executeWithRewrite(
        ctx: AgentContext,
        c: ToolCall,
        template: ChatRequest?,
    ): Result<JsonElement> {
        val toolCtx = ctx.toolCtx
        return try {
            Result.success(tools.invoke(toolCtx, c))
        } catch (e: Tool.UnclosedCode) {
            try {
                val merged = continuer.continueOnce(
                    e.partial,
                    continuationRequest(template, e.partial),
                ).content
                val newArgs = withContent(c.argsJsonOrEmpty(), merged)
                Result.success(tools.invoke(toolCtx, ToolCall(c.id, c.name, newArgs)))
            } catch (e2: CancellationException) {
                throw e2
            } catch (e2: Exception) {
                Result.failure(e2)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 强制续写请求：固定系统提示 + 未闭合代码原文；凭据/模型沿用最近一轮模板，
     * 输出上限用续写段常量（规格 §4.4.1，M3 遗留接线 a 的生产消费点）。
     */
    private fun continuationRequest(template: ChatRequest?, partial: String): ChatRequest {
        val base = template ?: ChatRequest(baseUrl = "", apiKey = "", model = "", messages = emptyList(), maxTokens = 0)
        return base.copy(
            messages = listOf(
                ChatMessage("system", REWRITE_SYSTEM_PROMPT),
                ChatMessage("user", partial),
            ),
            maxTokens = ModelCatalog.CONTINUE_SEGMENT_MAX_OUTPUT,
            tools = emptyList(),
        )
    }

    private suspend fun runFixRetry(req: ChatRequest, badContent: String): String? {
        val fixReq = req.copy(
            messages = req.messages +
                ChatMessage("assistant", badContent) +
                ChatMessage("user", FIX_PROMPT),
            tools = req.tools,
        )
        return try {
            var content = ""
            llm(fixReq).collect { e ->
                if (e is StreamEvent.ContentDelta) content += e.text
            }
            content
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** 工具调用 JSON 提取失败后的修正提示（重试一次）。 */
        const val FIX_PROMPT =
            "你的上一条输出不是有效的 JSON 工具调用。请重新输出：要么只输出一个 ```json 代码块，" +
                "内容为形如 [{\"tool\":\"工具名\",\"args\":{…}}] 的数组；要么直接给出最终答复（不含代码块）。"

        const val REWRITE_SYSTEM_PROMPT =
            "你是代码续写器。用户的代码因长度被截断，请从中断处原样续写补全（不要重复已有内容、" +
                "不要解释、不要 markdown 围栏），使代码结构完整闭合。"

        internal fun estimateTokens(text: String): Int = (text.length + 1) / 2

        internal fun appendAudit(
            history: com.zhique.core.project.HistoryStore,
            projectId: String,
            action: String,
            detail: String,
        ) {
            runCatching {
                history.appendAudit(projectId, com.zhique.core.project.AuditEntry(action = action, detail = detail.take(500)))
            }
        }

        // ---- 工具调用 JSON 解析 ----

        private val FENCE = Regex("```(?:json)?\\s*([\\s\\S]*?)```")

        /**
         * 解析模型输出中的工具调用数组。
         * - 含 ``` 围栏：围栏内容能解析为数组 → 逐项映射；围栏存在但解析失败 → null（触发修正重试）
         * - 无围栏：尝试裸 `[...]` 提取，解析不出 → 空列表（纯文本答复 → Finished）
         */
        fun parseToolCalls(content: String): List<ParsedCall>? {
            val fence = FENCE.find(content)
            return if (fence != null) {
                parseArray(fence.groupValues[1]) ?: run {
                    // 围栏存在但非 JSON 数组：若围栏里根本不像数组（如代码答案），按纯文本收束
                    val body = fence.groupValues[1].trim()
                    if (body.startsWith("[")) null else emptyList()
                }
            } else {
                val start = content.indexOf('[')
                val end = content.lastIndexOf(']')
                if (start >= 0 && end > start) parseArray(content.substring(start, end + 1)) else emptyList()
            }
        }

        private fun parseArray(raw: String): List<ParsedCall>? = runCatching {
            val arr = json.parseToJsonElement(raw) as? kotlinx.serialization.json.JsonArray ?: return null
            arr.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val name = (o["tool"] ?: o["name"]) as? JsonPrimitive ?: return@mapNotNull null
                val args = when (val a = o["args"] ?: o["arguments"]) {
                    is JsonObject -> a.toString()
                    null -> "{}"
                    else -> a.toString()
                }
                ParsedCall(name.content, args)
            }
        }.getOrNull()

        internal fun withContent(argsJson: String, newContent: String): String {
            val o = runCatching { json.parseToJsonElement(argsJson) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())
            val merged = JsonObject(o.toMutableMap().apply { put("content", JsonPrimitive(newContent)) })
            return merged.toString()
        }
    }
}

/** 用工具 schema 列表装配请求（编排器组装点）。 */
internal fun ChatRequest.withTools(schemas: List<ToolSchema>): ChatRequest = copy(tools = schemas)

/** ToolCall 的空参兜底（历史调用重放/续写重放用）。 */
private fun ToolCall.argsJsonOrEmpty(): String = argumentsJson.ifBlank { "{}" }
