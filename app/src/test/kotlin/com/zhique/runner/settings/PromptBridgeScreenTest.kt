package com.zhique.runner.settings

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Task 8.2：织雀提示词桥屏——chip 多选联动预览、复制全文、CompatHint 预填。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PromptBridgeScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun newController(): PromptBridgeController = PromptBridgeController()

    @Test
    fun chip勾选联动预览_未勾选不携带() {
        val controller = newController()
        compose.setContent { PromptBridgeScreen(controller, onBack = {}, onToast = {}) }
        compose.onNodeWithTag("pb-idea").performTextInput("做相机打卡")
        compose.onNodeWithText("做相机打卡").assertExists()
        // 未勾选：预览不含 API 段
        compose.onNodeWithText("## 可用的织雀 API（zq.*）").assertDoesNotExist()
        // 勾选 camera：预览出现 zq.camera 文档段
        compose.onNodeWithTag("pb-chip-camera").performClick()
        compose.onNodeWithTag("pb-chip-camera").assertIsSelected()
        compose.onNodeWithText("### zq.camera", substring = true).assertExists()
        assertTrue(controller.state.value.selected.contains("camera"))
        // 取消勾选：文档段移除
        compose.onNodeWithTag("pb-chip-camera").performClick()
        assertFalse(controller.state.value.selected.contains("camera"))
    }

    @Test
    fun 复制全文上剪贴板() {
        val controller = newController()
        val toasts = mutableListOf<String>()
        compose.setContent { PromptBridgeScreen(controller, onBack = {}, onToast = { toasts += it }) }
        compose.onNodeWithTag("pb-chips").performScrollToNode(hasTestTag("pb-chip-clipboard"))
        compose.onNodeWithTag("pb-chip-clipboard").performClick()
        compose.onNodeWithTag("pb-scroll").performScrollToNode(hasTestTag("pb-copy"))
        compose.onNodeWithTag("pb-copy").performClick()
        compose.waitForIdle()
        assertTrue(toasts.single().contains("已复制全文"))
        val cm = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()
            .getSystemService(android.content.ClipboardManager::class.java)
        val text = cm.primaryClip!!.getItemAt(0).text.toString()
        assertTrue(text.startsWith("# 织雀提示词"), "剪贴板实际内容前 60 字：${text.take(60)}")
        assertTrue("zq.clipboard" in text)
        assertFalse("### zq.camera" in text)
    }

    @Test
    fun compatHint原句经initialIdea预填() {
        val controller = newController()
        compose.setContent {
            PromptBridgeScreen(controller, onBack = {}, initialIdea = "从粘贴来的原句", onToast = {})
        }
        compose.waitForIdle()
        assertEquals("从粘贴来的原句", controller.state.value.idea)
        compose.onNodeWithText("从粘贴来的原句").assertExists()
    }

    @Test
    fun 控制器toggle与preview纯逻辑() {
        val controller = newController()
        controller.setIdea("测试")
        controller.toggle("share")
        controller.toggle("share")
        controller.toggle("file")
        runBlocking { assertTrue(controller.state.first().selected == setOf("file")) }
        val preview = controller.preview()
        assertTrue("### zq.file" in preview)
        assertFalse("### zq.share" in preview)
        assertTrue(preview.contains("Chromium"))
    }
}
