package com.zhique.runner.agent

import com.zhique.core.agent.AgentEvent
import com.zhique.core.ai.ChatRequest
import com.zhique.core.ai.StreamEvent
import com.zhique.core.ai.StopReason
import com.zhique.core.project.ProjectRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/** 规格审查缺口 3 + 警告 4/6：上下文手动覆盖、拒绝/待批回写、全自动开关。 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentReviewFixTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val testKey = "test-" + "r".repeat(12)

    private fun toolText(name: String, argsJson: String = "{}"): List<StreamEvent> =
        listOf(
            StreamEvent.ContentDelta("处理\n```json\n[{\"tool\":\"$name\",\"args\":$argsJson}]\n```"),
            StreamEvent.Done(StopReason.STOP),
        )

    private fun plainText(text: String): List<StreamEvent> =
        listOf(StreamEvent.ContentDelta(text), StreamEvent.Done(StopReason.STOP))

    private class Scripts(vararg items: List<StreamEvent>) {
        val queue = ArrayDeque<List<StreamEvent>>(items.toList())
    }

    private fun controller(
        scope: CoroutineScope,
        scripts: Scripts,
        repo: ProjectRepository,
        projectId: String,
        contextLimit: Int = 100_000,
    ): AgentController = AgentController(
        scope = scope,
        io = UnconfinedTestDispatcher(),
        deps = AgentController.Deps(
            projectId = projectId,
            projectName = "星空",
            repo = repo,
            vision = false,
            contextLimit = contextLimit,
            llm = { _ ->
                flow {
                    val script = scripts.queue.removeFirstOrNull() ?: error("脚本耗尽")
                    script.forEach { emit(it) }
                }
            },
            fastChat = { _ -> flow { emit(StreamEvent.Done(StopReason.STOP)) } },
            template = ChatRequest(baseUrl = "https://example.invalid", apiKey = testKey, model = "m", messages = emptyList(), maxTokens = 1024),
            fastTemplate = ChatRequest(baseUrl = "https://example.invalid", apiKey = testKey, model = "m", messages = emptyList(), maxTokens = 4096),
        ),
    )

    @Test
    fun `缺口3_上下文上限手动覆盖对Agent生效`() = runTest {
        val repo = ProjectRepository(tmp.root)
        val meta = repo.create("星空", "<html></html>")
        val scripts = Scripts(plainText("结束"))
        val scope = CoroutineScope(UnconfinedTestDispatcher())
        val c = controller(scope, scripts, repo, meta.id, contextLimit = 123_456)
        c.setGoal("目标")
        c.start()
        advanceUntilIdle()
        val budget = c.currentAssembler()?.budget
        assertNotNull(budget)
        assertEquals(123_456, budget.contextLimit, "Provider 手动上限第三层填充生效")
        assertEquals((123_456 * 0.75).toInt(), budget.workLimit)
    }

    @Test
    fun `警告4_待批与拒绝均回写会话记忆`() = runTest {
        val repo = ProjectRepository(tmp.root)
        val meta = repo.create("星空", "<html></html>")
        val scripts = Scripts(
            toolText("push", """{"remote":"origin"}"""),
            plainText("结束"),
        )
        val scope = CoroutineScope(UnconfinedTestDispatcher())
        val c = controller(scope, scripts, repo, meta.id)
        c.setGoal("推送")
        c.start()
        advanceUntilIdle()

        // 待批回写：模型已知「批准前不得再次发起」
        assertNotNull(c.state.value.awaitConfirm)
        val asm = c.currentAssembler()
        assertNotNull(asm)
        assertTrue(
            asm.turnsSnapshot().any { it.content.contains("已提交用户批准") && it.content.contains("push") },
            "待批状态须回写 assembler",
        )

        c.deny()
        assertNull(c.state.value.awaitConfirm)
        assertTrue(
            asm.turnsSnapshot().any { it.content.contains("用户拒绝了 push") },
            "拒绝须回写 assembler，防模型重复发起烧预算",
        )
    }

    @Test
    fun `警告6_全自动开关默认关_开时外发工具免批准`() = runTest {
        val repo = ProjectRepository(tmp.root)
        val meta = repo.create("星空", "<html></html>")
        val scripts = Scripts(
            toolText("push"),
            plainText("结束"),
        )
        val scope = CoroutineScope(UnconfinedTestDispatcher())
        val c = controller(scope, scripts, repo, meta.id)
        assertFalse(c.state.value.autoApproved, "默认关")
        c.setGoal("推送")
        c.start()
        advanceUntilIdle()
        assertNotNull(c.state.value.awaitConfirm, "默认关时 push 须出批准卡")
        c.stop()

        // 开：重开会话，push 直接执行（NotReady 占位仍是成功步）
        val scripts2 = Scripts(
            toolText("push"),
            plainText("结束"),
        )
        val c2 = controller(scope, scripts2, repo, meta.id)
        c2.setAutoApproved(true)
        assertTrue(c2.state.value.autoApproved)
        c2.setGoal("推送")
        c2.start()
        advanceUntilIdle()
        assertNull(c2.state.value.awaitConfirm, "全自动模式下不得出批准卡")
        val step = c2.state.value.steps.single { it.tool == "push" }
        assertTrue(step.ok == true && step.detail.contains("NotReady"))
    }
}
