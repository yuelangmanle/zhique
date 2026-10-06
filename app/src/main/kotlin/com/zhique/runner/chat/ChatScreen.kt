package com.zhique.runner.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * 对话面板（规格 §5.3 + X7）：顶栏双环（输出 / 上下文占比）+ 消息流 + 输入行。
 * 上下文真值 M4 接（现估算），占比 ≥80% 转琥珀、≥100% 转红。
 */
@Composable
fun ChatScreen(
    controller: ChatController,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    welcomeText: String? = null,
) {
    val state by controller.state.collectAsState()
    val compression by controller.compression.collectAsState()
    val scope = rememberCoroutineScope()
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("chat-back")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                Text("对话", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                UsageRing(
                    label = "输出",
                    fraction = state.outputTokens / OUTPUT_BUDGET,
                    modifier = Modifier.testTag("ring-output"),
                )
                UsageRing(
                    label = "上下文",
                    // 工作预算真值：长期 tokens ÷ 工作预算（窗口 75%，规格 §4.5.4）
                    fraction = state.contextFraction(controller.contextBudget.workLimit),
                    modifier = Modifier
                        .padding(start = 10.dp)
                        .testTag("ring-context"),
                )
                TextButton(
                    onClick = { scope.launch { controller.compactNow() } },
                    enabled = !state.busy,
                    modifier = Modifier.testTag("chat-compact"),
                ) { Text("压缩上下文") }
            }

            MessageList(
                turns = state.turns,
                onContinue = controller::continueOutput,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                streaming = state.streaming,
                liveThinking = state.liveThinking,
                liveContent = state.liveContent,
                error = state.error,
                welcomeText = welcomeText,
            )

            // 压缩摘要卡（保留/丢弃清单 + token 前后对比，复用 Agent 卡）
            compression?.let { report ->
                com.zhique.runner.agent.CompressionCard(
                    kept = report.kept,
                    dropped = report.dropped,
                    before = report.before,
                    after = report.after,
                    onDismiss = controller::dismissCompression,
                )
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = state.input,
                    onValueChange = controller::setInput,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("chat-input"),
                    placeholder = { Text("问点什么…") },
                    enabled = !state.busy,
                )
                IconButton(
                    onClick = { controller.send(state.input) },
                    enabled = !state.busy && state.input.isNotBlank(),
                    modifier = Modifier.testTag("chat-send"),
                ) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "发送")
                }
            }
        }
    }
}

/** 顶部用量环：≥80% 琥珀（等待），≥100% 红（错误），其余主色。 */
@Composable
internal fun UsageRing(label: String, fraction: Float, modifier: Modifier = Modifier) {
    val clamped = fraction.coerceIn(0f, 1f)
    val color = when {
        fraction >= 1f -> MaterialTheme.colorScheme.error
        fraction >= 0.8f -> Color(0xFFF59E0B) // 琥珀：等待/注意（语义色恒定）
        else -> MaterialTheme.colorScheme.primary
    }
    val bg = MaterialTheme.colorScheme.surfaceVariant
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(26.dp)) {
                drawArc(
                    color = bg, startAngle = -90f, sweepAngle = 360f, useCenter = false,
                    style = Stroke(width = 3.dp.toPx()),
                )
                drawArc(
                    color = color, startAngle = -90f, sweepAngle = 360f * clamped, useCenter = false,
                    style = Stroke(width = 3.dp.toPx()),
                )
            }
            Text("${(fraction * 100).roundToInt()}", style = MaterialTheme.typography.labelSmall)
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
    }
}

/** 全局输出预算（规格 §4.4.1：全局 16384）。 */
internal const val OUTPUT_BUDGET = 16384f
