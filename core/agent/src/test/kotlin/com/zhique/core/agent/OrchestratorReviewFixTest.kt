package com.zhique.core.agent

import com.zhique.core.ai.ChatMessage
import com.zhique.core.ai.ChatRequest
import com.zhique.core.ai.StreamEvent
import com.zhique.core.ai.StopReason
import com.zhique.core.project.ProjectRepository
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/**
 * 审查修复回归：Important 1（kind 端到端）/ Important 3（取消透传）/
 * Important 4（批准挂起不烧轮数）/ Minor 5（markdown 链接不触发修正重试）/
 * Minor 6（非 AiError 留痕）/ Minor 8（overBudget 排除口径）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OrchestratorReviewFixTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val testKey = "test-" + "f".repeat(12)

    private fun toolText(name: String, argsJson: String): List<StreamEvent> =
        listOf(
            StreamEvent.ContentDelta("处理\n```json\n[{\"tool\":\"$name\",\"args\":$argsJson}]\n```"),
            StreamEvent.Done(StopReason.STOP),
        )

    private fun plainText(text: String): List<StreamEvent> =
        listOf(StreamEvent.ContentDelta(text), StreamEvent.Done(StopReason.STOP))

    private fun repo(): ProjectRepository = ProjectRepository(tmp.root)

    private fun assembler(template: ChatRequest): MemoryContextAssembler =
        MemoryContextAssembler(
            budget = ContextBudget(contextLimit = 1_000_000),
            requestTemplate = template,
        )

    private fun template() = ChatRequest(
        baseUrl = "https://example.invalid",
        apiKey = testKey,
        model = "test-model",
        messages = emptyList(),
        maxTokens = 2048,
    )

    private fun ctx(projectId: String, asm: MemoryContextAssembler, autoApproved: Boolean = true): AgentContext =
        AgentContext(
            projectId = projectId,
            toolCtx = ToolContext(
                projectId = projectId,
                web = NoopWeb,
                repo = repo(),
                history = repo().history,
                vision = false,
            ),
            assembler = asm,
            autoApproved = autoApproved,
        )

    private object NoopWeb : WebControl {
        override val running = false
        override suspend fun run() {}
        override suspend fun stop() {}
        override suspend fun reload() {}
        override suspend fun consoleProblems() = emptyList<com.zhique.core.web.debug.TimelineEntry>()
        override suspend fun domSummary(): String? = null
        override suspend fun screenshotDataUrl(): String? = null
    }

    private class Scripts(vararg items: List<StreamEvent>) {
        val queue = ArrayDeque<List<StreamEvent>>(items.toList())
    }

    // ---- Important 1：kind 端到端 ----

    @Test
    fun `Important1_工具结果标TOOL_RESULT_截断且不计usage`() = runTest {
        val meta = repo().create("demo", "<html></html>")
        repo().writeFile(meta.id, "big.js", "const big = \"" + "Q".repeat(40 * 1024) + "\";\n")
        val asm = assembler(template())
        val scripts = Scripts(
            toolText("read_file", """{"path":"big.js"}"""),
            plainText("结束"),
        )
        val orch = Orchestrator(
            llm = { _ ->
                flow {
                    val script = scripts.queue.removeFirstOrNull() ?: error("脚本耗尽")
                    script.forEach { emit(it) }
                }
            },
            tools = ToolRegistry(defaultTools()),
            budget = Budget(maxRounds = 5).also { it.start() },
        )
        val events = mutableListOf<AgentEvent>()
        orch.run("看大文件", ctx(meta.id, asm)).collect { events += it }

        // 工具结果轮标 TOOL_RESULT
        val toolTurn = asm.turnsSnapshot().single { it.role == "tool" }
        assertEquals(Turn.Kind.TOOL_RESULT, toolTurn.kind)
        // 40KB 结果不计入长期预算（usage 只剩系统件基线，远小于 1 轮）
        assertTrue(asm.usage() < 0.1f, "usage=${asm.usage()}")
        // 组装时只带滚动窗口、单条截断
        val req = asm.build("看大文件", round = 2)
        val toolMsg = req.messages.first { it.role == "tool" }
        assertTrue(toolMsg.content.length < 3000, "截断后 ${toolMsg.content.length}")
        assertTrue(events.filterIsInstance<AgentEvent.StepResult>().single().ok)
    }

    // ---- Important 3：runFixRetry 取消透传 ----

    @Test
    fun `Important3_修正重试中的取消不被吞`() = runTest {
        var calls = 0
        val chat: suspend (ChatRequest) -> Flow<StreamEvent> = { _ ->
            flow {
                calls++
                if (calls == 1) {
                    // 第一轮：围栏残破 → 触发修正重试
                    emit(StreamEvent.ContentDelta("嗯\n```json\n[{\"tool\": broken\n```"))
                    emit(StreamEvent.Done(StopReason.STOP))
                } else {
                    throw CancellationException("用户取消")
                }
            }
        }
        val asm = assembler(template())
        val orch = Orchestrator(
            llm = chat,
            tools = ToolRegistry(defaultTools()),
            budget = Budget().also { it.start() },
        )
        assertFailsWith<CancellationException> {
            orch.run("目标", ctx("p1", asm)).collect { }
        }
        assertEquals(2, calls, "取消发生在修正重试请求")
    }

    // ---- Important 4：批准挂起不烧轮数/预算 ----

    @Test
    fun `Important4_AwaitConfirm真挂起_批准后恢复_拒绝回写`() = runTest {
        val meta = repo().create("demo", "<html></html>")
        val asm = assembler(template())
        val scripts = Scripts(
            toolText("push", """{"remote":"origin"}"""),
            toolText("push", """{"remote":"origin"}"""),
            plainText("结束"),
        )
        val gate = ConfirmGate()
        val orch = Orchestrator(
            llm = { _ ->
                flow {
                    val script = scripts.queue.removeFirstOrNull() ?: error("脚本耗尽")
                    script.forEach { emit(it) }
                }
            },
            tools = ToolRegistry(defaultTools()),
            budget = Budget(maxRounds = 9).also { it.start() },
            confirmGate = gate,
        )
        val events = mutableListOf<AgentEvent>()
        launch(UnconfinedTestDispatcher()) {
            orch.run("发布", ctx(meta.id, asm, autoApproved = false)).collect { events += it }
        }
        runCurrent()
        advanceUntilIdle()

        // 挂起在第一轮：不烧轮数，模型不会被再次调用（脚本停在第二个）
        assertTrue(events.any { it is AgentEvent.AwaitConfirm })
        assertEquals(1, orch.budgetView.roundsUsed, "挂起期间不消耗轮数")
        val tokensAtSuspend = orch.budgetView.usedTokens
        assertEquals(2, scripts.queue.size, "挂起期间模型未被再次调用（第二个脚本未消费）")

        // 拒绝 → 回写 + 恢复循环
        assertTrue(gate.denyCurrent())
        advanceUntilIdle()
        assertTrue(
            asm.turnsSnapshot().any { it.content.contains("用户拒绝了 push") },
            "拒绝须回写会话记忆",
        )
        assertTrue(events.none { it is AgentEvent.StepResult && it.tool == "push" && it.ok }, "拒绝不得执行")

        // 模型再次请求 push → 批准 → 真执行（NotReady）
        runCurrent()
        assertNotNull(gate.pendingCall, "第二次 push 再次挂起等待批准")
        assertEquals(2, orch.budgetView.roundsUsed, "拒绝恢复后仅多走 1 轮（挂起本身不耗轮）")
        assertTrue(gate.approveCurrent())
        advanceUntilIdle()
        val ok = events.filterIsInstance<AgentEvent.StepResult>().single { it.tool == "push" && it.ok }
        assertTrue(ok.ok && ok.detail.contains("NotReady"))
        assertTrue(asm.turnsSnapshot().any { it.content.contains("用户已批准") && it.content.contains("push") })
        assertTrue(events.last() is AgentEvent.Finished)
        assertEquals(3, orch.budgetView.roundsUsed, "无挂起外的额外轮次（2 次推送轮 + 收束轮）")
    }

    // ---- Minor 5：无围栏分支防 markdown 链接误触发 ----

    @Test
    fun `Minor5_markdown链接不触发修正重试`() = runTest {
        val meta = repo().create("demo", "<html></html>")
        val asm = assembler(template())
        val scripts = Scripts(plainText("参见 [文档](https://example.com) 即可"))
        var calls = 0
        val orch = Orchestrator(
            llm = { _ ->
                flow {
                    calls++
                    scripts.queue.removeFirstOrNull()!!.forEach { emit(it) }
                }
            },
            tools = ToolRegistry(defaultTools()),
            budget = Budget().also { it.start() },
        )
        val events = mutableListOf<AgentEvent>()
        orch.run("目标", ctx(meta.id, asm)).collect { events += it }
        assertEquals(1, calls, "无围栏纯文本直接收束，不得重试")
        assertTrue(events.last() is AgentEvent.Finished)
    }

    // ---- Minor 6：非 AiError 留痕 ----

    @Test
    fun `Minor6_网络直抛出Failed且审计留痕`() = runTest {
        val meta = repo().create("demo", "<html></html>")
        val asm = assembler(template())
        val orch = Orchestrator(
            llm = { _ -> flow { throw IllegalStateException("socket reset") } },
            tools = ToolRegistry(defaultTools()),
            budget = Budget().also { it.start() },
        )
        val events = mutableListOf<AgentEvent>()
        orch.run("目标", ctx(meta.id, asm)).collect { events += it }
        val failed = events.filterIsInstance<AgentEvent.Failed>().single()
        assertTrue(failed.message.contains("socket reset"))
        val audit = repo().history.readAudit(meta.id)
        assertTrue(audit.any { it.action == "orchestrator:error" && it.detail.contains("socket reset") })
        assertNull(asm.turnsSnapshot().firstOrNull { it.kind == Turn.Kind.SUMMARY })
    }

    // ---- Minor 8：overBudget 排除口径 ----

    @Test
    fun `Minor8_预算口径排除工具结果_普通轮次不被挤掉`() = runTest {
        // workLimit = 300*0.75 = 225 tokens；两条普通轮 ≈ 100 tokens 可容纳
        val asm = MemoryContextAssembler(
            budget = ContextBudget(contextLimit = 300),
            requestTemplate = template(),
        )
        asm.appendTurn("assistant", "普通轮一" + "x".repeat(60))
        asm.appendTurn("assistant", "普通轮二" + "y".repeat(60))
        // 40KB 工具结果（滚动窗口）：旧口径会挤爆预算把普通轮丢光
        asm.appendTurn("tool", "read_file 结果：${"Q".repeat(40 * 1024)}", kind = Turn.Kind.TOOL_RESULT)
        val req = asm.build("目标", round = 1)
        val all = req.messages.joinToString("\n") { it.content }
        assertTrue(all.contains("普通轮一"), "普通轮不得被工具结果挤出预算")
        assertTrue(all.contains("普通轮二"))
        // 口径统一：长期预算 tokens 不含 40KB 工具结果（旧口径 > 20000 tokens）
        assertTrue(asm.estimateTokens() < 1000, "estimateTokens=${asm.estimateTokens()}")
        assertTrue(asm.usage() < 1.0f, "usage 含系统基线，仅工具结果不计入：${asm.usage()}")
    }

    // ---- Minor 7 在 FileMapReviewFixTest ----

    @Test
    fun `Json解析辅助_参数content替换`() {
        val merged = Orchestrator.withContent("""{"path":"a.js","content":"旧"}""", "新")
        val obj = Json.parseToJsonElement(merged).jsonObject
        assertEquals("a.js", obj["path"]!!.jsonPrimitive.content)
        assertEquals("新", obj["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `ConfirmGate重入逐出旧挂起不泄漏`() = runTest {
        // M4 债务收敛：await 重入时旧 pending 被原子逐出并按拒绝完成，其 awaiter 不悬挂
        val gate = ConfirmGate()
        val c1 = com.zhique.core.ai.ToolCall("1", "push", "{}")
        val c2 = com.zhique.core.ai.ToolCall("2", "push", "{}")
        val first = async(start = CoroutineStart.UNDISPATCHED) { gate.await(c1) }
        val second = async(start = CoroutineStart.UNDISPATCHED) { gate.await(c2) }
        runCurrent()
        assertEquals(c2, gate.pendingCall, "后入者占住闸")
        assertEquals(false, first.await(), "被逐出的旧挂起按拒绝恢复，不永久挂起")
        assertTrue(gate.approveCurrent())
        assertEquals(true, second.await())
    }
}
