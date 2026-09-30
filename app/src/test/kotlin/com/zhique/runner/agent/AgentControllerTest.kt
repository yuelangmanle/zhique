package com.zhique.runner.agent

import com.zhique.core.agent.AgentEvent
import com.zhique.core.agent.Budget
import com.zhique.core.agent.Memory
import com.zhique.core.agent.WebControl
import com.zhique.core.ai.ChatRequest
import com.zhique.core.ai.StreamEvent
import com.zhique.core.ai.StopReason
import com.zhique.core.project.ProjectRepository
import com.zhique.core.web.debug.TimelineEntry
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/** Task 4.4：AgentController 生命周期（状态折算/回滚/压缩卡/批准/暂停续轮）。 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentControllerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val testKey = "test-" + "a".repeat(12)

    private fun toolText(name: String, argsJson: String = "{}"): List<StreamEvent> =
        listOf(
            StreamEvent.ContentDelta("我来处理\n```json\n[{\"tool\":\"$name\",\"args\":$argsJson}]\n```"),
            StreamEvent.Done(StopReason.STOP),
        )

    private fun plainText(text: String): List<StreamEvent> =
        listOf(StreamEvent.ContentDelta(text), StreamEvent.Done(StopReason.STOP))

    private class Scripts(vararg items: List<StreamEvent>) {
        val queue = ArrayDeque<List<StreamEvent>>(items.toList())
    }

    private class FakeWeb : WebControl {
        val actions = mutableListOf<String>()
        override val running = false
        override suspend fun run() {
            actions += "run"
        }

        override suspend fun stop() {}
        override suspend fun reload() {
            actions += "reload"
        }

        override suspend fun consoleProblems() = emptyList<TimelineEntry>()
        override suspend fun domSummary(): String? = null
        override suspend fun screenshotDataUrl(): String? = null
    }

    private fun controller(
        scope: CoroutineScope,
        scripts: Scripts,
        repo: ProjectRepository,
        projectId: String,
        toasts: MutableList<String> = mutableListOf(),
    ): AgentController = AgentController(
        scope = scope,
        io = UnconfinedTestDispatcher(),
        deps = AgentController.Deps(
            projectId = projectId,
            projectName = "星空",
            repo = repo,
            vision = false,
            llm = { req ->
                flow {
                    val script = scripts.queue.removeFirstOrNull() ?: error("脚本耗尽")
                    script.forEach { emit(it) }
                }
            },
            fastChat = { req ->
                flow {
                    emit(StreamEvent.ContentDelta("被压缩轮次的要点"))
                    emit(StreamEvent.Done(StopReason.STOP))
                }
            },
            template = ChatRequest(
                baseUrl = "https://example.invalid",
                apiKey = testKey,
                model = "test-model",
                messages = emptyList(),
                maxTokens = 2048,
            ),
            fastTemplate = ChatRequest(
                baseUrl = "https://example.invalid",
                apiKey = testKey,
                model = "test-model",
                messages = emptyList(),
                maxTokens = 4096,
            ),
            memory = Memory(File(tmp.root, "global.md"), repo),
            web = FakeWeb(),
            onToast = { toasts += it },
        ),
    )

    @Test
    fun `start到finished_轮_步骤_diff_快照齐备`() = runTest {
        val repo = ProjectRepository(tmp.root)
        val meta = repo.create("星空", "<html><body>v1</body></html>")
        val scripts = Scripts(
            toolText("edit_file", """{"path":"index.html","content":"<html><body>v2 fixed</body></html>"}"""),
            plainText("修好了，结束"),
        )
        val c = controller(CoroutineScope(UnconfinedTestDispatcher()), scripts, repo, meta.id)
        c.setGoal("修好星空")
        c.start()
        advanceUntilIdle()

        val s = c.state.value
        assertTrue(s.finished, "error=${s.error}")
        assertFalse(s.running)
        assertEquals(2, s.rounds.size)
        val step = s.steps.single { it.tool == "edit_file" }
        assertTrue(step.ok == true)
        val diff = step.diff
        assertNotNull(diff)
        assertEquals("index.html", diff.path)
        assertTrue(diff.lines.any { it.type == '-' }, "应有红行")
        assertTrue(diff.lines.any { it.type == '+' && it.text.contains("v2 fixed") }, "应有绿行")
        assertTrue(s.snapshots.isNotEmpty(), "编辑前快照在场")
        assertTrue(s.contextUsage > 0f, "上下文环真值")
    }

    @Test
    fun `回滚快照恢复编辑前内容`() = runTest {
        val repo = ProjectRepository(tmp.root)
        val meta = repo.create("星空", "<html><body>v1</body></html>")
        val scripts = Scripts(
            toolText("edit_file", """{"path":"index.html","content":"<html>v2</html>"}"""),
            plainText("结束"),
        )
        val scope = CoroutineScope(UnconfinedTestDispatcher())
        val c = controller(scope, scripts, repo, meta.id)
        c.setGoal("改")
        c.start()
        advanceUntilIdle()

        val snap = c.state.value.snapshots.single()
        c.rollback(snap.id)
        advanceUntilIdle()
        assertEquals("<html><body>v1</body></html>", repo.readFile(meta.id, "index.html"))
    }

    @Test
    fun `压缩上下文按钮产出摘要卡`() = runTest {
        val repo = ProjectRepository(tmp.root)
        val meta = repo.create("星空", "<html></html>")
        val scripts = Scripts(
            toolText("read_console"),
            toolText("read_console"),
            toolText("read_console"),
            plainText("结束"),
        )
        val c = controller(CoroutineScope(UnconfinedTestDispatcher()), scripts, repo, meta.id)
        c.setGoal("看看")
        c.start()
        advanceUntilIdle()
        assertNull(c.state.value.compression)
        c.compactNow()
        advanceUntilIdle()
        val report = c.state.value.compression
        assertNotNull(report, "有普通轮次可压缩（assistant 轮）")
        assertTrue(report.before > report.after, "${report.before} → ${report.after}")
    }

    @Test
    fun `外发工具出批准卡_批准执行NotReady_拒绝关闭`() = runTest {
        val repo = ProjectRepository(tmp.root)
        val meta = repo.create("星空", "<html></html>")
        val scripts = Scripts(
            toolText("push", """{"remote":"origin"}"""),
            plainText("结束"),
        )
        val scope = CoroutineScope(UnconfinedTestDispatcher())
        val toasts = mutableListOf<String>()
        val c = controller(scope, scripts, repo, meta.id, toasts)
        c.setGoal("推送")
        c.start()
        advanceUntilIdle()

        val confirm = c.state.value.awaitConfirm
        assertNotNull(confirm, "外发工具须出批准卡")
        assertEquals("push", confirm.tool)
        c.approve()
        advanceUntilIdle()
        assertNull(c.state.value.awaitConfirm)
        val step = c.state.value.steps.last { it.tool == "push" }
        assertTrue(step.ok == true, "git 占位返回 NotReady 仍是成功步：${step.detail}")
        assertTrue(step.detail.contains("NotReady"))
    }

    @Test
    fun `stop暂停可续5轮`() = runTest {
        val repo = ProjectRepository(tmp.root)
        val meta = repo.create("星空", "<html></html>")
        val gate = CompletableDeferred<Unit>()
        val scope = CoroutineScope(UnconfinedTestDispatcher())
        val blocking = AgentController(
            scope = scope,
            io = UnconfinedTestDispatcher(),
            deps = AgentController.Deps(
                projectId = meta.id,
                projectName = "星空",
                repo = repo,
                vision = false,
                llm = { _ -> flow { gate.await() } },
                fastChat = { _ -> flow { emit(StreamEvent.Done(StopReason.STOP)) } },
                template = ChatRequest(baseUrl = "https://example.invalid", apiKey = testKey, model = "m", messages = emptyList(), maxTokens = 1024),
                fastTemplate = ChatRequest(baseUrl = "https://example.invalid", apiKey = testKey, model = "m", messages = emptyList(), maxTokens = 1024),
                web = null,
            ),
        )
        blocking.setGoal("长任务")
        blocking.start()
        assertTrue(blocking.state.value.running)
        blocking.stop()
        assertFalse(blocking.state.value.running, "暂停后 running=false")
        assertTrue(blocking.state.value.error!!.contains("暂停"))

        // 续 5 轮：换普通脚本收束
        val scripts = Scripts(plainText("继续完成"))
        val resumed = controller(scope, scripts, repo, meta.id)
        resumed.setGoal("长任务")
        resumed.start()
        advanceUntilIdle()
        assertTrue(resumed.state.value.finished)
    }
}
