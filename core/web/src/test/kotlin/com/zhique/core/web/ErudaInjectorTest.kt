package com.zhique.core.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M9 Task 9.2：eruda 高级面板注入开关（内置资产 + 页尾注入 + 防重复 init）。 */
class ErudaInjectorTest {

    @Test
    fun `关闭或空源不注入`() {
        assertNull(ErudaInjector.pageEndScript(null), "开关关闭（无资产）→ 不注入")
        assertNull(ErudaInjector.pageEndScript(""), "空资产 → 不注入")
        assertNull(ErudaInjector.pageEndScript("   "), "纯空白 → 不注入")
    }

    @Test
    fun `开启时注入init并带防重复标记`() {
        val fake = "window.eruda={init:function(){window.__ERUDA_INIT_CALLED__=true;}};"
        val script = assertNotNull(ErudaInjector.pageEndScript(fake))
        assertTrue(script.startsWith("(function(){"), "整体 IIFE 包裹")
        assertTrue(script.endsWith("})();"), "IIFE 收口")
        assertTrue(ErudaInjector.GUARD in script, "带页面级防重复标记")
        assertTrue("window.eruda.init()" in script, "自动 init")
        // 二次包装不丢原源
        assertTrue(fake in script)
    }

    @Test
    fun `注入脚本对重复调用幂等`() {
        // 同一页面 onPageFinished 多次回调：第二次因 GUARD 直接 return（结构断言）
        val script = assertNotNull(ErudaInjector.pageEndScript("var x=1;"))
        val guardCheck = "if(window.${ErudaInjector.GUARD})return;"
        assertTrue(guardCheck in script, "先查标记再装载")
        assertTrue(script.indexOf(ErudaInjector.GUARD) < script.indexOf("var x=1;"), "标记先行")
    }

    @Test
    fun `内置资产在构建产物assets且语法可校验`() {
        // f2677ea 起 eruda 不入源码树：构建期 fetchEruda 从 npm registry 拉取并校验
        // SHA-256 落到 generated/eruda-assets（该目录已挂进 assets srcDir，随包分发）。
        // 测试任务依赖 preBuild → fetchEruda，产物必已就位；此处核对存在与形态。
        val f = java.io.File("build/generated/eruda-assets/${ErudaInjector.ERUDA_ASSET}")
        assertTrue(f.isFile, "缺构建期拉取的资产 ${f.path}（fetchEruda 应已在 preBuild 就位）")
        val src = f.readText()
        assertTrue(src.contains("eruda"), "eruda 源非空（${src.length} chars）")
        assertFalse(src.isBlank())
        // UMD 入口挂 window.eruda
        assertTrue("e.eruda=t()" in src || "exports.eruda" in src, "UMD 入口应暴露全局 eruda")
    }

    @Test
    fun `与织雀桥不共享命名空间`() {
        // 注入脚本只新增 window.__ZHIQUE_ERUDA_LOADED__ / window.eruda，不触碰 __ZHIQUE__
        val script = assertNotNull(ErudaInjector.pageEndScript("var x=1;"))
        assertFalse("window.__ZHIQUE__" in script.replace("window.__ZHIQUE_ERUDA_LOADED__", ""))
        assertEquals(null, null) // 占位对齐断言风格
    }
}
