package com.zhique.runner.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.zhique.runner.ui.theme.zqSemantic

/**
 * 液态玻璃（Liquid Glass）：半透明材质面 + 高光描边 + 顶部内高光 + 轻染。
 *
 * 实现说明：Android 无公开的 Compose「背景实时模糊」API（backdrop blur 仅
 * Window 级），本材质以「环境底（[ZqAmbient] 柔和渐变）透出 + 半透明填充 +
 * 镜面边缘」逼近 iOS 26 Liquid Glass 的观感——玻璃元素必须浮在环境底或
 * 内容之上才有通透感，屏幕内容区仍为 iOS 分栏不透明卡。
 */
@Composable
fun LiquidGlass(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(22.dp),
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    val dark = MaterialTheme.colorScheme.background.luminanceIsDark()
    val semantic = zqSemantic()
    val fill = if (dark) Color(0xFF1C1C1E).copy(alpha = 0.74f) else Color.White.copy(alpha = 0.74f)
    Box(
        modifier
            .clip(shape)
            .background(fill)
            .drawBehind {
                // 镜面高光描边：左上亮 → 右下弱（玻璃厚度感）
                drawRoundRect(
                    brush = Brush.linearGradient(
                        colors = listOf(semantic.glassBorder, semantic.glassBorder.copy(alpha = 0.06f)),
                        start = Offset.Zero,
                        end = Offset(size.width, size.height),
                    ),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width / 2f, size.width / 2f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.2.dp.toPx()),
                )
            },
        contentAlignment = contentAlignment,
    ) {
        // 顶部内高光（lensing 提示）
        Box(
            Modifier
                .matchParentSize()
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(semantic.glassHighlight.copy(alpha = 0.35f), Color.Transparent),
                            endY = size.height * 0.35f,
                        ),
                    )
                },
        )
        content()
    }
}

private fun Color.luminanceIsDark(): Boolean = (0.299f * red + 0.587f * green + 0.114f * blue) < 0.5f

/**
 * iOS 环境底：分栏底色上叠加两团极低饱和的品牌色晕（静态，无动画——
 * 玻璃透出的就是它，动效会干扰阅读且耗电）。
 */
@Composable
fun ZqAmbient(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val bg = MaterialTheme.colorScheme.background
    val tint = MaterialTheme.colorScheme.primary
    Box(
        modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(bg)
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(tint.copy(alpha = 0.07f), Color.Transparent),
                        center = Offset(size.width * 0.85f, size.height * 0.08f),
                        radius = size.width * 0.75f,
                    ),
                )
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(tint.copy(alpha = 0.05f), Color.Transparent),
                        center = Offset(size.width * 0.05f, size.height * 0.95f),
                        radius = size.width * 0.7f,
                    ),
                )
            },
        content = content,
    )
}
