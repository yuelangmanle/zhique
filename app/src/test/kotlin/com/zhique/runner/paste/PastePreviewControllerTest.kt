package com.zhique.runner.paste

import com.zhique.core.paste.Assembler
import com.zhique.core.paste.PasteConfidence
import com.zhique.core.project.ProjectMeta
import com.zhique.core.project.ProjectRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/** 粘贴预览控制器：解析→清洗→组装全在 IO 协程，UI 态 StateFlow（HomeController 同款模式）。 */
@OptIn(ExperimentalCoroutinesApi::class)
class PastePreviewControllerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var repo: ProjectRepository

    private class FakeAutoRun(var value: Boolean = false) : PasteAutoRunStore {
        override val autoRun: Flow<Boolean> get() = flowOf(value)
        override suspend fun setAutoRun(v: Boolean) { value = v }
    }

    private fun newController(
        onRun: (ProjectMeta) -> Unit = {},
        autoRunStore: PasteAutoRunStore? = null,
        onToast: (String) -> Unit = {},
    ): PastePreviewController {
        repo = ProjectRepository(tmp.root)
        val d = UnconfinedTestDispatcher()
        return PastePreviewController(
            repo = repo,
            scope = CoroutineScope(d),
            io = d,
            autoRunStore = autoRunStore,
            onToast = onToast,
            onRun = onRun,
        )
    }

    @Test
    fun `start解析完整html_置信度与默认名与透传`() {
        val c = newController()
        c.start("<!DOCTYPE html>\n<html><head><title>星空</title></head><body>hi</body></html>")
        val s = c.state.value
        assertEquals("完整 HTML", s.formLabel)
        assertTrue(s.confidence >= 0.8f)
        assertEquals("星空", s.name)
        assertTrue(s.assembledHtml.startsWith("<!DOCTYPE html>"))
        assertFalse(s.aiFallbackSuggested)
    }

    @Test
    fun `fragments清洗组装_骨架与报告`() {
        val c = newController()
        c.start(
            """
            ```html
            <div id="app"></div>
            ```
            ```css
            body { margin: 0; }
            ```
            ```javascript
            const app = document.getElementById("app");
            ```
            """.trimIndent(),
        )
        val s = c.state.value
        assertEquals("多代码块片段", s.formLabel)
        assertTrue("<script defer>" in s.assembledHtml)
        assertTrue("<style>" in s.assembledHtml)
        assertTrue(s.actions.isNotEmpty(), "应有清洗报告动作")
    }

    @Test
    fun `撤销清洗_原始输入重跑`() {
        val c = newController()
        val raw = "```js\nconst a = 1;\n```"
        c.start(raw)
        assertFalse("```" in c.state.value.assembledHtml, "清洗后不应残留围栏")
        c.setCleaning(false)
        assertTrue("```js" in c.state.value.assembledHtml, "撤销清洗=原始输入重跑")
        assertTrue(c.state.value.actions.isEmpty())
        c.setCleaning(true)
        assertFalse("```" in c.state.value.assembledHtml, "可恢复清洗")
    }

    @Test
    fun `命名编辑生效并入库`() {
        val c = newController()
        c.start("function () { console.log(1); }")
        c.setName("我的脚本")
        c.saveAsDraft()
        assertEquals(1, repo.list().size)
        assertEquals("我的脚本", repo.list()[0].name)
        assertEquals("我的脚本", c.state.value.name)
    }

    @Test
    fun `存为草稿_落盘内容与组装一致`() {
        val c = newController()
        c.start("body { color: red; }")
        c.saveAsDraft()
        val meta = repo.list().single()
        assertEquals(c.state.value.assembledHtml, repo.readFile(meta.id, "index.html"))
        var toasted: String? = null
        // 二次保存不重复（同名直接覆盖场景由项目级管理处理，这里只验证 toast 反馈）
        val c2 = newController(onToast = { toasted = it })
        c2.start("body { color: red; }")
        c2.saveAsDraft()
        assertTrue(toasted?.contains("草稿") == true)
    }

    @Test
    fun `运行回调onRun并落库`() {
        var ran: ProjectMeta? = null
        val c = newController(onRun = { ran = it })
        c.start("// 星空动画\nconst a = 1;")
        c.saveAndRun()
        assertTrue(ran != null)
        assertEquals(1, repo.list().size)
        assertEquals("星空动画", ran?.name)
    }

    @Test
    fun `兼容提示产出数据`() {
        val c = newController()
        c.start(
            """
            ```js
            navigator.geolocation.getCurrentPosition(console.log);
            zq.fs.read('a.txt');
            ```
            """.trimIndent(),
        )
        val hints = c.state.value.hints
        assertEquals(2, hints.size)
        assertTrue(hints.any { it.api == "navigator.geolocation" })
        assertTrue(hints.any { it.api == "zq.fs.read" })
    }

    @Test
    fun `autoRun开启且高置信_start直接保存运行`() {
        var ran: ProjectMeta? = null
        val c = newController(onRun = { ran = it }, autoRunStore = FakeAutoRun(true))
        c.start("<!DOCTYPE html><html><body>ok</body></html>")
        assertTrue(ran != null, "高置信应自动运行")
        assertEquals(1, repo.list().size)
    }

    @Test
    fun `autoRun开启但低置信_停在预览建议兜底`() {
        var ran: ProjectMeta? = null
        val c = newController(onRun = { ran = it }, autoRunStore = FakeAutoRun(true))
        c.start("把时间 => 金钱")
        assertEquals(null, ran, "低置信必须停在预览")
        assertEquals("未识别", c.state.value.formLabel)
        assertTrue(c.state.value.aiFallbackSuggested)
        assertTrue(c.state.value.confidence < PasteConfidence.AI_FALLBACK_THRESHOLD)
    }

    @Test
    fun `autoRun开关走store并同步state`() = kotlinx.coroutines.test.runTest {
        val fake = FakeAutoRun(false)
        val c = newController(autoRunStore = fake)
        c.setAutoRun(true)
        assertEquals(true, c.state.value.autoRun)
        assertEquals(true, fake.autoRun.first())
    }

    @Test
    fun `空白输入不崩溃停在未知`() {
        val c = newController()
        c.start("   ")
        assertEquals("未识别", c.state.value.formLabel)
    }

    @Test
    fun `标题提取兜底默认名`() {
        val c = newController()
        c.start("<!DOCTYPE html>\n<html><body>x</body></html>")
        assertEquals(Assembler.DEFAULT_TITLE, c.state.value.name)
    }
}
