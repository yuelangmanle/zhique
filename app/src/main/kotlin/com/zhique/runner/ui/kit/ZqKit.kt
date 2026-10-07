package com.zhique.runner.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zhique.core.telemetry.DebugHub

/**
 * ZqKit：织雀 UI 基建层（可商用级克制视觉）+ 调试插桩。
 *
 * 视觉准则：平色面（无渐变/无毛玻璃）、1dp 细线描边、单一强调色、
 * 8/12/16/24 间距栅格、字重与灰阶承担层级。
 *
 * 插桩准则：所有按钮经 [zqTrack] 上报 `ui.click`（action 点状命名）；
 * 根级 [zqTapSensor] 兜底记录全部触摸（含未迁移按钮），两层合流 DebugHub。
 */

// ---- 插桩 ----

/** 按钮级跟踪：包裹 onClick，事件带 action 名与当前屏幕。 */
private fun zqOnClick(action: String, onClick: () -> Unit): () -> Unit = {
    DebugHub.event("ui", "click", detail = mapOf("action" to action))
    onClick()
}

/**
 * 根级触摸传感器（每个按钮/任何可点的兜底全覆盖）：按下→抬起记一次 `ui.tap`，
 * 坐标为根布局局部系。不消费事件，不影响子组件交互。
 */
fun Modifier.zqTapSensor(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val up = waitForUpOrCancellation()
        if (up != null) {
            DebugHub.event(
                "ui", "tap",
                detail = mapOf("x" to "${up.position.x.toInt()}", "y" to "${up.position.y.toInt()}"),
            )
        }
    }
}

// ---- 组件 ----

/** 细线描边卡片：平色面 + hairline，承担全部分组/列表容器语义。 */
@Composable
fun ZqCard(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(content = content)
    }
}

/** 屏页顶栏：56dp、标题左对齐、底部细线、动作右侧。 */
@Composable
fun ZqTopBar(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        actions()
    }
}

/** 区块小标题（全大写灰阶，克制的分组语义）。 */
@Composable
fun ZqSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = 20.dp, bottom = 8.dp),
    )
}

/** 主按钮（填充，唯一强调色面）。 */
@Composable
fun ZqButton(
    action: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Button(onClick = zqOnClick(action, onClick), modifier = modifier, enabled = enabled, content = content)
}

/** 次按钮（描边）。 */
@Composable
fun ZqOutlinedButton(
    action: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    OutlinedButton(onClick = zqOnClick(action, onClick), modifier = modifier, enabled = enabled, content = content)
}

/** 弱化按钮（文字）。 */
@Composable
fun ZqTextButton(
    action: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    TextButton(onClick = zqOnClick(action, onClick), modifier = modifier, enabled = enabled, content = content)
}

/** 图标按钮（action 必填：图标无文字语义）。 */
@Composable
fun ZqIconButton(
    action: String,
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    IconButton(onClick = zqOnClick(action, onClick), modifier = modifier, enabled = enabled) {
        Icon(icon, contentDescription = contentDescription)
    }
}

/** 静态键值行（调试页/诊断页的紧凑展示）。 */
@Composable
fun ZqKeyValue(key: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            key,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

/** 空态占位（克制的居中灰字）。 */
@Composable
fun ZqEmpty(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
