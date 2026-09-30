package com.zhique.core.agent

import com.zhique.core.ai.AiErrorException
import com.zhique.core.ai.ChatMessage
import com.zhique.core.ai.ChatRequest
import com.zhique.core.ai.ModelCatalog
import com.zhique.core.ai.StreamEvent
import com.zhique.core.ai.StopReason
import com.zhique.core.project.ProjectRepository
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/**
 * Task 4.2：编排循环与三道安全带（mock Provider 脚本驱动）。
 * 覆盖：目标完成/预算耗尽/工具报错恢复/纯文本无 screenshot/未闭合强制续写/
 * AwaitConfirm/快照回滚/审计重放/JSON 解析修正重试/用量记录/记忆写回。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OrchestratorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val testKey = "test-" + "k".repeat(12)

    /** 会话记忆口（4.3 的 Memory 实现；此处假实现验证写回时机）。 */
    private class FakeMemory : AgentMemory {
        var writeBacks = 0
        override suspend fun writeBack(projectId: String) {
            writeBacks++
        }
    }

    private class FakeAssembler(private val key: String) : ContextAssembler {
        val turns = mutableListOf<Pair<String, String>>()
        val errors = mutableListOf<String>()
        var lastGoal = ""

        override fun build(goal: String, round: Int): ChatRequest = ChatRequest(
            baseUrl = "https://example.invalid",
            apiKey = key,
            model = "test-model",
            messages = listOf(
                ChatMessage("system", "你是织雀 Agent"),
                ChatMessage("user", goal),
            ) + turns.map { ChatMessage(it.first, it.second) },
            maxTokens = 2048,
        ).also { lastGoal = goal }

        override fun appendTurn(role: String, content: String, starred: Boolean, kind: Turn.Kind) {
            turns += role to content
        }

        override fun appendError(line: String) {
            errors += line
        }

        override fun usage(): Float = 0.1f
    }

    private fun repo(): ProjectRepository = ProjectRepository(tmp.root)

    private fun toolCallText(name: String, argsJson: String): String =
        "我来处理\n```json\n[{\"tool\":\"$name\",\"args\":$argsJson}]\n```"

    private fun plainText(text: String): List<StreamEvent> =
        listOf(StreamEvent.ContentDelta(text), StreamEvent.Done(StopReason.STOP))

    private fun toolText(name: String, argsJson: String = "{}"): List<StreamEvent> =
        plainText(toolCallText(name, argsJson))

    /** 试图输出工具调用但 JSON 残破（解析失败 → 修正提示重试一次的输入）。 */
    private fun brokenFence(): List<StreamEvent> =
        plainText("我想想……\n```json\n[{\"tool\": broken\n```")

    private inner class Harness(
        val scripts: ArrayDeque<List<StreamEvent>>,
        val seen: MutableList<ChatRequest> = mutableListOf(),
    ) {
        val usage = mutableListOf<Int>()
        val memory = FakeMemory()
        val assembler = FakeAssembler(testKey)

        val chat: suspend (ChatRequest) -> Flow<StreamEvent> = { req ->
            flow {
                seen += req
                val script = scripts.removeFirstOrNull() ?: error("脚本耗尽")
                script.forEach { emit(it) }
            }
        }

        fun orchestrator(maxRounds: Int = 5, vision: Boolean = true, gate: ConfirmGate = ConfirmGate()): Orchestrator = Orchestrator(
            llm = chat,
            tools = ToolRegistry(defaultTools()),
            budget = Budget(maxRounds = maxRounds),
            recordUsage = { usage += it },
            confirmGate = gate,
        )

        fun ctx(projectId: String, vision: Boolean = true, autoApproved: Boolean = false): AgentContext =
            AgentContext(
                projectId = projectId,
                toolCtx = ToolContext(
                    projectId = projectId,
                    web = NoopWeb,
                    repo = repo(),
                    history = repo().history,
                    vision = vision,
                ),
                assembler = assembler,
                memory = memory,
                autoApproved = autoApproved,
            )

        suspend fun run(goal: String, projectId: String, maxRounds: Int = 5, vision: Boolean = true): List<AgentEvent> {
            val events = mutableListOf<AgentEvent>()
            orchestrator(maxRounds = maxRounds, vision = vision).run(goal, ctx(projectId, vision)).collect {
                events += it
            }
            return events
        }
    }

    private object NoopWeb : WebControl {
        override val running = false
        override suspend fun run() {}
        override suspend fun stop() {}
        override suspend fun reload() {}
        override suspend fun consoleProblems() = emptyList<com.zhique.core.web.debug.TimelineEntry>()
        override suspend fun domSummary(): String? = null
        override suspend fun screenshotDataUrl(): String? = null
    }

    // ---- 目标完成 ----

    @Test
    fun `无工具调用即完成并写回记忆`() = runTest {
        val h = Harness(ArrayDeque(listOf(plainText("页面已经正常，无需修改"))))
        val events = h.run("修好星空", projectId = "p1")
        assertTrue(events.last() is AgentEvent.Finished)
        assertEquals(1, h.memory.writeBacks, "会话结束必须写回 zhique.md")
        assertTrue(h.usage.single() > 0, "每轮应记录用量")
    }

    // ---- 预算 ----

    @Test
    fun `预算耗尽停在第N轮不再调模型`() = runTest {
        val h = Harness(
            ArrayDeque(
                listOf(
                    toolText("read_file", """{"path":"index.html"}"""),
                    toolText("read_console"),
                ),
            ),
        )
        val events = h.run("修好星空", projectId = "p1", maxRounds = 2)
        assertEquals(2, h.seen.size, "两轮预算只调两次模型")
        assertTrue(events.any { it is AgentEvent.BudgetHit })
        assertEquals(1, h.memory.writeBacks, "预算耗尽同样写回记忆")
    }

    @Test
    fun `三闸预算任一触顶即停`() = runTest {
        val budget = Budget(maxRounds = 5, maxTokens = 100, maxDurationMs = 1000, now = { 0L })
        budget.start()
        budget.beginRound()
        budget.recordTokens(150)
        assertTrue(budget.exhausted(), "token 闸触顶")
    }

    // ---- 工具报错恢复 ----

    @Test
    fun `工具报错记录错误时间线并继续下一轮`() = runTest {
        val h = Harness(
            ArrayDeque(
                listOf(
                    toolText("read_file", """{"path":"不存在.html"}"""),
                    plainText("已放弃该文件，结束"),
                ),
            ),
        )
        val events = h.run("修好星空", projectId = "p1")
        val failure = events.filterIsInstance<AgentEvent.StepResult>().single { !it.ok }
        assertTrue(failure.detail.contains("不存在"))
        assertTrue(h.assembler.errors.isNotEmpty(), "错误必须进报错时间线（下轮上下文）")
        assertTrue(events.last() is AgentEvent.Finished)
    }

    // ---- 纯文本模型硬隔离 ----

    @Test
    fun `纯文本上下文请求screenshot被拒且循环不断`() = runTest {
        val h = Harness(
            ArrayDeque(
                listOf(
                    toolText("screenshot_page"),
                    plainText("改用 DOM 摘要观察，结束"),
                ),
            ),
        )
        val events = h.run("修好星空", projectId = "p1", vision = false)
        val failure = events.filterIsInstance<AgentEvent.StepResult>().single { !it.ok }
        assertTrue(failure.detail.contains("screenshot"))
        assertTrue(events.last() is AgentEvent.Finished)
    }

    // ---- 未闭合强制续写（M3 遗留接线 d）----

    @Test
    fun `edit_file未闭合代码强制续写后重放`() = runTest {
        val meta = repo().create("demo", "<html><body></body></html>")
        val h = Harness(
            ArrayDeque(
                listOf(
                    toolText("edit_file", """{"path":"app.js","content":"function f() {"}"""),
                    plainText("\n  return 1;\n}"), // continueOnce 的续写段（续写器已去重衔接）
                    plainText("写入完成，结束"),
                ),
            ),
        )
        val events = h.run("补全 app.js 的 f 函数", projectId = meta.id)
        val okStep = events.filterIsInstance<AgentEvent.StepResult>().single { it.tool == "edit_file" }
        assertTrue(okStep.ok, "续写重放后应成功：${okStep.detail}")
        val written = repo().readFile(meta.id, "app.js")
        assertTrue(written.contains("function f()") && written.contains("}"), "实际：$written")
        // 续写请求必须使用续写段输出上限常量（M3 遗留接线 a 的生产消费点）
        val contReq = h.seen.first { it.maxTokens == ModelCatalog.CONTINUE_SEGMENT_MAX_OUTPUT }
        assertEquals(8192, contReq.maxTokens)
    }

    // ---- 外发动作确认 ----

    @Test
    fun `requiresConfirm工具发AwaitConfirm且挂起不执行_批准后恢复`() = runTest {
        val gate = ConfirmGate()
        val h = Harness(
            ArrayDeque(
                listOf(
                    toolText("push", """{"remote":"origin"}"""),
                    plainText("等待批准，结束"),
                ),
            ),
        )
        val orch = h.orchestrator(gate = gate)
        val events = mutableListOf<AgentEvent>()
        val collector = launch { orch.run("发布项目", h.ctx("p1")).collect { events += it } }
        runCurrent()
        val confirm = events.filterIsInstance<AgentEvent.AwaitConfirm>().single()
        assertEquals("push", confirm.tool)
        assertTrue(events.filterIsInstance<AgentEvent.StepResult>().isEmpty(), "未批准不得执行")
        assertEquals(1, orch.budgetView.roundsUsed, "挂起期间不消耗轮数")
        gate.approveCurrent()
        collector.join()
        val ok = events.filterIsInstance<AgentEvent.StepResult>().single { it.tool == "push" }
        assertTrue(ok.ok && ok.detail.contains("NotReady"))
        assertTrue(events.last() is AgentEvent.Finished)
    }

    @Test
    fun `全自动模式外发工具直接执行并返回NotReady占位`() = runTest {
        val h = Harness(
            ArrayDeque(
                listOf(
                    toolText("push"),
                    plainText("结束"),
                ),
            ),
        )
        val meta = repo().create("demo", "<html></html>")
        val ctx = h.ctx(meta.id, autoApproved = true)
        val events = mutableListOf<AgentEvent>()
        h.orchestrator().run("推送", ctx).collect { events += it }
        assertTrue(events.filterIsInstance<AgentEvent.StepResult>().single { it.tool == "push" }.ok)
    }

    // ---- 快照与审计 ----

    @Test
    fun `编辑前快照先行且可回滚任意步`() = runTest {
        val meta = repo().create("demo", "v1")
        val h = Harness(
            ArrayDeque(
                listOf(
                    toolText("edit_file", """{"path":"index.html","content":"v2"}"""),
                    toolText("edit_file", """{"path":"index.html","content":"v3"}"""),
                    plainText("结束"),
                ),
            ),
        )
        val events = h.run("迭代两版", projectId = meta.id)
        assertEquals("v3", repo().readFile(meta.id, "index.html"))
        val snaps = repo().history.list(meta.id)
        assertEquals(2, snaps.size, "每次编辑前各留一快照")
        // 回滚到第一步
        repo().history.restore(meta.id, snaps.first().id)
        assertEquals("v1", repo().readFile(meta.id, "index.html"))
        // 快照先行事件携带 snapshotId
        assertTrue(events.filterIsInstance<AgentEvent.Step>().all { it.snapshotId != null })
    }

    @Test
    fun `审计完整重放`() = runTest {
        val meta = repo().create("demo", "<html></html>")
        val h = Harness(
            ArrayDeque(
                listOf(
                    toolText("read_file", """{"path":"index.html"}"""),
                    toolText("edit_file", """{"path":"index.html","content":"<html>fixed</html>"}"""),
                    plainText("结束"),
                ),
            ),
        )
        h.run("修复", projectId = meta.id)
        val audit = repo().history.readAudit(meta.id, page = 0, pageSize = 50)
        assertTrue(audit.any { it.action.contains("read_file") && it.action.contains("ok") })
        assertTrue(audit.any { it.action.contains("edit_file") && it.action.contains("ok") })
    }

    // ---- 解析容错 ----

    @Test
    fun `JSON解析失败走修正提示重试一次`() = runTest {
        val h = Harness(
            ArrayDeque(
                listOf(
                    brokenFence(),
                    toolText("read_console"),
                    plainText("结束"),
                ),
            ),
        )
        val events = h.run("看看报错", projectId = "p1")
        assertTrue(events.filterIsInstance<AgentEvent.StepResult>().single { it.tool == "read_console" }.ok)
        assertEquals(3, h.seen.size, "解析失败 → 修正重试 → 下一轮")
        val fixReq = h.seen[1]
        assertTrue(fixReq.messages.last().content.contains("JSON"), "修正提示要求重新输出 JSON")
    }

    @Test
    fun `两次解析失败按纯文本收束`() = runTest {
        val h = Harness(
            ArrayDeque(
                listOf(
                    brokenFence(),
                    brokenFence(),
                ),
            ),
        )
        val events = h.run("目标", projectId = "p1")
        assertTrue(events.last() is AgentEvent.Finished)
    }

    // ---- 模型流内错误 ----

    @Test
    fun `模型流内错误出Failed事件`() = runTest {
        val h = Harness(
            ArrayDeque(listOf<List<StreamEvent>>(listOf(StreamEvent.Done(StopReason.ERROR("服务过载"))))),
        )
        val events = h.run("目标", projectId = "p1")
        val failed = events.filterIsInstance<AgentEvent.Failed>().single()
        assertTrue(failed.message.contains("过载"))
        assertEquals(1, h.memory.writeBacks)
    }

    // ---- 续5轮 ----

    @Test
    fun `续5轮后预算闸放宽`() {
        val budget = Budget(maxRounds = 2)
        budget.start()
        repeat(2) { budget.beginRound() }
        assertTrue(budget.exhausted())
        budget.extendRounds(5)
        assertTrue(!budget.exhausted(), "续 5 轮后可继续")
        assertEquals(7, budget.maxRounds)
    }
}
