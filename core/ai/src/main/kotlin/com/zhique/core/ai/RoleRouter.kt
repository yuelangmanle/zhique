package com.zhique.core.ai

/** 角色槽（规格 §4.4 角色路由表：五槽）。 */
enum class AgentRole(val label: String, val hint: String) {
    CHAT("对话伙伴", "问答/讲解/生成"),
    AGENT_MAIN("Agent 主力", "规划/决策/关键改码"),
    FAST_LOOP("快循环", "重试验证/摘要/压缩"),
    VISION("视觉检查", "截图回看（须 vision 模型）"),
    CHORE("杂务", "commit message/标题"),
}

/** 一个角色的模型指向。 */
@kotlinx.serialization.Serializable
data class ModelRef(val providerId: String, val model: String)

/** 解析结果附能力（徽章用）。 */
data class RoleRoute(val role: AgentRole, val ref: ModelRef, val modality: Modality)

/** 视觉槽误配纯文本模型的硬拦异常（规格 §4.4：误选纯文本硬拦）。 */
class VisionRoleError(val ref: ModelRef) :
    Exception("视觉槽必须选择 vision 模型：「${ref.model}」是纯文本模型")

/** 预设组合（省钱/均衡/质量），一键填充未显式绑定的槽位。 */
enum class Preset(val label: String) {
    ECO("省钱"),
    BALANCED("均衡"),
    QUALITY("质量"),
    ;

    /** 预设默认模型（[providerId] 为全局默认 Provider）。模型名均为目录内已知样本。 */
    fun defaults(providerId: String): Map<AgentRole, ModelRef> {
        fun ref(model: String) = ModelRef(providerId, model)
        return when (this) {
            ECO -> mapOf(
                AgentRole.CHAT to ref("deepseek-chat"),
                AgentRole.AGENT_MAIN to ref("deepseek-chat"),
                AgentRole.FAST_LOOP to ref("deepseek-chat"),
                AgentRole.VISION to ref("gpt-4o-mini"),
                AgentRole.CHORE to ref("deepseek-chat"),
            )
            BALANCED -> mapOf(
                AgentRole.CHAT to ref("gpt-4o"),
                AgentRole.AGENT_MAIN to ref("gpt-4o"),
                AgentRole.FAST_LOOP to ref("deepseek-chat"),
                AgentRole.VISION to ref("gemini-2.5-pro"),
                AgentRole.CHORE to ref("deepseek-chat"),
            )
            QUALITY -> mapOf(
                AgentRole.CHAT to ref("gpt-5"),
                AgentRole.AGENT_MAIN to ref("claude-sonnet-4"),
                AgentRole.FAST_LOOP to ref("deepseek-chat"),
                AgentRole.VISION to ref("gemini-2.5-pro"),
                AgentRole.CHORE to ref("deepseek-chat"),
            )
        }
    }
}

/**
 * 角色路由：不同模型干不同的活（规格 §4.4）。
 * 优先级：项目覆盖 > 用户绑定 > 预设默认；视觉槽纯文本硬拦（[VisionRoleError]）。
 */
class RoleRouter(
    val defaultProviderId: String,
    private val modalityLookup: (String) -> Modality = { ModelCatalog.modality(it) },
) {
    val modalityOf: (String) -> Modality = modalityLookup

    fun presetBindings(preset: Preset): Map<AgentRole, ModelRef> = preset.defaults(defaultProviderId)

    /** 解析五槽之一；视觉槽过硬闸。未配置任何来源则抛状态异常（UI 引导配置）。 */
    fun resolve(
        role: AgentRole,
        bindings: Map<AgentRole, ModelRef>,
        projectOverrides: Map<AgentRole, ModelRef> = emptyMap(),
        preset: Preset = Preset.BALANCED,
    ): ModelRef {
        val ref = projectOverrides[role]
            ?: bindings[role]
            ?: preset.defaults(defaultProviderId)[role]
            ?: throw IllegalStateException("角色「${role.label}」未配置模型")
        if (role == AgentRole.VISION && modalityOf(ref.model) != Modality.VISION) {
            throw VisionRoleError(ref)
        }
        return ref
    }

    /** 全槽解析（配置屏预览用）。 */
    fun resolveAll(
        bindings: Map<AgentRole, ModelRef>,
        projectOverrides: Map<AgentRole, ModelRef> = emptyMap(),
        preset: Preset = Preset.BALANCED,
    ): List<RoleRoute> = AgentRole.entries.map { role ->
        val ref = resolve(role, bindings, projectOverrides, preset)
        RoleRoute(role, ref, modalityOf(ref.model))
    }
}
