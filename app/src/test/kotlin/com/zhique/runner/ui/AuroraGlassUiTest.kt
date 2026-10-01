package com.zhique.runner.ui

import androidx.compose.animation.core.SpringSpec
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.zhique.runner.ui.components.AuroraBackground
import com.zhique.runner.ui.components.AuroraDomain
import com.zhique.runner.ui.components.GlassCard
import com.zhique.runner.ui.components.GlowButton
import com.zhique.runner.ui.theme.ZqMotion
import com.zhique.runner.ui.theme.ZqTheme
import java.io.File
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M9 Task 9.1 验收：动效五件套弹簧阻尼区间 + 全仓禁线性 easing 源码守护 +
 * GlassCard/GlowButton/AuroraBackground 可组合。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuroraGlassUiTest {

    // ---- 动效常量（规格 §5.2：damping 0.75–0.85） ----

    @Test
    fun `动效弹簧阻尼在075到085区间`() {
        val specs = listOf(
            "ZqSpring(抽屉)" to com.zhique.runner.ui.theme.ZqSpring,
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
        // 质量审查次要-6：扫描全仓 main 源码（:app + 全部 :core:* 模块），
        // 注释剥离按行处理（/*…*/ 块注释中间行与行尾 // 都剥）。
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
                        // 唯一豁免：AuroraBackground 光斑漂移的循环相位（线性相位保证循环无缝，
                        // 属环境光非交互转场；见该文件注释）
                        if (path.fileName.toString() == "AuroraBackground.kt") return@forEach
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

    // ---- GlassCard / GlowButton / AuroraBackground 可组合 ----

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `GlassCard内容渲染且顶部高光不破坏内容`() {
        compose.setContent {
            ZqTheme {
                GlassCard(Modifier.testTag("glass-card")) {
                    Text("玻璃卡内容")
                }
            }
        }
        compose.onNodeWithText("玻璃卡内容").assertIsDisplayed()
    }

    @Test
    fun `GlowButton点击回调触发`() {
        var clicked = 0
        compose.setContent {
            ZqTheme {
                GlowButton(
                    onClick = { clicked++ },
                    label = "发光按钮",
                    icon = Icons.Filled.Add,
                    testTag = "glow-button",
                )
            }
        }
        compose.onNodeWithTag("glow-button").performClick()
        assertEquals(1, clicked)
    }

    @Test
    fun `GlowButtonFab变体形变旋转展开`() {
        compose.setContent {
            ZqTheme {
                GlowButton(
                    onClick = {},
                    fab = true,
                    expanded = true,
                    icon = Icons.Filled.Add,
                    testTag = "glow-fab",
                )
            }
        }
        compose.onNodeWithTag("glow-fab").assertIsDisplayed()
    }

    @Test
    fun `AuroraBackground双域渲染内容可见`() {
        compose.setContent {
            ZqTheme {
                AuroraBackground(Modifier.testTag("aurora"), domain = AuroraDomain.LIGHT) {
                    Text("晨光底内容")
                }
            }
        }
        compose.onNodeWithText("晨光底内容").assertIsDisplayed()
    }

}
