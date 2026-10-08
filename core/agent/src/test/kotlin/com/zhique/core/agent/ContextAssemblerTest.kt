package com.zhique.core.agent

import com.zhique.core.ai.ChatMessage
import com.zhique.core.ai.ChatRequest
import com.zhique.core.ai.ModelCatalog
import com.zhique.core.ai.StreamEvent
import com.zhique.core.ai.StopReason
import com.zhique.core.project.ProjectRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/** Task 4.3：三层记忆（Memory/FileMap/ContextAssembler）+ 上下文压缩（ContextBudget/Compactor）。 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContextAssemblerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val testKey = "test-" + "m".repeat(12)

    private fun repo(): ProjectRepository = ProjectRepository(tmp.root)

    private fun memory(): Memory = Memory(tmp.newFile("global-memory.md"), repo())

    private fun fastChatOf(reply: String): suspend (ChatRequest) -> Flow<StreamEvent> = { req ->
        flow {
            emit(StreamEvent.ContentDelta(reply))
            emit(StreamEvent.Done(StopReason.STOP))
        }
    }

    private fun template(model: String = "test-model") = ChatRequest(
        baseUrl = "https://example.invalid",
        apiKey = testKey,
        model = model,
        messages = emptyList(),
        maxTokens = 2048,
    )

    private fun budget(limit: Int = 100_000) = ContextBudget(contextLimit = limit)

    private fun assembler(
        limit: Int = 100_000,
        memory: Memory? = null,
        projectId: String? = null,
        fileMap: String = "",
        autoCompact: (suspend (MemoryContextAssembler) -> Unit)? = null,
        autoScope: CoroutineScope? = null,
        threshold: Float = ContextBudget.DEFAULT_AUTO_THRESHOLD,
    ): MemoryContextAssembler {
        val b = ContextBudget(contextLimit = limit, autoThreshold = threshold)
        return MemoryContextAssembler(
            budget = b,
            requestTemplate = template(),
            memory = memory,
            projectId = projectId,
            fileMapProvider = { fileMap },
            autoCompact = autoCompact,
            autoScope = autoScope,
        )
    }

    // ---- 组装顺序 ----

    @Test
    fun `组装顺序_系统规范_项目记忆_任务目标_文件地图_报错_轮次`() = runTest {
        val m = memory()
        m.setGlobal("全局约定：中文回复")
        val r = repo()
        val p = r.create("demo", "<html></html>")
        r.writeFile(p.id, "zhique.md", "- 项目约定：单文件输出")
        val a = assembler(memory = m, projectId = p.id, fileMap = "- index.html\n  id: sky")
        a.appendError("js_error: boom")
        a.appendTurn("assistant", "我先看看")
        a.appendTurn("user", "继续")

        val req = a.build("修好星空", round = 1)
        val system = req.messages.first()
        assertEquals("system", system.role)
        val idxSpec = system.content.indexOf("系统规范")
        val idxGlobal = system.content.indexOf("全局约定")
        val idxProject = system.content.indexOf("项目约定")
        val idxMap = system.content.indexOf("文件地图")
        val idxError = system.content.indexOf("boom")
        assertTrue(idxSpec < idxGlobal && idxGlobal < idxProject && idxProject < idxMap && idxMap < idxError,
            "组装顺序应为 系统规范>项目记忆>文件地图>报错时间线")
        val goalMsg = req.messages.first { it.role == "user" }
        assertTrue(goalMsg.content.contains("修好星空"), "任务目标原文必须在")
        assertEquals(listOf("assistant", "user"), req.messages.drop(2).map { it.role })
    }

    @Test
    fun `超预算从尾部丢_任务目标永在`() = runTest {
        // workLimit = 300*0.75 = 225 tokens；系统+目标+文件地图+6 轮超出，触发从尾部丢
        val a = assembler(limit = 300, fileMap = "FILE_MAP_MARKER_" + "x".repeat(300))
        repeat(6) { a.appendTurn("assistant", "turn$it " + "y".repeat(80)) }
        val req = a.build("目标原文在此", round = 1)
        val all = req.messages.joinToString("\n") { it.content }
        assertTrue(all.contains("目标原文在此"), "任务目标永在")
        assertTrue(all.contains("系统规范"), "系统规范永在")
        assertTrue("turn5" in all, "近期轮次保留")
        assertFalse("turn0" in all, "最早轮次从尾部丢弃")
    }

    @Test
    fun `⭐标记结论裁剪永不掉`() = runTest {
        val a = assembler(limit = 1000)
        a.appendTurn("assistant", "关键结论⭐：根因是 canvas 尺寸为 0", starred = true)
        repeat(8) { a.appendTurn("assistant", "turn$it " + "z".repeat(120)) }
        val req = a.build("目标", round = 1)
        val all = req.messages.joinToString("\n") { it.content }
        assertTrue(all.contains("根因是 canvas 尺寸为 0"), "⭐结论不得被裁剪")
    }

    // ---- read_file/grep 滚动窗口 ----

    @Test
    fun `工具结果滚动窗口_不计入长期预算且限量截断`() = runTest {
        val a = assembler(limit = 100_000)
        repeat(10) {
            a.appendTurn("tool", "read_file 结果：#$it ${"Q".repeat(8000)}", kind = Turn.Kind.TOOL_RESULT)
        }
        assertTrue(a.usage() < 0.8f, "工具结果不计入长期预算，usage=${a.usage()}")
        val req = a.build("目标", round = 1)
        val toolMsgs = req.messages.filter { it.role == "user" && it.content.startsWith("[工具回执] ") }
        assertTrue(toolMsgs.size <= MemoryContextAssembler.TOOL_WINDOW, "滚动窗口限量")
        assertTrue(toolMsgs.first().content.length < 3000, "单条工具结果截断")
        assertTrue(toolMsgs.last().content.contains("#9"), "保留最近一条")
    }

    // ---- 自动压缩 ----

    @Test
    fun `用量达80%自动触发且不打断当前轮`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var compacted = 0
        val scope = CoroutineScope(UnconfinedTestDispatcher())
        val a = assembler(
            limit = 1000,
            autoCompact = { asm ->
                gate.await()
                Compactor(fastChatOf("要点"), template()).compactNow(asm, manual = false)
                compacted++
            },
            autoScope = scope,
        )
        repeat(4) { a.appendTurn("assistant", "早期$it " + "p".repeat(120)) }
        // workLimit = 1000*0.75 = 750 tokens；~850 字符 ≈ 425 tokens，累计 ≥ 0.8 → 触发
        a.appendTurn("assistant", "g".repeat(850))
        // append 立即返回（压缩在后台、被 gate 拦住）：当前轮不被打断
        a.appendTurn("user", "当前轮继续输入不受阻塞")
        assertEquals(0, compacted, "压缩在后台，尚未执行")
        gate.complete(Unit)
        assertEquals(1, compacted)
        assertFalse(a.turnsSnapshot().any { it.content.startsWith("早期0") }, "最早期轮次已被压缩替换")
        assertTrue(a.turnsSnapshot().any { it.kind == Turn.Kind.SUMMARY })
    }

    @Test
    fun `未达阈值不自动触发`() = runTest {
        var triggered = false
        val a = assembler(
            limit = 100_000,
            autoCompact = { triggered = true },
            autoScope = CoroutineScope(UnconfinedTestDispatcher()),
        )
        a.appendTurn("user", "短消息")
        assertFalse(triggered)
    }

    // ---- FileMap ----

    @Test
    fun `FileMap结构树与符号索引且单文件不超2KB`() {
        val map = FileMap.build(
            listOf(
                "index.html" to """<html><body><canvas id="sky" class="star-field"></canvas></body></html>""",
                "app.js" to "function paint() {}\nconst canvas = document.getElementById('sky');\nlet frame = 0;\nclass Painter {}",
            ),
        )
        assertTrue("index.html" in map && "app.js" in map)
        assertTrue("id: sky" in map, "HTML→id 索引")
        assertTrue("class: star-field" in map, "HTML→class 索引")
        assertTrue("paint" in map && "canvas" in map, "JS→function/const 名")
        assertTrue("Painter" in map, "JS→class 名")
        map.split("\n- ").forEach { section ->
            assertTrue(section.length <= FileMap.PER_FILE_CAP + 32, "单文件段超 2KB")
        }
    }

    @Test
    fun `FileMap超长内容截断到上限`() {
        val map = FileMap.build(listOf("big.js" to ("const a = 1;\n".repeat(2000))))
        assertTrue(map.length < FileMap.PER_FILE_CAP + 200, "超长符号索引必须截断：${map.length}")
    }
}

/** Compactor 侧测试（手动/不变量/摘要卡/上限解析）。 */
@OptIn(ExperimentalCoroutinesApi::class)
class CompactorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val testKey = "test-" + "c".repeat(12)

    private fun fastChatOf(reply: String): suspend (ChatRequest) -> Flow<StreamEvent> = { req ->
        flow {
            emit(StreamEvent.ContentDelta(reply))
            emit(StreamEvent.Done(StopReason.STOP))
        }
    }

    private fun template() = ChatRequest(
        baseUrl = "https://example.invalid",
        apiKey = testKey,
        model = "deepseek-chat",
        messages = emptyList(),
        maxTokens = ModelCatalog.FAST_LOOP_MAX_OUTPUT,
    )

    private fun newAssembler(limit: Int = 100_000): MemoryContextAssembler = MemoryContextAssembler(
        budget = ContextBudget(contextLimit = limit),
        requestTemplate = template(),
    )

    private fun fill(a: MemoryContextAssembler) {
        a.appendTurn("assistant", "早期轮次一：" + "a".repeat(200))
        a.appendTurn("assistant", "早期轮次二：" + "b".repeat(200))
        a.appendTurn("assistant", "关键结论⭐：报错来自 requestAnimationFrame 未判空", starred = true)
        a.appendTurn("assistant", "中期轮次：" + "c".repeat(200))
        a.appendTurn("assistant", "近期一")
        a.appendTurn("assistant", "近期二")
        a.appendTurn("assistant", "近期三")
        a.appendTurn("assistant", "近期四（最后一条）")
    }

    @Test
    fun `手动压缩立即执行且不受阈值限制`() = runTest {
        val compactor = Compactor(fastChatOf("要点摘要"), template())
        val a = newAssembler()
        fill(a)
        assertTrue(a.usage() < 0.8f, "用量低于阈值，手动仍可压缩")
        val report = compactor.compactNow(a, manual = true)
        assertTrue(report != null && report.manual)
    }

    @Test
    fun `压缩不变量_目标_星标_最近4轮原文永在`() = runTest {
        val compactor = Compactor(fastChatOf("被压缩轮次的要点"), template())
        val a = newAssembler()
        fill(a)
        compactor.compactNow(a)
        val req = a.build("修复星空动画", round = 1)
        val all = req.messages.joinToString("\n") { it.content }
        assertTrue(all.contains("修复星空动画"), "任务目标原文")
        assertTrue(all.contains("报错来自 requestAnimationFrame 未判空"), "⭐标记结论原文")
        assertTrue(all.contains("近期一") && all.contains("近期四（最后一条）"), "最近 4 轮原文")
        assertTrue(all.contains("被压缩轮次的要点"), "压缩摘要在场")
        assertFalse(all.contains("早期轮次一：aaa"), "被压缩轮次原文已被替换")
    }

    @Test
    fun `压缩摘要卡数据_kept_dropped_before_after`() = runTest {
        val compactor = Compactor(fastChatOf("要点"), template())
        val a = newAssembler()
        fill(a)
        val report = compactor.compactNow(a)!!
        assertTrue(report.before > report.after, "压缩后必须变小：${report.before} → ${report.after}")
        assertTrue(report.kept.any { it.contains("任务目标") })
        assertTrue(report.kept.any { it.contains("⭐") })
        assertTrue(report.kept.any { it.contains("4") }, "最近 4 轮说明")
        assertEquals(3, report.dropped.size, "早期非星标轮次被压缩（星标除外）")
        assertTrue(report.dropped.first().contains("早期轮次一"))
    }

    @Test
    fun `轮次不足时不压缩`() = runTest {
        val compactor = Compactor(fastChatOf("要点"), template())
        val a = newAssembler()
        a.appendTurn("user", "只有一句")
        assertNull(compactor.compactNow(a))
    }

    @Test
    fun `压缩请求用快循环4096上限`() = runTest {
        val seen = mutableListOf<ChatRequest>()
        val chat: suspend (ChatRequest) -> Flow<StreamEvent> = { req ->
            seen += req
            flow {
                emit(StreamEvent.ContentDelta("要点"))
                emit(StreamEvent.Done(StopReason.STOP))
            }
        }
        val compactor = Compactor(chat, template())
        val a = newAssembler()
        fill(a)
        compactor.compactNow(a)
        assertEquals(ModelCatalog.FAST_LOOP_MAX_OUTPUT, seen.single().maxTokens, "快循环 4096 常量消费点")
        assertTrue(seen.single().messages.first().content.contains("⭐"), "压缩提示词固定要求保留星标结论")
    }

    @Test
    fun `上限三层解析_手动_目录_默认`() {
        // 手动 > 0 生效
        assertEquals(32_000, ContextBudget.resolve("gpt-4o", manual = 32_000).contextLimit)
        // 手动 0 = 按模型目录窗口
        assertEquals(65_536, ContextBudget.resolve("deepseek-chat", manual = 0).contextLimit)
        // 未收录模型走默认
        assertEquals(ModelCatalog.DEFAULT_CONTEXT_WINDOW, ContextBudget.resolve("mystery-model", manual = null).contextLimit)
        // 工作预算默认 75%
        val b = ContextBudget(contextLimit = 1000)
        assertEquals(750, b.workLimit)
        assertEquals(0.8f, b.autoThreshold)
    }

    // ---- Memory：zhique.md 读写回 ----

    @Test
    fun `会话结束写回zhique_md并去重`() = runTest {
        val repo = ProjectRepository(tmp.root)
        val p = repo.create("demo", "<html></html>")
        repo.writeFile(p.id, "zhique.md", "- 既有约定：单文件")
        val m = Memory(java.io.File(tmp.root, "global.md"), repo)
        m.addSessionNote("新约定：报错先看 console")
        m.addSessionNote("既有约定：单文件") // 与文件既有重复，不得重写
        m.writeBack(p.id)
        val md = repo.readFile(p.id, "zhique.md")
        assertTrue(md.contains("新约定：报错先看 console"))
        assertEquals(1, md.lines().count { it.contains("既有约定：单文件") }, "去重：不重复写回")
        // pending 清空后重复 writeBack 为 no-op
        m.addSessionNote("再一条")
        m.writeBack(p.id)
        m.writeBack(p.id)
        assertEquals(1, repo.readFile(p.id, "zhique.md").lines().count { it.contains("再一条") })
    }

    @Test
    fun `项目记忆缺失时为空`() = runTest {
        val repo = ProjectRepository(tmp.root)
        val p = repo.create("demo", "<html></html>")
        val m = Memory(java.io.File(tmp.root, "global.md"), repo)
        assertEquals("", m.projectText(p.id))
        assertEquals("", m.globalText())
    }
}
