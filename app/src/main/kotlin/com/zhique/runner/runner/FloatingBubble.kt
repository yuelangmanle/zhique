package com.zhique.runner.runner

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.zhique.runner.ui.theme.ZqSpring
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** 悬浮球 x 边缘吸附：就近贴左右边缘（y 自由）。 */
object BubbleSnap {
    fun snapX(x: Float, containerWidth: Float, ballSize: Float): Float {
        val maxX = (containerWidth - ballSize).coerceAtLeast(0f)
        val cx = x.coerceIn(0f, maxX)
        return if (cx < containerWidth / 2f) 0f else maxX
    }
}

/** 悬浮球位置状态（px，测试断言用）。 */
class FloatingBubbleState {
    var x by mutableFloatStateOf(0f)
    var y by mutableFloatStateOf(0f)
}

/**
 * 悬浮球 + 半透明浮层面板（Console 摘要 + Agent 按钮）。
 * 拖拽跟手、释放 spring 边缘吸附；M1 为窗口内浮层，WindowManager 系统浮层
 * 与 overlay 权限流程在 M10 设备验证时接线（同 UX，无权限需求）。
 */
@Composable
fun FloatingBubble(
    state: FloatingBubbleState,
    summary: String = "",
    onOpenAgent: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val ballSizeDp = 56.dp
    val density = LocalDensity.current
    val ballPx = with(density) { ballSizeDp.toPx() }
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    val xAnim = remember { Animatable(state.x) }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val maxX = (w - ballPx).coerceAtLeast(0f)
        val maxY = (h - ballPx).coerceAtLeast(0f)

        // 初始落位：右下角留出底边距
        LaunchedEffect(maxX, maxY) {
            if (state.x == 0f && state.y == 0f) {
                state.x = maxX
                state.y = (maxY - with(density) { 96.dp.toPx() }).coerceIn(0f, maxY)
                xAnim.snapTo(state.x)
            }
        }

        // 悬浮球
        Box(
            Modifier
                .offset { IntOffset(xAnim.value.roundToInt(), state.y.roundToInt()) }
                .size(ballSizeDp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .testTag("bubble-ball")
                .pointerInput(maxX, maxY) {
                    detectDragGestures(
                        onDrag = { change, amount ->
                            change.consume()
                            val nx = (xAnim.value + amount.x).coerceIn(0f, maxX)
                            scope.launch {
                                xAnim.snapTo(nx)
                                state.x = nx
                                state.y = (state.y + amount.y).coerceIn(0f, maxY)
                            }
                        },
                        onDragEnd = {
                            scope.launch {
                                val target = BubbleSnap.snapX(xAnim.value, w, ballPx)
                                xAnim.animateTo(target, ZqSpring)
                                state.x = xAnim.value
                            }
                        },
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { open = !open })
                },
            contentAlignment = Alignment.Center,
        ) {
            Text("雀", color = MaterialTheme.colorScheme.onPrimary)
        }

        // 浮层面板（QA 修复：0.92 透明度让底层 WebView 文字透出混浊，改不透明）
        if (open) {
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .widthIn(max = 320.dp)
                    .testTag("bubble-panel"),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp,
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text("Console 摘要", style = MaterialTheme.typography.titleSmall)
                    Text(
                        summary.ifBlank { "暂无输出" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            open = false
                            onOpenAgent()
                        }, modifier = Modifier.testTag("bubble-agent")) { Text("交给 Agent") }
                        TextButton(onClick = { open = false }) { Text("关闭") }
                    }
                }
            }
        }
    }
}
