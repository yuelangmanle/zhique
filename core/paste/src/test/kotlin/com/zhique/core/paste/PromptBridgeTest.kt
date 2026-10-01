package com.zhique.core.paste

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Task 8.2：提示词桥——分段拼接、未勾选不携带、文档资产与能力清单一致（防漂移）。
 */
class PromptBridgeTest {

    private val bridge = PromptBridge()

    @Test
    fun 四段固定结构齐全() {
        val out = bridge.generate("做一个记账小工具", emptyList())
        assertTrue(out.startsWith(PromptBridge.HEADER))
        listOf("## 我要做的事", "## 运行环境", "## 输出要求").forEach {
            assertTrue(it in out, "缺少段：$it")
        }
        assertTrue("做一个记账小工具" in out)
    }

    @Test
    fun 运行环境段含Chromium与WebGL_WebGPU与权限纪律() {
        val out = bridge.generate("x", emptyList())
        assertTrue("Chromium" in out)
        assertTrue("WebGL" in out)
        assertTrue("WebGPU" in out)
        assertTrue("不得崩溃" in out) // 权限被拒不崩溃
    }

    @Test
    fun 输出要求段含单文件完整HTML与无markdown围栏() {
        val out = bridge.generate("x", emptyList())
        assertTrue("单文件完整 HTML" in out || ("<!DOCTYPE html>" in out && "</html>" in out))
        assertTrue("markdown 代码围栏" in out)
    }

    @Test
    fun 勾选的API携带对应文档段() {
        val out = bridge.generate("拍照打卡", listOf("camera"))
        assertTrue("## 可用的织雀 API（zq.*）" in out)
        assertTrue("### zq.camera" in out)
        assertTrue("zq.camera.capture" in out) // 文档正文进了提示词
    }

    @Test
    fun 未勾选不携带文档段() {
        val out = bridge.generate("x", emptyList())
        assertFalse("## 可用的织雀 API（zq.*）" in out)
        assertFalse("### zq.camera" in out)
    }

    @Test
    fun 只携带勾选项() {
        val out = bridge.generate("x", listOf("clipboard", "share"))
        assertTrue("### zq.clipboard" in out)
        assertTrue("### zq.share" in out)
        assertFalse("### zq.camera" in out)
        assertFalse("### zq.location" in out)
    }

    @Test
    fun 未知API忽略不臆造() {
        val out = bridge.generate("x", listOf("camera", "nfc", "  "))
        assertTrue("### zq.camera" in out)
        assertFalse("nfc" in out)
    }

    @Test
    fun 重复勾选去重() {
        val out = bridge.generate("x", listOf("camera", "camera"))
        assertEquals(1, Regex("### zq\\.camera").findAll(out).count())
    }

    @Test
    fun 空用户句兜底占位() {
        val out = bridge.generate("   ", emptyList())
        assertTrue("（在这里描述你想做的小工具）" in out)
    }

    // ---- 文档资产与能力清单一致（防漂移） ----

    @Test
    fun 资产文件清单与KNOWN_APIS一致() {
        val dir = File("src/main/resources/zq-docs")
        assertTrue(dir.isDirectory, "zq-docs 资产目录缺失")
        val files = dir.listFiles { f -> f.extension == "md" }!!.map { it.nameWithoutExtension }.sorted()
        assertEquals(PromptBridge.KNOWN_APIS.sorted(), files)
    }

    @Test
    fun 每份文档非空且含zq命名空间与授权语义() {
        PromptBridge.KNOWN_APIS.forEach { id ->
            val doc = PromptBridge.DEFAULT_DOC_LOADER(id)
            assertTrue(!doc.isNullOrBlank(), "文档缺失：$id")
            assertTrue("zq.$id" in doc, "文档 $id 未提及 zq.$id 调用")
            assertTrue("授权" in doc || "取消" in doc || "分享面板" in doc, "文档 $id 未说明授权/取消语义")
        }
    }

    @Test
    fun 默认加载器对未知id安全返回null() {
        assertEquals(null, PromptBridge.DEFAULT_DOC_LOADER("not-exist"))
    }
}
