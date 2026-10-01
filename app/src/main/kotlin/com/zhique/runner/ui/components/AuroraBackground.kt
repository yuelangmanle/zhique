package com.zhique.runner.ui.components

import android.app.ActivityManager
import android.content.Context
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.zhique.runner.ui.theme.AuroraDomainSpec
import com.zhique.runner.ui.theme.auroraPalette
import kotlin.math.cos
import kotlin.math.sin

/**
 * AuroraBackground（规格 §5.1 光晕要素）：Canvas + infiniteTransition 两枚大半径
 * 径向光斑低饱和缓慢漂移；晨光浅底/深空暗底由主题域给色。
 * 低端机（ActivityManager.isLowRamDevice）降级为单光斑（§5.2 低端机减光晕层数）。
 *
 * 注：缓动禁用约束（§5.2）针对交互与状态转场动效；光斑漂移用 LinearEasing +
 * 反复重启是为了让相位循环严格无缝（任何 easing 在循环端点会产生停顿/回跳）。
 */
enum class AuroraDomain { LIGHT, DARK }

@Composable
fun AuroraBackground(
    modifier: Modifier = Modifier,
    domain: AuroraDomain = AuroraDomain.LIGHT,
    content: @Composable () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lowRam = remember { isLowRamDevice(context) }
    // 质量审查 Important-2：光斑色按屏域映射，不随系统深色开关
    val palette = auroraPalette(
        if (domain == AuroraDomain.LIGHT) AuroraDomainSpec.LIGHT else AuroraDomainSpec.DARK,
    )
    val blobA = palette.blobA
    val blobB = palette.blobB

    val transition = rememberInfiniteTransition(label = "aurora")
    val phaseA by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(18_000, easing = LinearEasing), RepeatMode.Restart),
        label = "aurora-a",
    )
    val phaseB by transition.animateFloat(
        initialValue = (Math.PI / 2).toFloat(),
        targetValue = ((Math.PI / 2) + 2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(26_000, easing = LinearEasing), RepeatMode.Restart),
        label = "aurora-b",
    )

    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            fun blob(color: Color, phase: Float, cx: Float, cy: Float, rx: Float, ry: Float, alpha: Float) {
                val center = Offset(cx + rx * cos(phase), cy + ry * sin(phase))
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(color.copy(alpha = alpha), Color.Transparent),
                        center = center,
                        radius = size.minDimension * 0.75f,
                    ),
                    radius = size.minDimension * 0.75f,
                    center = center,
                )
            }
            blob(
                color = blobA, phase = phaseA,
                cx = w * 0.22f, cy = h * 0.18f, rx = w * 0.1f, ry = h * 0.08f,
                alpha = if (domain == AuroraDomain.LIGHT) 0.55f else 0.5f,
            )
            if (!lowRam) {
                blob(
                    color = blobB, phase = phaseB,
                    cx = w * 0.82f, cy = h * 0.78f, rx = w * 0.08f, ry = h * 0.1f,
                    alpha = if (domain == AuroraDomain.LIGHT) 0.45f else 0.4f,
                )
            }
        }
        content()
    }
}

/** 低端机判定（ActivityManager.isLowRamDevice；读取失败按非低端机处理）。 */
private fun isLowRamDevice(context: Context): Boolean {
    return runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        am?.isLowRamDevice == true
    }.getOrDefault(false)
}
