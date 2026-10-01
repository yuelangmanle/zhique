package com.zhique.runner.ui.components

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zhique.runner.ui.theme.ZqMotion
import com.zhique.runner.ui.theme.zqSemantic

/**
 * GlassCard（规格 §5.1 毛玻璃 + 镜面高光要素）：
 * - blur(20px)+saturate(150%)：API 31+（minSdk=31）RenderEffect 系统 GPU 实时渲染，
 *   作用于半透明底色层——内容不受糊化；
 * - 边框白 55%（语义 glassBorder，暗域自动降透明度）+ 顶部 1px 镜面高光内线；
 * - 阴影 + 按压回弹（§5.2 ②：scale 0.94→1.035→1，[ZqMotion.Press]）。
 * [onPressBounce]=false 时不带按压动效（纯展示卡）。
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    corner: Dp = 20.dp,
    elevation: Dp = 8.dp,
    onPressBounce: Boolean = true,
    content: @Composable () -> Unit,
) {
    val semantic = zqSemantic()
    val shape = RoundedCornerShape(corner)
    var pressed = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val scale = remember { Animatable(1f) }
    LaunchedEffect(pressed.value) {
        if (!onPressBounce) return@LaunchedEffect
        if (pressed.value) {
            scale.snapTo(0.94f)
        } else if (scale.value < 1f) {
            // 释放：经 1.035 微过冲回落 1（spring damping<1 自然形成回弹）
            scale.animateTo(1.035f, ZqMotion.Press)
            scale.animateTo(1f, ZqMotion.Press)
        }
    }
    val glassEffect = remember { glassRenderEffect() }

    Box(
        modifier
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
            .shadow(elevation, shape)
            .clip(shape)
            .pointerInput(onPressBounce) {
                if (!onPressBounce) return@pointerInput
                detectTapGestures(
                    onPress = {
                        pressed.value = true
                        tryAwaitRelease()
                        pressed.value = false
                    },
                )
            },
    ) {
        // 毛玻璃底层：blur+saturate 只糊底色，不糊内容
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer { renderEffect = glassEffect }
                .background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.72f))
                .border(1.dp, semantic.glassBorder, shape)
                .drawWithContent {
                    drawContent()
                    // 顶部 1px 镜面高光（内阴影语义）
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(semantic.glassHighlight, Color.Transparent),
                            startY = 0f,
                            endY = 2f,
                        ),
                        topLeft = Offset.Zero,
                        size = Size(size.width, 1.dp.toPx()),
                        alpha = 0.9f,
                    )
                },
        )
        content()
    }
}

/** blur(20px)+saturate(150%) 组合 RenderEffect；API<31 返回 null（不生效即降级素卡）。 */
private fun glassRenderEffect(): RenderEffect? {
    if (Build.VERSION.SDK_INT < 31) return null
    return runCatching {
        val blur = android.graphics.RenderEffect.createBlurEffect(
            20f,
            20f,
            android.graphics.Shader.TileMode.CLAMP,
        )
        val matrix = ColorMatrix().apply { setSaturation(1.5f) }
        android.graphics.RenderEffect
            .createColorFilterEffect(ColorMatrixColorFilter(matrix), blur)
            .asComposeRenderEffect()
    }.getOrNull()
}
