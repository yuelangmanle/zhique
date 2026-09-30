package com.zhique.runner.ai

import com.zhique.core.ai.AgentRole
import com.zhique.core.ai.ChatRequest
import com.zhique.core.ai.ModelCatalog
import com.zhique.core.ai.Modality
import com.zhique.core.ai.ModelRef
import com.zhique.core.ai.Preset
import com.zhique.core.ai.RoleRouter
import com.zhique.core.ai.StreamEvent
import com.zhique.core.ai.VisionRoleError
import com.zhique.runner.AppContainer
import com.zhique.runner.settings.ProviderConfig
import com.zhique.runner.settings.providerFor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * 角色路由 → Provider 调用通道装配（M3 规格审查遗留接线 b/c 的生产侧）：
 * - [wire] 解析五槽之一 → Provider 配置 → ChatRequest 模板（输出上限三层解析）；
 * - UsageMeter.record 挂进 Chat/Agent 调用管线（每 Provider/每项目累计）；
 * - 视觉槽纯文本硬拦（手动覆盖口径再拦一次）。
 */
class AiWiring(private val container: AppContainer) {

    data class Wired(
        val providerId: String,
        val model: String,
        val vision: Boolean,
        val contextWindow: Int,
        val template: ChatRequest,
        val fastTemplate: ChatRequest,
        val chat: suspend (ChatRequest) -> Flow<StreamEvent>,
        val fastChat: suspend (ChatRequest) -> Flow<StreamEvent>,
        val recordUsage: suspend (tokens: Int) -> Unit,
    )

    /** 解析一个角色的调用通道；未配置任何 Provider 返回 null（UI 引导去服务商页）。 */
    suspend fun wire(role: AgentRole, projectId: String? = null): Wired? {
        val providers = container.providerStore.list()
        if (providers.isEmpty()) return null
        val defaultProvider = providers.first()

        val router = RoleRouter(defaultProvider.id)
        val bindings = container.roleBindingStore.current()
        val preset = runCatching { Preset.valueOf(bindings.preset) }.getOrDefault(Preset.BALANCED)
        val roles = bindings.roles.toRoleMap()
        val overrides = (projectId?.let { bindings.projectOverrides[it] } ?: emptyMap()).toRoleMap()

        val ref = router.resolve(role, roles, overrides, preset)
        val fastRef = router.resolve(AgentRole.FAST_LOOP, roles, overrides, preset)

        val config = providers.firstOrNull { it.id == ref.providerId } ?: defaultProvider
        val fastConfig = providers.firstOrNull { it.id == fastRef.providerId } ?: defaultProvider

        val modality = config.modalityManual?.let { manual ->
            if (manual == "vision") Modality.VISION else Modality.TEXT
        } ?: ModelCatalog.modality(ref.model)
        if (role == AgentRole.VISION && modality != Modality.VISION) throw VisionRoleError(ref)

        val template = ChatRequest(
            baseUrl = config.baseUrl,
            apiKey = container.providerStore.decryptKey(config),
            model = ref.model,
            messages = emptyList(),
            maxTokens = ModelCatalog.resolveMaxOutput(ref.model, config.maxOutputManual),
        )
        val fastTemplate = ChatRequest(
            baseUrl = fastConfig.baseUrl,
            apiKey = container.providerStore.decryptKey(fastConfig),
            model = fastRef.model,
            messages = emptyList(),
            maxTokens = ModelCatalog.FAST_LOOP_MAX_OUTPUT, // 快循环 4096（规格 §4.4.1）
        )

        return Wired(
            providerId = config.id,
            model = ref.model,
            vision = modality == Modality.VISION,
            contextWindow = ModelCatalog.resolveContextWindow(ref.model, config.contextManual),
            template = template,
            fastTemplate = fastTemplate,
            chat = { req -> providerFor(config.protocol).chatStream(req) },
            fastChat = { req -> providerFor(fastConfig.protocol).chatStream(req) },
            recordUsage = { tokens -> container.usageMeter.record(config.id, projectId, tokens) },
        )
    }

    private fun Map<String, ModelRef>.toRoleMap(): Map<AgentRole, ModelRef> =
        mapNotNull { (k, v) ->
            runCatching { AgentRole.valueOf(k) }.getOrNull()?.let { it to v }
        }.toMap()
}
