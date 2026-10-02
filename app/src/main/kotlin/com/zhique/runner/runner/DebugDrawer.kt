package com.zhique.runner.runner

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.zhique.core.web.CapabilityReport
import com.zhique.core.web.debug.Timeline
import com.zhique.core.web.debug.TimelineEntry
import com.zhique.runner.ui.theme.ZqSpring
import kotlin.math.abs
import kotlinx.coroutines.launch

/** 抽屉三档吸附：peek 12% / half 50% / full 92%（规格 Task 1.4 Step 2）。 */
object DrawerSnap {
    const val PEEK = 0.12f
    const val HALF = 0.5f
    const val FULL = 0.92f
    val levels = listOf(PEEK, HALF, FULL)

    /**
     * 跟手释放后的目标档。[downwardVelocityFraction] = 下滑速度(px/s) / 容器高(px)，
     * 显著滑动朝方向推进一步，否则就近吸附。
     */
    fun target(fraction: Float, downwardVelocityFraction: Float): Float {
        val f = fraction.coerceIn(levels.first(), levels.last())
        return when {
            downwardVelocityFraction > 1f ->
                levels.lastOrNull { it < f - 0.01f } ?: levels.first()
            downwardVelocityFraction < -1f ->
                levels.firstOrNull { it > f + 0.01f } ?: levels.last()
            else -> levels.minBy { abs(it - f) }
        }
    }
}

/** 抽屉高度状态：fraction = 抽屉高 / 容器高。 */
class DebugDrawerState(initialFraction: Float = DrawerSnap.PEEK) {
    internal val animatable = Animatable(initialFraction)

    val fraction: Float get() = animatable.value

    suspend fun dragTo(fraction: Float) {
        animatable.snapTo(fraction.coerceIn(MIN, MAX))
    }

    suspend fun settle(target: Float) {
        animatable.animateTo(target.coerceIn(MIN, MAX), ZqSpring)
    }

    companion object {
        const val MIN = 0.06f
        const val MAX = 0.95f
    }
}

/** 时间线条目展示文本（级别 + 内容 + 折叠计数）。 */
internal fun entryLabel(entry: TimelineEntry): String = buildString {
    entry.event.level?.let { append("[$it] ") }
    append(
        entry.event.text ?: entry.event.message ?: entry.event.reason ?: entry.event.url ?: "",
    )
    if (entry.count > 1) append(" ×${entry.count}")
}

/**
 * 底部调试抽屉：三页签（报错/Console/网络）+ 降级标注 + 模式切换 + 「交给 Agent」。
 * 抽屉高度跟手、释放按速度/就近 spring 吸附三档。
 */
@Composable
fun DebugDrawer(
    state: DebugDrawerState,
    mode: RunnerMode,
    onModeChange: (RunnerMode) -> Unit,
    timeline: Timeline = Timeline(emptyList(), emptyList(), emptyList(), emptyList()),
    capability: CapabilityReport? = null,
    onSendToAgent: () -> Unit = {},
    onOpenChat: () -> Unit = {},
    onReload: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val tracker = remember { VelocityTracker() }
    val tabs = remember(timeline) {
        listOf("报错" to timeline.problems, "Console" to timeline.console, "网络" to timeline.network)
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // 派生安全页签：不在组合期写状态（页签数变化时自动回落）
    val safeTab = tab.coerceIn(0, tabs.lastIndex.coerceAtLeast(0))

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val containerH = constraints.maxHeight.toFloat()
        val drawerH = containerH * state.fraction

        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(with(density) { drawerH.toDp() })
                .testTag("debug-drawer"),
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            tonalElevation = 3.dp,
        ) {
            Column(Modifier.fillMaxSize()) {
                // 拖拽把手：跟手 + 释放吸附
                var startFraction by remember { mutableFloatStateOf(0f) }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp)
                        .testTag("drawer-handle")
                        .pointerInput(containerH) {
                            detectVerticalDragGestures(
                                onDragStart = {
                                    startFraction = state.fraction
                                    tracker.resetTracking()
                                },
                                onVerticalDrag = { change, dy ->
                                    tracker.addPosition(change.uptimeMillis, change.position)
                                    change.consume()
                                    scope.launch {
                                        state.dragTo(startFraction - dy / containerH)
                                    }
                                },
                                onDragEnd = {
                                    val v = tracker.calculateVelocity()
                                    scope.launch {
                                        state.settle(DrawerSnap.target(state.fraction, v.y / containerH))
                                    }
                                },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .width(44.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.outline),
                    )
                }
                // 头部：AI 入口（收起态即可见——真机反馈：藏在展开层里发现不了）+ 降级标注 + 重载
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = onSendToAgent,
                        modifier = Modifier.testTag("drawer-send-agent"),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Text("🤖 交给 Agent", style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = onOpenChat,
                        modifier = Modifier.testTag("drawer-open-chat"),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text("AI 对话", style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(Modifier.width(8.dp))
                    if (capability?.degraded == true) {
                        Text(
                            "已降级 WebGL",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFFB26A00),
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onReload) { Text("重载") }
                }
                // 三页签
                TabRow(selectedTabIndex = safeTab) {
                    tabs.forEachIndexed { i, (name, list) ->
                        Tab(
                            selected = safeTab == i,
                            onClick = { tab = i },
                            modifier = Modifier.testTag("drawer-tab-$i"),
                            text = { Text("$name (${list.size})") },
                        )
                    }
                }
                // 列表
                val current = tabs[safeTab].second
                LazyColumn(Modifier.fillMaxSize()) {
                    if (current.isEmpty()) {
                        item {
                            Text(
                                "暂无记录",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                    items(
                        current,
                        // seq+t 组合键：桥 seq 在渲染进程重建后会重置，拼 t 保证唯一
                        key = { "${it.event.seq}-${it.event.t}" },
                    ) { entry ->
                        Text(
                            entryLabel(entry),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                }
                // 底部：模式切换 + 交给 Agent
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RunnerModeSwitcher(mode, onModeChange, Modifier.weight(1f))
                    // Aurora Glass：主进程按钮换 GlowButton（靛蓝外发光，§5.1 发光交互）
                    com.zhique.runner.ui.components.GlowButton(
                        onClick = onSendToAgent,
                        label = "交给 Agent",
                        testTag = "agent-button",
                    )
                }
            }
        }
    }
}
