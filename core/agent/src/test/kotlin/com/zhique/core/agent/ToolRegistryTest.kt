package com.zhique.core.agent

import com.zhique.core.ai.ToolCall
import com.zhique.core.agent.tools.DomSnapshot
import com.zhique.core.project.ProjectRepository
import com.zhique.core.web.debug.DebugEvent
import com.zhique.core.web.debug.TimelineEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/** Task 4.1：工具注册表与工具实现（vision 过滤/edit_file 未闭合检测/grep 行号/git NotReady）。 */
class ToolRegistryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val json = Json

    private class FakeWeb : WebControl {
        val actions = mutableListOf<String>()
        override var running = false
        var problems: List<TimelineEntry> = emptyList()
        var dom: String? = null
        var screenshot: String? = null

        override suspend fun run() {
            actions += "run"; running = true
        }

        override suspend fun stop() {
            actions += "stop"; running = false
        }

        override suspend fun reload() {
            actions += "reload"
        }

        override suspend fun consoleProblems(): List<TimelineEntry> = problems

        override suspend fun domSummary(): String? = dom

        override suspend fun screenshotDataUrl(): String? = screenshot
    }

    private fun repo(): ProjectRepository = ProjectRepository(tmp.root)

    private fun ctx(web: FakeWeb, vision: Boolean = true, projectId: String = "p1"): ToolContext {
        val repo = repo()
        return ToolContext(
            projectId = projectId,
            web = web,
            repo = repo,
            history = repo.history,
            vision = vision,
        )
    }

    private fun registry(): Pair<ToolRegistry, FakeWeb> = ToolRegistry(defaultTools()) to FakeWeb()

    private fun call(name: String, args: String = "{}") = ToolCall("call_1", name, args)

    // ---- vision 装配 ----

    @Test
    fun `注册表按vision过滤screenshot_page`() {
        val (reg, _) = registry()
        val textOnly = reg.visible(vision = false).map { it.name }
        assertFalse("screenshot_page" in textOnly, "纯文本模型不得装配 screenshot_page（防幻觉硬隔离）")
        assertTrue("screenshot_page" in reg.visible(vision = true).map { it.name })
        assertFalse("screenshot_page" in reg.schemas(vision = false).map { it.name })
    }

    @Test
    fun `schemas均为合法JSONSchema且描述非空`() {
        val (reg, _) = registry()
        reg.schemas(vision = true).forEach { s ->
            val parsed = json.parseToJsonElement(s.parametersJson).jsonObject
            assertTrue(parsed.containsKey("type"), "${s.name} 缺 type")
            assertTrue(s.description.isNotBlank(), "${s.name} 缺描述")
        }
        assertTrue(reg.schemas(vision = true).size >= 8, "工具数量不足：${reg.schemas(vision = true).size}")
    }

    // ---- Web 工具 ----

    @Test
    fun `run_stop_reload走WebControl`() = runTest {
        val (reg, web) = registry()
        reg.invoke(ctx(web), call("run"))
        reg.invoke(ctx(web), call("reload"))
        reg.invoke(ctx(web), call("stop"))
        assertEquals(listOf("run", "reload", "stop"), web.actions)
    }

    @Test
    fun `read_console返回问题流折叠计数`() = runTest {
        val (reg, web) = registry()
        web.problems = listOf(
            TimelineEntry(DebugEvent(1, 1, "js_error", message = "boom"), isProblem = true),
            TimelineEntry(DebugEvent(2, 2, "console", level = "error", text = "x"), isProblem = true, count = 3),
        )
        val out = reg.invoke(ctx(web), call("read_console")).jsonObject
        val problems = out["problems"].toString()
        assertTrue("boom" in problems)
        assertTrue("3" in problems, "折叠计数应保留：$problems")
        assertEquals(2, out["total"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `read_dom_snapshot截断至64KB`() = runTest {
        val (reg, web) = registry()
        web.dom = "x".repeat(70_000)
        val out = reg.invoke(ctx(web), call("read_dom_snapshot")).jsonObject
        val summary = out["summary"]!!.jsonPrimitive.content
        assertTrue(summary.length <= 64 * 1024, "DOM 摘要超上限：${summary.length}")
        assertTrue(summary.endsWith("…[截断]"))
    }

    @Test
    fun `read_dom_snapshot未加载返回空提示`() = runTest {
        val (reg, web) = registry()
        web.dom = null
        val out = reg.invoke(ctx(web), call("read_dom_snapshot")).jsonObject
        assertTrue(out["summary"]!!.jsonPrimitive.content.contains("无"))
    }

    @Test
    fun `screenshot_page返回图片dataUrl`() = runTest {
        val (reg, web) = registry()
        web.screenshot = "data:image/png;base64,AAAA"
        val out = reg.invoke(ctx(web, vision = true), call("screenshot_page")).jsonObject
        assertEquals("data:image/png;base64,AAAA", out["image"]!!.jsonPrimitive.content)
    }

    @Test
    fun `screenshot_page对纯文本上下文拒绝`() = runTest {
        val (reg, web) = registry()
        assertFailsWith<ToolException> { reg.invoke(ctx(web, vision = false), call("screenshot_page")) }
    }

    // ---- 文件工具 ----

    @Test
    fun `edit_file整文件替换`() = runTest {
        val meta = repo().create("demo", "<html></html>")
        val (reg, web) = registry()
        val out = reg.invoke(
            ctx(web, projectId = meta.id),
            call("edit_file", """{"path":"index.html","content":"<html><body>hi</body></html>"}"""),
        ).jsonObject
        assertEquals("whole", out["mode"]!!.jsonPrimitive.content)
        assertEquals("<html><body>hi</body></html>", repo().readFile(meta.id, "index.html"))
    }

    @Test
    fun `edit_file行区间替换`() = runTest {
        val meta = repo().create("demo", "line1\nline2\nline3\n")
        val (reg, web) = registry()
        reg.invoke(
            ctx(web, projectId = meta.id),
            call("edit_file", """{"path":"index.html","startLine":2,"endLine":2,"content":"patched"}"""),
        )
        assertEquals("line1\npatched\nline3\n", repo().readFile(meta.id, "index.html"))
    }

    @Test
    fun `edit_file未闭合script抛UnclosedCode且携带partial`() = runTest {
        val meta = repo().create("demo", "<html><body></body></html>")
        val (reg, web) = registry()
        val e = assertFailsWith<Tool.UnclosedCode> {
            reg.invoke(
                ctx(web, projectId = meta.id),
                call("edit_file", """{"path":"index.html","content":"<html><body><script>const a=1;"}"""),
            )
        }
        assertTrue(e.partial.contains("const a=1"))
        assertTrue(e.message!!.contains("script"))
        assertEquals("<html><body></body></html>", repo().readFile(meta.id, "index.html"), "残缺补丁不得落盘")
    }

    @Test
    fun `edit_file未闭合JS括号抛UnclosedCode`() = runTest {
        val meta = repo().create("demo", "<html></html>")
        repo().writeFile(meta.id, "app.js", "const x = 1;\n")
        val (reg, web) = registry()
        assertFailsWith<Tool.UnclosedCode> {
            reg.invoke(
                ctx(web, projectId = meta.id),
                call("edit_file", """{"path":"app.js","content":"function f() { return {a:1;"}"""),
            )
        }
        assertEquals("const x = 1;\n", repo().readFile(meta.id, "app.js"))
    }

    @Test
    fun `闭合代码正常通过`() = runTest {
        val meta = repo().create("demo", "<html></html>")
        val (reg, web) = registry()
        reg.invoke(
            ctx(web, projectId = meta.id),
            call(
                "edit_file",
                """{"path":"app.js","content":"function f() { return {a: 1}; } // 注释 { 不计 }"}""",
            ),
        )
        assertTrue(repo().readFile(meta.id, "app.js").contains("function f()"))
    }

    @Test
    fun `list_files排除history且输出相对路径`() = runTest {
        val meta = repo().create("demo", "<html></html>")
        repo().writeFile(meta.id, "css/main.css", "body{}")
        val (reg, web) = registry()
        val out = reg.invoke(ctx(web, projectId = meta.id), call("list_files")).jsonObject
        val files = out["files"].toString()
        assertTrue("index.html" in files)
        assertTrue("css/main.css" in files)
        assertFalse("history" in files)
    }

    @Test
    fun `read_file拒绝沙箱逃逸`() = runTest {
        val meta = repo().create("demo", "<html></html>")
        val (reg, web) = registry()
        assertFailsWith<Exception> {
            reg.invoke(ctx(web, projectId = meta.id), call("read_file", """{"path":"../../secret"}"""))
        }
    }

    @Test
    fun `grep输出正则与行号格式`() = runTest {
        val meta = repo().create("demo", "<html><body><canvas id='sky'></canvas></body></html>")
        repo().writeFile(meta.id, "app.js", "const sky = 1;\nconst sun = 2;\n")
        val (reg, web) = registry()
        val out = reg.invoke(
            ctx(web, projectId = meta.id),
            call("grep", """{"pattern":"sky"}"""),
        ).jsonObject
        val matches = out["matches"].toString()
        assertTrue("app.js:1:const sky = 1;" in matches, "实际：$matches")
        assertEquals(2, out["total"]!!.jsonPrimitive.content.toInt(), "index.html 中的 id=sky 也命中")
    }

    // ---- git 类占位 ----

    @Test
    fun `git类工具NotReady占位且requiresConfirm`() = runTest {
        val (reg, web) = registry()
        assertTrue(reg["push"]!!.requiresConfirm, "外发动作默认逐项确认")
        assertTrue(reg["create_repo"]!!.requiresConfirm)
        val out = reg.invoke(ctx(web), call("push", """{"remote":"origin"}""")).jsonObject
        assertEquals("NotReady", out["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `未知工具抛ToolException`() = runTest {
        val (reg, web) = registry()
        assertFailsWith<ToolException> { reg.invoke(ctx(web), call("nope")) }
    }

    @Test
    fun `DomSnapshot脚本含摘要字段与截断保护`() {
        val js = DomSnapshot.js()
        assertTrue("tag" in js && "childrenCount" in js)
        assertNull(DomSnapshot.truncate(null))
        assertEquals("ab", DomSnapshot.truncate("ab"))
    }
}
