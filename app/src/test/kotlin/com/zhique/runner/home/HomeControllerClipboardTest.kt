package com.zhique.runner.home

import com.zhique.core.project.ProjectRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/** 首页剪贴板卡：ON_RESUME 读一次 + 显式刷新，检测到内容且含代码特征才显示（规格 §2.1）。 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeControllerClipboardTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newController(clipboard: String?): HomeController {
        val d = UnconfinedTestDispatcher()
        return HomeController(
            repo = ProjectRepository(tmp.root),
            scope = CoroutineScope(d),
            io = d,
            clipboardText = { clipboard },
        )
    }

    private val jsPaste = "```js\nconst a = 1;\n```"

    @Test
    fun `检测到代码特征才显示卡`() {
        val c = newController(jsPaste)
        c.checkClipboard()
        assertEquals(jsPaste, c.clipboardCandidate.value)
    }

    @Test
    fun `纯文本无代码特征不显示卡`() {
        val c = newController("今天天气不错，我们去公园散步吧。")
        c.checkClipboard()
        assertNull(c.clipboardCandidate.value)
    }

    @Test
    fun `空剪贴板与空白不显示卡`() {
        val c = newController("   \n ")
        c.checkClipboard()
        assertNull(c.clipboardCandidate.value)
        val c2 = newController(null)
        c2.checkClipboard()
        assertNull(c2.clipboardCandidate.value)
    }

    @Test
    fun `忽略后同内容不再打扰_内容变化才重新出现`() {
        var clip: String? = jsPaste
        val d = UnconfinedTestDispatcher()
        val c = HomeController(
            repo = ProjectRepository(tmp.root),
            scope = CoroutineScope(d),
            io = d,
            clipboardText = { clip },
        )
        c.checkClipboard()
        c.dismissClipboard()
        assertNull(c.clipboardCandidate.value)
        c.checkClipboard() // ON_RESUME 再次读取，同内容不重复打扰
        assertNull(c.clipboardCandidate.value)
        clip = "body { color: red; }" // 剪贴板内容变了
        c.checkClipboard()
        assertEquals(clip, c.clipboardCandidate.value)
    }

    @Test
    fun `consume取走并清空`() {
        val c = newController(jsPaste)
        c.checkClipboard()
        assertEquals(jsPaste, c.consumeClipboard())
        assertNull(c.clipboardCandidate.value)
    }

    @Test
    fun `手动刷新重读剪贴板`() {
        var clip: String? = null
        val d = UnconfinedTestDispatcher()
        val c = HomeController(
            repo = ProjectRepository(tmp.root),
            scope = CoroutineScope(d),
            io = d,
            clipboardText = { clip },
        )
        c.checkClipboard()
        assertNull(c.clipboardCandidate.value)
        clip = jsPaste
        c.checkClipboard() // 显式刷新按钮同路径
        assertEquals(jsPaste, c.clipboardCandidate.value)
    }
}
