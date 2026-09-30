package com.zhique.runner.editor

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.height
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.zhique.runner.ui.theme.ZqTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Task 4.5 UI：轻编辑器（文件 Tab/只读横幅/选中浮出/AI 输入条）；编辑器桩注入。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditorScreenUiTest {

    @get:Rule
    val compose = createComposeRule()

    private fun state(agentRunning: Boolean = false, selected: String = "") = EditorUiState(
        files = listOf(
            EditorFile("index.html", "<html></html>", "html"),
            EditorFile("app.js", "const a = 1;", "javascript"),
        ),
        activeIndex = 0,
        agentRunning = agentRunning,
        selectedText = selected,
    )

    @Test
    fun `文件tab与只读横幅`() {
        compose.setContent {
            ZqTheme {
                EditorContent(
                    state = state(agentRunning = true),
                    onBack = {},
                    onSelectTab = {},
                    onContentChange = { _, _ -> },
                    onSelection = {},
                    onAiInput = {},
                    onAskAi = {},
                    editorSlot = { _, _, content, readOnly, _, _ ->
                        FakeEditorSlot(content, readOnly)
                    },
                )
            }
        }
        compose.onNodeWithTag("editor-readonly-banner").assertExists()
        compose.onNodeWithTag("editor-tab-index.html").assertExists()
        compose.onNodeWithTag("editor-tab-app.js").performClick()
        compose.onNodeWithTag("fake-editor").assertExists()
    }

    @Test
    fun `选中浮出问AI修段复制`() {
        var asked: EditorAskContext? = null
        compose.setContent {
            ZqTheme {
                EditorContent(
                    state = state(selected = "const a = 1;"),
                    onBack = {},
                    onSelectTab = {},
                    onContentChange = { _, _ -> },
                    onSelection = {},
                    onAiInput = {},
                    onAskAi = { asked = it },
                    editorSlot = { _, _, content, readOnly, _, _ -> FakeEditorSlot(content, readOnly) },
                )
            }
        }
        compose.onNodeWithTag("ask-ai-selection").performClick()
        assertEquals("解释这段代码", asked?.question)
        assertEquals("const a = 1;", asked?.selection)
        assertEquals("html", asked?.language)
        compose.onNodeWithTag("fix-selection").performClick()
        assertEquals("修复这段代码的问题", asked?.question)
    }

    @Test
    fun `AI输入条带选中上下文发送`() {
        var asked: EditorAskContext? = null
        var typed: String? = null
        compose.setContent {
            ZqTheme {
                EditorContent(
                    state = state(selected = "div.sky"),
                    onBack = {},
                    onSelectTab = {},
                    onContentChange = { _, _ -> },
                    onSelection = {},
                    onAiInput = { typed = it },
                    onAskAi = { asked = it },
                    editorSlot = { _, _, content, readOnly, _, _ -> FakeEditorSlot(content, readOnly) },
                )
            }
        }
        compose.onNodeWithTag("editor-ai-input").performTextInput("这个选择器对吗")
        compose.waitForIdle()
        // 输入回调携带问题文本（状态提交时机由宿主管）
        assertEquals("这个选择器对吗", typed)
        compose.onNodeWithTag("editor-ai-send").performClick()
        compose.waitForIdle()
        // 状态未回流时兜底问题 + 选中上下文必须随行
        assertEquals("看这段代码", asked?.question)
        assertEquals("div.sky", asked?.selection)
    }

    @Test
    fun `编辑器离开组合即release`() {
        val editorBox = arrayOfNulls<ZqCodeEditor>(1)
        compose.setContent {
            ZqTheme {
                var show by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(true) }
                if (show) {
                    RealEditorSlot(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .testTag("real-editor"),
                        path = "index.html",
                        content = "<html></html>",
                        readOnly = false,
                        onContentChange = {},
                        onSelection = {},
                        onEditorCreated = { editorBox[0] = it },
                    )
                }
                androidx.compose.material3.Button(
                    onClick = { show = false },
                    modifier = Modifier.testTag("remove-editor"),
                ) { androidx.compose.material3.Text("移除") }
            }
        }
        compose.waitForIdle()
        val editor = editorBox[0]
        kotlin.test.assertNotNull(editor, "真实编辑器应被创建")
        org.junit.Assert.assertFalse(editor!!.releaseObserved)
        compose.onNodeWithTag("remove-editor").performClick()
        compose.waitForIdle()
        org.junit.Assert.assertTrue("离开组合必须 release()", editor!!.releaseObserved)
    }

    @Test
    fun `agent运行时编辑器只读`() {
        compose.setContent {
            ZqTheme {
                EditorContent(
                    state = state(agentRunning = true),
                    onBack = {},
                    onSelectTab = {},
                    onContentChange = { _, _ -> },
                    onSelection = {},
                    onAiInput = {},
                    onAskAi = {},
                    editorSlot = { _, _, content, readOnly, _, _ ->
                        org.junit.Assert.assertTrue("编辑器应只读", readOnly)
                        FakeEditorSlot(content, readOnly)
                    },
                )
            }
        }
        compose.waitForIdle()
    }
}

/** 编辑器测试桩：给个可见节点供断言。 */
@androidx.compose.runtime.Composable
private fun FakeEditorSlot(content: String, readOnly: Boolean) {
    androidx.compose.material3.Text(
        text = "fake-editor:" + (if (readOnly) "ro" else "rw") + ":" + content.take(20),
        modifier = androidx.compose.ui.Modifier.testTag("fake-editor"),
    )
}
