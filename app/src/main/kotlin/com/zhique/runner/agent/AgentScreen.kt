package com.zhique.runner.agent

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.zhique.runner.ui.theme.ZqMotion
import com.zhique.runner.chat.ThinkingBlock
import com.zhique.runner.chat.UsageRing
import kotlin.math.roundToInt

private val Green = Color(0xFF2E7D32)
private val Red = Color(0xFFC62828)

/**
 * Agent 会话屏（规格 §5.3 屏 5）：目标输入 + 步骤时间线（思考折叠）+ diff 卡 +
 * 预算三弧环（轮/token/时间）+ 暂停终止/续 5 轮 + 回滚快照 + 能力徽章 +
 * AwaitConfirm 批准卡 + 「压缩上下文」摘要卡（保留/丢弃 + token 对比计数动画）。
 * 顶栏上下文环接 assembler.usage 真值，≥80% 琥珀（UsageRing 语义恒定）。
 */
@Composable
fun AgentScreen(
    controller: AgentController,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsState()
    AgentContent(
        state = state,
        onBack = onBack,
        onGoalChange = controller::setGoal,
        onStart = controller::start,
        onStop = controller::stop,
        onResumeFive = controller::resumeFive,
        onCompact = controller::compactNow,
        onDismissCompression = controller::dismissCompression,
        onRollback = controller::rollback,
        onApprove = controller::approve,
        onDeny = controller::deny,
        onAutoChange = controller::setAutoApproved,
        modifier = modifier,
    )
}

/** 纯渲染形态（测试直接注入状态）。 */
@Composable
fun AgentContent(
    state: AgentUiState,
    onBack: () -> Unit,
    onGoalChange: (String) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onResumeFive: () -> Unit,
    onCompact: () -> Unit,
    onDismissCompression: () -> Unit,
    onRollback: (String) -> Unit,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    onAutoChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var showSnapshots by remember { mutableStateOf(false) }
    // Aurora Glass（M9，质量审查 Important-2）：运行域强制深空主题 + 深空光斑
    com.zhique.runner.ui.theme.ZqTheme(darkTheme = true) {
    com.zhique.runner.ui.components.AuroraBackground(
        modifier = modifier.fillMaxSize(),
        domain = com.zhique.runner.ui.components.AuroraDomain.DARK,
    ) {
    Surface(modifier.fillMaxSize(), color = androidx.compose.ui.graphics.Color.Transparent) {
        Column {
            // 顶栏：返回 / 项目名 / 能力徽章 / 上下文环真值
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("agent-back")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        state.projectName.ifBlank { "Agent" },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        if (state.vision) "vision · 截图自查" else "文本 · DOM 观察",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.testTag("capability-badge"),
                    )
                }
                UsageRing(
                    label = "上下文",
                    fraction = state.contextUsage,
                    modifier = Modifier.testTag("agent-context-ring"),
                )
            }

            // 目标输入 + 启动
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = state.goal,
                    onValueChange = onGoalChange,
                    modifier = Modifier.weight(1f).testTag("agent-goal-input"),
                    placeholder = { Text("描述目标：修好星空动画的报错") },
                    enabled = !state.running,
                    singleLine = true,
                )
                Button(
                    onClick = onStart,
                    enabled = !state.running && state.goal.isNotBlank(),
                    modifier = Modifier.testTag("agent-start"),
                ) { Text("交给 Agent") }
            }

            // 预算三弧 + 操作行
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BudgetRing(
                    rounds = state.budget.roundsUsed.toFloat() / state.budget.maxRounds.coerceAtLeast(1),
                    tokens = state.budget.tokensUsed / 16384f,
                    time = state.budget.elapsedMs / 600_000f,
                    modifier = Modifier.testTag("budget-ring"),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "轮 ${state.budget.roundsUsed}/${state.budget.maxRounds}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.testTag("budget-text"),
                )
                Spacer(Modifier.weight(1f))
                if (state.running) {
                    OutlinedButton(onClick = onStop, modifier = Modifier.testTag("agent-stop")) { Text("终止") }
                } else {
                    OutlinedButton(onClick = onResumeFive, modifier = Modifier.testTag("agent-resume5")) { Text("续 5 轮") }
                }
                TextButton(
                    onClick = { showSnapshots = true },
                    modifier = Modifier.testTag("snapshots-button"),
                ) { Text("快照") }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("agent-auto-row")) {
                    Text("全自动", style = MaterialTheme.typography.labelSmall)
                    androidx.compose.material3.Switch(
                        checked = state.autoApproved,
                        onCheckedChange = onAutoChange,
                        modifier = Modifier.testTag("agent-auto-switch"),
                    )
                }
                TextButton(onClick = onCompact, modifier = Modifier.testTag("compact-button")) {
                    Text("压缩上下文")
                }
            }

            state.error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 12.dp).testTag("agent-error"),
                )
            }

            // 步骤时间线
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp).testTag("agent-timeline"),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(state.rounds, key = { "r${it.no}-${it.content.hashCode()}" }) { round ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp)) {
                            Text(
                                "第 ${round.no} 轮",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                            ThinkingBlock(
                                thinking = round.thinking,
                                seconds = if (state.running) null else 0.0,
                                tokens = (round.thinking.length + 1) / 2,
                                streaming = state.running,
                            )
                            if (round.content.isNotBlank()) {
                                Text(
                                    round.content.take(2000),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.testTag("round-content-${round.no}"),
                                )
                            }
                        }
                    }
                }
                items(state.steps.size) { idx -> StepCard(state.steps[idx]) }
                if (state.finished) {
                    item {
                        Text(
                            "✓ 会话完成",
                            color = Green,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(8.dp).testTag("agent-finished"),
                        )
                    }
                }
            }
        }
    }

    // AwaitConfirm 批准卡（外发工具，规格 §6：默认逐项确认）
    val confirm = state.awaitConfirm
    if (confirm != null) {
        AlertDialog(
            onDismissRequest = onDeny,
            title = { Text("外发动作待批准") },
            text = { Text("工具「${confirm.tool}」将执行外发操作：\n${confirm.argsJson.take(300)}") },
            confirmButton = {
                TextButton(onClick = onApprove, modifier = Modifier.testTag("confirm-approve")) { Text("批准") }
            },
            dismissButton = {
                TextButton(onClick = onDeny, modifier = Modifier.testTag("confirm-deny")) { Text("拒绝") }
            },
            modifier = Modifier.testTag("confirm-card"),
        )
    }

    // 快照回滚抽屉
    if (showSnapshots) {
        AlertDialog(
            onDismissRequest = { showSnapshots = false },
            title = { Text("回滚到任意快照") },
            text = {
                Column(Modifier.height(320.dp)) {
                    if (state.snapshots.isEmpty()) {
                        Text("暂无快照", modifier = Modifier.testTag("snapshots-empty"))
                    }
                    LazyColumn {
                        items(state.snapshots, key = { it.id }) { snap ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(snap.label, style = MaterialTheme.typography.bodySmall)
                                    Text(
                                        java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault())
                                            .format(java.util.Date(snap.at)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.secondary,
                                    )
                                }
                                TextButton(
                                    onClick = {
                                        onRollback(snap.id)
                                        showSnapshots = false
                                    },
                                    modifier = Modifier.testTag("rollback-${snap.id}"),
                                ) { Text("回滚") }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSnapshots = false }) { Text("关闭") }
            },
        )
    }

    // 压缩摘要卡
    state.compression?.let { report ->
        CompressionCard(
            kept = report.kept,
            dropped = report.dropped,
            before = report.before,
            after = report.after,
            onDismiss = onDismissCompression,
        )
    }
    }
    }
}

/** 一步工具卡 + diff 卡。 */
@Composable
private fun StepCard(step: StepUi) {
    Card(
        Modifier.fillMaxWidth().testTag("step-${step.tool}"),
        colors = CardDefaults.cardColors(
            containerColor = when (step.ok) {
                true -> Color(0xFFE8F5E9)
                false -> Color(0xFFFDECEA)
                null -> MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (step.ok) {
                        true -> "✓"
                        false -> "✗"
                        null -> "…"
                    },
                    color = when (step.ok) {
                        true -> Green
                        false -> Red
                        null -> MaterialTheme.colorScheme.secondary
                    },
                )
                Spacer(Modifier.width(6.dp))
                Text(step.tool, style = MaterialTheme.typography.titleSmall)
            }
            if (step.detail.isNotBlank() && step.diff == null) {
                Text(step.detail.take(400), style = MaterialTheme.typography.bodySmall, maxLines = 4)
            }
            step.diff?.let { diff -> DiffCard(diff) }
        }
    }
}

/** diff 卡：红绿行。 */
@Composable
private fun DiffCard(diff: DiffUi) {
    Column(Modifier.fillMaxWidth().testTag("diff-card")) {
        Text(
            diff.path,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary,
        )
        Column(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(6.dp))
                .padding(6.dp),
        ) {
            diff.lines.take(30).forEach { line ->
                Text(
                    "${line.type} ${line.text}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = when (line.type) {
                        '-' -> Red
                        '+' -> Green
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            when (line.type) {
                                '-' -> Color(0x1AC62828)
                                '+' -> Color(0x1A2E7D32)
                                else -> Color.Transparent
                            },
                        )
                        .testTag("diff-line-${line.type}"),
                )
            }
        }
    }
}

/** 预算三弧环：外弧=轮、中弧=token、内弧=时长。 */
@Composable
fun BudgetRing(rounds: Float, tokens: Float, time: Float, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val secondary = MaterialTheme.colorScheme.secondary
    Box(modifier.size(44.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(44.dp)) {
            val outer = 3.dp.toPx()
            val mid = 2.5f.dp.toPx()
            val inner = 2f.dp.toPx()
            val center = androidx.compose.ui.geometry.Offset(size.width / 2, size.height / 2)

            fun ring(radius: Float, stroke: Float, fraction: Float, color: Color) {
                val topLeft = androidx.compose.ui.geometry.Offset(center.x - radius, center.y - radius)
                val arcSize = androidx.compose.ui.geometry.Size(radius * 2, radius * 2)
                drawArc(
                    color = Color(0x2246509F),
                    startAngle = -90f, sweepAngle = 360f, useCenter = false,
                    topLeft = topLeft, size = arcSize, style = Stroke(stroke),
                )
                drawArc(
                    color = color,
                    startAngle = -90f,
                    sweepAngle = 360f * fraction.coerceIn(0f, 1f),
                    useCenter = false,
                    topLeft = topLeft, size = arcSize, style = Stroke(stroke),
                )
            }
            val maxR = size.minDimension / 2 - 1.dp.toPx()
            ring(maxR, outer, rounds, primary)
            ring(maxR - outer - 2.dp.toPx(), mid, tokens, tertiary)
            ring(maxR - outer - mid - 4.dp.toPx(), inner, time, secondary)
        }
    }
}

/** 压缩摘要卡：保留/丢弃清单 + token 对比计数动画。 */
@Composable
fun CompressionCard(
    kept: List<String>,
    dropped: List<String>,
    before: Int,
    after: Int,
    onDismiss: () -> Unit,
) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(before, after) {
        progress.snapTo(0f)
        // M9：进度填充改弹性 spring（规格 §5.2 ④）
        progress.animateTo(1f, ZqMotion.Progress)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("已压缩上下文") },
        text = {
            Column(Modifier.testTag("compression-card")) {
                Text(
                    "${(before * progress.value).roundToInt()} → ${(after * progress.value).roundToInt()} tokens",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.testTag("compression-tokens"),
                )
                Spacer(Modifier.height(8.dp))
                Text("保留：", style = MaterialTheme.typography.labelMedium)
                kept.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.height(6.dp))
                Text("丢弃（压成摘要）：", style = MaterialTheme.typography.labelMedium)
                dropped.take(8).forEach {
                    Text("· $it", style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("compression-dismiss")) { Text("知道了") }
        },
    )
}
