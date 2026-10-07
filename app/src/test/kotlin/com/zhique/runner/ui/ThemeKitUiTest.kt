package com.zhique.runner.ui

import androidx.compose.animation.core.SpringSpec
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.zhique.runner.ui.kit.ZqCard
import com.zhique.runner.ui.theme.ZqMotion
import com.zhique.runner.ui.theme.ZqSpring
import com.zhique.runner.ui.theme.ZqTheme
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 主题守卫：动效弹簧阻尼区间 + 全仓禁线性 easing + 克制视觉组件（ZqCard）可组合。
 * （原 Aurora Glass 三件套已随「克制视觉」改造移除。）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThemeKitUiTest {

    // ---- 动效常量（§5.2：damping 0.75–0.85） ----

    @Test
    fun `动效弹簧阻尼在075到085区间`() {
        val specs = listOf(
            "ZqSpring(抽屉)" to ZqSpring,
            "Drawer" to ZqMotion.Drawer,
            "Press" to ZqMotion.Press,
            "Fab" to ZqMotion.Fab,
            "Progress" to ZqMotion.Progress,
            "SlideIn" to ZqMotion.SlideIn,
            "Reveal" to ZqMotion.Reveal,
        )
        specs.forEach { (name, spec) ->
            assertTrue(spec is SpringSpec<Float>, "$name 须为 spring")
            assertTrue(
                spec.dampingRatio in 0.75f..0.85f,
                "$name damping=${spec.dampingRatio} 超出 0.75–0.85（§5.2）",
            )
        }
    }

    // ---- 全仓禁线性 easing（源码扫描守护） ----

    @Test
    fun `全仓禁线性easing_交互转场全spring`() {
        // 扫描全仓 main 源码（:app + 全部 :core:* 模块），注释剥离按行处理。
        // 仓库根由 Gradle 注入（zhique.repoRoot 系统属性），NIO 遍历零路径攀爬。
        val nioRoot = java.nio.file.Path.of(
            requireNotNull(System.getProperty("zhique.repoRoot")) { "缺少 zhique.repoRoot 系统属性" },
        )
        data class Hit(val file: String, val line: Int, val text: String)
        val offenders = mutableListOf<Hit>()
        val blockComment = Regex("""/\*.*?\*/|//.*$""")
        val scanRoots = buildList {
            add(nioRoot.resolve("app").resolve("src").resolve("main"))
            java.nio.file.Files.list(nioRoot.resolve("core")).use { stream ->
                stream.filter { java.nio.file.Files.isDirectory(it) }
                    .sorted()
                    .forEach { add(it.resolve("src").resolve("main")) }
            }
        }
        scanRoots.forEach { root ->
            if (!java.nio.file.Files.isDirectory(root)) return@forEach
            java.nio.file.Files.walk(root).use { walk ->
                walk.filter { java.nio.file.Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                    .forEach { path ->
                        val lines = java.nio.file.Files.readAllLines(path)
                        val stripped = lines.joinToString("\n") { line -> blockComment.replace(line, "") }
                        stripped.lines().forEachIndexed { i, line ->
                            if ("LinearEasing" in line || "tween(" in line) {
                                offenders += Hit(nioRoot.relativize(path).toString(), i + 1, line.trim())
                            }
                        }
                    }
            }
        }
        assertTrue(offenders.isEmpty(), "发现线性/时长型动效（§5.2 全 spring）：$offenders")
    }

    // ---- 克制视觉组件 ----

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `ZqCard内容渲染且按钮可点`() {
        var clicked = 0
        compose.setContent {
            ZqTheme {
                ZqCard(Modifier.testTag("zq-card")) {
                    Text("卡片内容")
                    Button(onClick = { clicked++ }, modifier = Modifier.testTag("card-btn")) { Text("点我") }
                }
            }
        }
        compose.onNodeWithText("卡片内容").assertIsDisplayed()
        compose.onNodeWithTag("card-btn").performClick()
        assertEquals(1, clicked)
    }

    @Test
    fun `浅深两域主题色可解析`() {
        compose.setContent {
            Column {
                ZqTheme(darkTheme = false) {
                    androidx.compose.material3.Surface(
                        color = androidx.compose.material3.MaterialTheme.colorScheme.background,
                    ) { Text("浅") }
                }
                ZqTheme(darkTheme = true) {
                    androidx.compose.material3.Surface(
                        color = androidx.compose.material3.MaterialTheme.colorScheme.background,
                    ) { Text("深") }
                }
            }
        }
        compose.onNodeWithText("浅").assertIsDisplayed()
        compose.onNodeWithText("深").assertIsDisplayed()
    }
}
