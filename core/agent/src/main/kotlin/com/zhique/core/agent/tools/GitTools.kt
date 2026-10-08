package com.zhique.core.agent.tools

import com.zhique.core.agent.Tool
import com.zhique.core.agent.ToolContext
import com.zhique.core.agent.ToolRegistry
import com.zhique.core.ai.ToolSchema
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * git 类工具（规格 §4.5/§4.8，M7 接线真实实现）：
 * - requiresConfirm=true 保持不变——外发动作默认逐项确认，编排器在非全自动模式下
 *   发 AwaitConfirm 事件并挂起（M4 ConfirmGate）；
 * - 真实逻辑在 `:core:publish` + `:app` 侧经 [Gateway] 注入（[bind]，进程级单点）；
 * - 未绑定（早期测试/未装配容器）保持 NotReady 占位语义。
 */
object GitTools {
    const val CREATE_REPO = "create_repo"
    const val PUSH = "push"
    const val READ_RELEASES = "read_releases"

    internal const val NOT_READY = "M7 接线后可用"

    /**
     * 发布底座缝：`:app` 容器绑定真实实现（GitHubApi/GitRepo/ReleaseJobEngine）。
     * 返回 JSON 结果；失败返回 `{"status":"error","detail":...}` 供模型理解重试，
     * 凭据类细节由底层保证不入文本。
     */
    interface Gateway {
        suspend fun createRepo(projectId: String, name: String, isPrivate: Boolean): JsonObject
        suspend fun push(projectId: String, message: String?, wantRelease: Boolean, tag: String?): JsonObject
        suspend fun readReleases(projectId: String): JsonObject
    }

    @Volatile private var gateway: Gateway? = null

    /** 容器装配时绑定（null=解绑，回到 NotReady）。 */
    fun bind(g: Gateway?) {
        gateway = g
    }

    fun all(): List<Tool> = listOf(CreateRepoTool, PushTool, ReadReleasesTool)

    /** 未绑定时的占位返回。 */
    internal suspend fun notReady(): JsonObject = buildJsonObject {
        put("status", "NotReady")
        put("detail", NOT_READY)
    }

    internal fun current(): Gateway? = gateway
}

/** git 工具公共基类：未绑定→NotReady；绑定→经 [Gateway] 执行。requiresConfirm=true。 */
internal abstract class GitToolBase(
    override val name: String,
    private val desc: String,
) : Tool {
    /** lazy：parametersJson 由子类提供，不能在基类初始化期取（open 属性陷阱）。 */
    override val schema: ToolSchema by lazy {
        ToolSchema(
            name = name,
            description = "$desc（外发动作：执行前需用户批准）",
            parametersJson = parametersJson,
        )
    }
    override val requiresConfirm = true

    protected abstract val parametersJson: String

    final override suspend fun invoke(ctx: ToolContext, args: JsonElement): JsonObject {
        val g = GitTools.current() ?: return GitTools.notReady()
        // runCatching 会吞 CancellationException（外层 catch 不可达）——必须显式放行
        try {
            return execute(g, ctx, args)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return buildJsonObject {
                put("status", "error")
                put("detail", e.message ?: e.javaClass.simpleName)
            }
        }
    }

    protected abstract suspend fun execute(g: GitTools.Gateway, ctx: ToolContext, args: JsonElement): JsonObject
}

internal object CreateRepoTool : GitToolBase(
    GitTools.CREATE_REPO,
    "在 GitHub 创建远端仓库并绑定到当前项目（需用户批准）",
) {
    override val parametersJson =
        """{"type":"object","properties":{"name":{"type":"string","description":"仓库名，默认项目名"},"private":{"type":"boolean","description":"默认私有"}},"required":[]}"""

    override suspend fun execute(
        g: GitTools.Gateway,
        ctx: ToolContext,
        args: JsonElement,
    ): JsonObject {
        val name = ToolRegistry.str(args, "name") ?: ctx.repo.meta(ctx.projectId).name
        val isPrivate = ToolRegistry.str(args, "private")?.toBooleanStrictOrNull() ?: true
        return g.createRepo(ctx.projectId, name, isPrivate)
    }
}

internal object PushTool : GitToolBase(
    GitTools.PUSH,
    "提交并推送项目到绑定的 GitHub 仓库（状态机驱动，可断点续跑；默认同时创建 GitHub Release 并附 APK；需用户批准）",
) {
    override val parametersJson =
        """{"type":"object","properties":{"message":{"type":"string","description":"commit message，留空自动生成"},"wantRelease":{"type":"boolean","description":"是否同时创建 GitHub Release（默认 true）"},"tag":{"type":"string","description":"Release tag，如 v1.0.0；留空按导出版本号"}},"required":[]}"""

    override suspend fun execute(
        g: GitTools.Gateway,
        ctx: ToolContext,
        args: JsonElement,
    ): JsonObject = g.push(
        ctx.projectId,
        ToolRegistry.str(args, "message"),
        ToolRegistry.bool(args, "wantRelease") ?: true,
        ToolRegistry.str(args, "tag"),
    )
}

internal object ReadReleasesTool : GitToolBase(
    GitTools.READ_RELEASES,
    "读取绑定仓库的 GitHub Release 列表（需用户批准）",
) {
    override val parametersJson =
        """{"type":"object","properties":{},"additionalProperties":true}"""

    override suspend fun execute(
        g: GitTools.Gateway,
        ctx: ToolContext,
        args: JsonElement,
    ): JsonObject = g.readReleases(ctx.projectId)
}
