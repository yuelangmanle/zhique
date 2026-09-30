package com.zhique.core.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 角色路由（Task 3.6）：五槽解析、省钱/均衡/质量预设、视觉槽纯文本硬拦、项目覆盖优先级。
 */
class RoleRouterTest {

    private val router = RoleRouter(defaultProviderId = "p-default")

    private fun refs(vararg pairs: Pair<AgentRole, String>) =
        pairs.associate { (r, m) -> r to ModelRef(providerId = "p-default", model = m) }

    @Test
    fun `五槽解析_显式绑定生效`() {
        val out = router.resolve(
            AgentRole.AGENT_MAIN,
            bindings = refs(AgentRole.AGENT_MAIN to "claude-sonnet-4"),
        )
        assertEquals("claude-sonnet-4", out.model)
        assertEquals("p-default", out.providerId)
    }

    @Test
    fun `未绑定槽回落预设默认`() {
        val bindings = Preset.BALANCED.defaults(providerId = "p-default")
        assertEquals("gpt-4o", router.resolve(AgentRole.CHAT, bindings).model)
        assertEquals("deepseek-chat", router.resolve(AgentRole.FAST_LOOP, bindings).model)
        assertEquals("gemini-2.5-pro", router.resolve(AgentRole.VISION, bindings).model)
    }

    @Test
    fun `省钱预设全走便宜档_视觉槽仍需vision模型`() {
        val b = Preset.ECO.defaults("p-default")
        assertEquals("deepseek-chat", router.resolve(AgentRole.CHAT, b).model)
        assertEquals("deepseek-chat", router.resolve(AgentRole.FAST_LOOP, b).model)
        assertEquals(Modality.VISION, router.modalityOf(router.resolve(AgentRole.VISION, b).model),
            "ECO 视觉槽兜底也必须是 vision 模型")
    }

    @Test
    fun `质量预设主力为强模型`() {
        val b = Preset.QUALITY.defaults("p-default")
        assertEquals("claude-sonnet-4", router.resolve(AgentRole.AGENT_MAIN, b).model)
        assertEquals("gpt-5", router.resolve(AgentRole.CHAT, b).model)
    }

    @Test
    fun `项目覆盖优先于用户绑定`() {
        val out = router.resolve(
            AgentRole.FAST_LOOP,
            bindings = refs(AgentRole.FAST_LOOP to "deepseek-chat"),
            projectOverrides = refs(AgentRole.FAST_LOOP to "gpt-4o-mini"),
        )
        assertEquals("gpt-4o-mini", out.model)
    }

    @Test
    fun `视觉槽绑纯文本模型硬拦VisionRoleError`() {
        assertFailsWith<VisionRoleError> {
            router.resolve(
                AgentRole.VISION,
                bindings = refs(AgentRole.VISION to "deepseek-chat"),
            )
        }
        // 项目覆盖同样过闸
        assertFailsWith<VisionRoleError> {
            router.resolve(
                AgentRole.VISION,
                bindings = refs(AgentRole.VISION to "gpt-4o"),
                projectOverrides = refs(AgentRole.VISION to "deepseek-reasoner"),
            )
        }
    }

    @Test
    fun `非视觉角色绑纯文本模型放行`() {
        val out = router.resolve(AgentRole.CHORE, bindings = refs(AgentRole.CHORE to "deepseek-chat"))
        assertEquals("deepseek-chat", out.model)
    }

    @Test
    fun `空绑定回落指定预设默认`() {
        assertEquals("deepseek-chat", router.resolve(AgentRole.CHAT, emptyMap(), preset = Preset.ECO).model)
        assertEquals("gpt-5", router.resolve(AgentRole.CHAT, emptyMap(), preset = Preset.QUALITY).model)
    }
}
