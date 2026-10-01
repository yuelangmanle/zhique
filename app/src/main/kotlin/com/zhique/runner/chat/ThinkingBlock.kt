package com.zhique.runner.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.zhique.runner.ui.theme.ZqMotion
import java.util.Locale

/**
 * 思考流折叠块（规格 X7，类 ZCode 形态）：
 * 默认折叠——摘要行「已思考 N 秒 · M tokens ▸」；点击平滑展开逐字回放，再点收起。
 * 思考与正文是两个独立渲染区，本块永不嵌入正文流。
 *
 * [seconds] 为 null（仍在思考）时显示「思考中…」；展开回放为快速逐字动画，
 * 已看过的增量在 [remember] 缓冲中，收起再展开不重置。
 */
@Composable
fun ThinkingBlock(
    thinking: String,
    seconds: Double?,
    tokens: Int,
    modifier: Modifier = Modifier,
    streaming: Boolean = false,
) {
    if (thinking.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    // 逐字回放游标：首次展开从 0 推进到当前已缓冲长度；不随 thinking 重建，
    // 新增量到达时从当前游标续播到新长度，收起再展开不重置
    val reveal = remember { Animatable(0f) }

    LaunchedEffect(expanded, thinking) {
        if (expanded) {
            val target = thinking.length.toFloat()
            if (reveal.value < target) {
                // M9：思考流回放改非线性 spring（规格 §5.2 禁线性节奏）
                reveal.animateTo(target, ZqMotion.Reveal)
            }
        }
    }

    Column(modifier.fillMaxWidth().testTag("thinking-block")) {
        val streamingPlaceholder = seconds == null && streaming
        val summary = if (streamingPlaceholder) {
            "思考中…"
        } else {
            String.format(Locale.ROOT, "已思考 %.1f s · %d tokens ", seconds ?: 0.0, tokens) +
                if (expanded) "◂" else "▸"
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp)
                .testTag("thinking-toggle"),
        ) {
            Text(
                text = summary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Text(
                text = thinking.take(reveal.value.toInt().coerceAtMost(thinking.length)),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.SansSerif,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = 12.dp, bottom = 6.dp)
                    .testTag("thinking-replay-text"),
            )
        }
    }
}

/** 思考 tokens 粗估（中英混合按 2 字符 ≈ 1 token 的折中口径，仅展示用）。 */
internal fun estimateTokens(text: String): Int = (text.length + 1) / 2
