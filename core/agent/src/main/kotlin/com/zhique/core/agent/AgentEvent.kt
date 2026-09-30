package com.zhique.core.agent

import com.zhique.core.ai.ChatRequest

/** Agent 会话事件流（计划 Task 4.2 契约；AgentScreen 逐条渲染）。 */
sealed interface AgentEvent {
    /** 一轮模型输出（思考与正文分流，UI 复用 ThinkingBlock 折叠样式）。 */
    data class Round(val no: Int, val thinking: String, val content: String) : AgentEvent

    /** 一步工具执行开始（[snapshotId] 非空 = 已留回滚快照）。 */
    data class Step(val snapshotId: String?, val tool: String, val argsJson: String) : AgentEvent

    /** 一步执行结果。 */
    data class StepResult(val round: Int, val tool: String, val ok: Boolean, val detail: String) : AgentEvent

    /** 外发工具等待用户批准（未批准不执行）。 */
    data class AwaitConfirm(val tool: String, val argsJson: String) : AgentEvent

    /** 预算触顶（轮/token/时长任一）。 */
    data object BudgetHit : AgentEvent

    /** 目标完成（模型不再发起工具调用）。 */
    data class Finished(val content: String) : AgentEvent

    /** 模型流内错误/管道异常，会话终止（上下文保留可续跑）。 */
    data class Failed(val message: String) : AgentEvent
}

/** 会话记忆口（Task 4.3 的 Memory 实现）：会话结束自动把新约定写回 zhique.md。 */
interface AgentMemory {
    suspend fun writeBack(projectId: String)
}

/**
 * 三层记忆的会话组装口（Task 4.3 提供实现；Task 4.2 编排器只依赖本接口）。
 * 组装顺序：系统规范 > 项目记忆摘要 > 任务目标 > 文件地图 > 报错时间线 > 近期轮次。
 */
interface ContextAssembler {
    fun build(goal: String, round: Int): ChatRequest

    /** 记录一轮进近期轮次（工具结果须标 [com.zhique.core.agent.Turn.Kind.TOOL_RESULT]：滚动窗口/截断/不计长期预算）。 */
    fun appendTurn(role: String, content: String, starred: Boolean = false, kind: Turn.Kind = Turn.Kind.NORMAL)

    /** 记录一条报错（进报错时间线）。 */
    fun appendError(line: String)

    /** 上下文用量占比（0–1+，≥ autoThreshold 触发后台压缩）。 */
    fun usage(): Float
}

/** 一次编排运行的上下文（计划 Task 4.2）。 */
class AgentContext(
    val projectId: String,
    val toolCtx: ToolContext,
    val assembler: ContextAssembler,
    val memory: AgentMemory? = null,
    val autoApproved: Boolean = false,
)
