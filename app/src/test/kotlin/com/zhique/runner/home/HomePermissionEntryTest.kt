package com.zhique.runner.home

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import com.zhique.core.project.ProjectRepository
import com.zhique.runner.ui.theme.ZqTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 首页项目卡长按菜单「权限」入口（Task 5.3）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomePermissionEntryTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `长按菜单含权限入口并回调目标项目`() {
        val repo = ProjectRepository(tmp.newFolder())
        val project = repo.create("入口项目", "<p></p>")
        var openedFor: String? = null
        compose.setContent {
            ZqTheme {
                HomeScreen(
                    repo = repo,
                    onRun = {},
                    onToast = {},
                    onOpenPermissions = { openedFor = it.id },
                )
            }
        }
        compose.waitForIdle()
        compose.onAllNodesWithTag("project-card")[0].performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithTag("menu-permission").performClick()
        compose.waitForIdle()
        assertEquals(project.id, openedFor, "「权限」必须带着项目 id 去权限中心")
    }

    @Test
    fun `菜单其余项仍齐全`() {
        val repo = ProjectRepository(tmp.newFolder())
        repo.create("菜单项目", "<p></p>")
        compose.setContent {
            ZqTheme {
                HomeScreen(repo = repo, onRun = {}, onToast = {})
            }
        }
        compose.waitForIdle()
        compose.onAllNodesWithTag("project-card")[0].performTouchInput { longClick() }
        compose.waitForIdle()
        assertTrue(compose.onNodeWithText("重命名").assertExists().toString().isNotEmpty())
        compose.onNodeWithText("移动分组").assertExists()
        compose.onNodeWithText("复制项目").assertExists()
        compose.onNodeWithText("zip 导出").assertExists()
        compose.onNodeWithText("删除").assertExists()
    }
}
