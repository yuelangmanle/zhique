package com.zhique.core.agent

import kotlin.test.Test
import kotlin.test.assertTrue

/** 审查修复 Minor 7：HTML 内联 script 的 id=/class= 赋值不再误配进符号索引。 */
class FileMapReviewFixTest {

    @Test
    fun `内联script赋值不误配_结构属性仍被索引`() {
        val html = """
            <html><body>
            <canvas id="canvas1" class="starfield"></canvas>
            <div class="hud"></div>
            <script>
              const el = document.getElementById('canvas1');
              el.id = "sky";
              el.className = "star";
            </script>
            </body></html>
        """.trimIndent()
        val map = FileMap.build(listOf("index.html" to html))
        assertTrue("canvas1" in map, "结构 id 仍被索引")
        assertTrue("hud" in map, "结构 class 仍被索引")
        assertTrue("starfield" in map)
        // script 块里的赋值目标与取值字符串都不得混入索引
        assertTrue(!Regex("\\bsky\\b").containsMatchIn(map), "JS 赋值不得混入 id 索引：$map")
        assertTrue(!Regex("\\bstar\\b").containsMatchIn(map), "JS 取值字符串不得混入 class 索引：$map")
    }

    @Test
    fun `未闭合script块同样被剔除`() {
        val html = """<html><body id="root"><script>el.id = "bad";"""
        val map = FileMap.build(listOf("index.html" to html))
        assertTrue(!Regex("\\bbad\\b").containsMatchIn(map), "未闭合块内容同样剔除：$map")
        assertTrue("root" in map)
    }

    @Test
    fun `css符号不受影响`() {
        val map = FileMap.build(listOf("main.css" to "body { color: red; }\n.sky { top: 0; }"))
        assertTrue("body" in map && "sky" in map)
    }
}
