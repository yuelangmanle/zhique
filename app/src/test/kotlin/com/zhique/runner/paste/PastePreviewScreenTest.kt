package com.zhique.runner.paste

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.zhique.core.project.ProjectRepository
import com.zhique.runner.ui.theme.ZqTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 粘贴预览屏：解析结果卡 / 清洗报告展开与撤销 / 命名 / 存草稿 / 运行（规格 §5.3 屏 3）。 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PastePreviewScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val jsPaste = "```js\nconst a = 1;\n```"

    private fun newController(onRun: (com.zhique.core.project.ProjectMeta) -> Unit = {}): Pair<PastePreviewController, ProjectRepository> {
        val repo = ProjectRepository(tmp.root)
        val d = UnconfinedTestDispatcher()
        val c = PastePreviewController(
            repo = repo,
            scope = CoroutineScope(d),
            io = d,
            onRun = onRun,
        )
        return c to repo
    }

    @Test
    fun `解析结果卡与命名与存为草稿`() {
        val (c, repo) = newController()
        compose.setContent {
            ZqTheme {
                PastePreviewScreen(controller = c, onBack = {}, modifier = Modifier.testTag("screen"))
            }
        }
        c.start("<!DOCTYPE html>\n<html><head><title>星空</title></head><body>hi</body></html>")
        compose.waitForIdle()
        compose.onNodeWithText("完整 HTML").assertExists()
        compose.onNodeWithTag("paste-name").performTextClearance()
        compose.onNodeWithTag("paste-name").performTextInput("我的星空")
        compose.waitForIdle()
        compose.onNodeWithTag("paste-save-draft").performClick()
        compose.waitForIdle()
        kotlin.test.assertEquals(1, repo.list().size)
        kotlin.test.assertEquals("我的星空", repo.list()[0].name)
    }

    @Test
    fun `清洗报告展开与撤销`() {
        val (c, _) = newController()
        compose.setContent {
            ZqTheme {
                PastePreviewScreen(controller = c, onBack = {})
            }
        }
        c.start(jsPaste)
        compose.waitForIdle()
        compose.onNodeWithTag("paste-report-toggle").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("剥离围栏").assertExists()
        compose.onNodeWithTag("paste-undo-clean").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("恢复清洗").assertExists()
        kotlin.test.assertTrue("```js" in c.state.value.assembledHtml, "撤销清洗=原始输入重跑")
    }

    @Test
    fun `运行按钮触发onRun并入库`() {
        var ran = false
        val (c, repo) = newController(onRun = { ran = true })
        compose.setContent {
            ZqTheme {
                PastePreviewScreen(controller = c, onBack = {})
            }
        }
        c.start(jsPaste)
        compose.waitForIdle()
        compose.onNodeWithTag("paste-run").performClick()
        compose.waitForIdle()
        kotlin.test.assertTrue(ran)
        kotlin.test.assertEquals(1, repo.list().size)
    }
}
