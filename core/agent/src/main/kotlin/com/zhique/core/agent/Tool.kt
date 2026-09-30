package com.zhique.core.agent

import com.zhique.core.ai.ToolSchema
import com.zhique.core.project.HistoryStore
import com.zhique.core.project.ProjectRepository
import kotlinx.serialization.json.JsonElement

/**
 * Agent 工具（规格 §4.5 工具箱，计划 Task 4.1）。
 *
 * [schema] 为 JSON Schema（`ToolSchema.parametersJson`），三协议共用；
 * [requiresConfirm] 标记外发动作（git 类），编排器在非全自动模式下发 [AgentEvent.AwaitConfirm]
 * 并暂停执行（M4 阶段 git 工具本体为 NotReady 占位，M7 接线真实实现）。
 */
interface Tool {
    val name: String
    val schema: ToolSchema
    val requiresConfirm: Boolean get() = false

    suspend fun invoke(ctx: ToolContext, args: JsonElement): JsonElement

    /**
     * edit_file 产出未闭合代码（括号不平衡 / `<script>/<style>/<html>` 未闭合）：
     * 残缺补丁绝不落盘，携带 partial 由编排器调 continuer.continueOnce 强制续写后重放
     * （规格 §4.4.1 第 5 条，M3 规格审查遗留接线 d）。
     */
    class UnclosedCode(val path: String, val partial: String, detail: String) :
        Exception("$detail（已拦截，未写入 $path）")
}

/** 工具执行失败（未知工具/文件不存在/参数非法等），编排器记为该步失败并继续循环。 */
class ToolException(message: String) : Exception(message)

/**
 * 工具执行上下文：一次会话的目标项目 + 运行器控制面 + 仓库 + 视觉能力。
 * [vision]=false 时注册表不装配 screenshot_page（防幻觉硬隔离，规格 §4.5）。
 */
data class ToolContext(
    val projectId: String,
    val web: WebControl,
    val repo: ProjectRepository,
    val history: HistoryStore,
    val vision: Boolean,
)
