package com.zhique.core.agent.tools

import com.zhique.core.agent.Tool
import com.zhique.core.agent.ToolContext
import com.zhique.core.ai.ToolSchema
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * git 类工具（规格 §4.5：外发动作默认逐项确认）。M4 阶段为 NotReady 占位——
 * 真实实现（JGit + GitHub API）在 M7 接线；requiresConfirm=true，
 * 编排器在非全自动模式下发 AwaitConfirm 事件并跳过执行。
 */
object GitTools {
    const val CREATE_REPO = "create_repo"
    const val PUSH = "push"
    const val READ_RELEASES = "read_releases"

    internal const val NOT_READY = "M7 接线后可用"

    fun all(): List<Tool> = listOf(CreateRepoTool, PushTool, ReadReleasesTool)
}

/** git 占位工具公共基类：NotReady 返回值 + requiresConfirm=true（外发动作语义）。 */
internal abstract class GitPlaceholder(
    override val name: String,
    description: String,
) : Tool {
    override val schema = ToolSchema(
        name = name,
        description = "$description（外发动作：执行前需用户批准）",
        parametersJson = """{"type":"object","properties":{},"additionalProperties":true}""",
    )
    override val requiresConfirm = true

    override suspend fun invoke(ctx: ToolContext, args: JsonElement): JsonObject = buildJsonObject {
        put("status", "NotReady")
        put("detail", GitTools.NOT_READY)
    }
}

internal object CreateRepoTool : GitPlaceholder(
    GitTools.CREATE_REPO,
    "在 GitHub 创建远端仓库（需用户批准）",
)

internal object PushTool : GitPlaceholder(
    GitTools.PUSH,
    "推送项目到绑定的 GitHub 仓库（需用户批准）",
)

internal object ReadReleasesTool : GitPlaceholder(
    GitTools.READ_RELEASES,
    "读取远端仓库 Release 列表（需用户批准）",
)
