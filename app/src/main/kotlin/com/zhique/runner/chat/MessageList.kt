package com.zhique.runner.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * 消息渲染区（X7）：思考块与正文两个独立渲染区——思考块永远在正文之前、独立组件，
 * 任何情况下思考不插入正文流。assistant 轮带「已续写 N 段」徽标与触顶警告条。
 */
@Composable
internal fun MessageList(
    turns: List<ChatTurn>,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        turns.forEachIndexed { i, turn ->
            if (turn.role == "user") {
                UserBubble(turn, i)
            } else {
                AssistantBubble(turn, i, onContinue)
            }
        }
    }
}

@Composable
private fun UserBubble(turn: ChatTurn, index: Int) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Text(
            text = turn.content,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .testTag("turn-user-$index"),
        )
    }
}

@Composable
private fun AssistantBubble(turn: ChatTurn, index: Int, onContinue: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        ThinkingBlock(
            thinking = turn.thinking,
            seconds = if (turn.thinkingSeconds > 0.0) turn.thinkingSeconds else null,
            tokens = turn.thinkingTokens,
        )
        Text(
            text = turn.content,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .testTag("turn-content-$index"),
        )
        if (turn.segments > 0) {
            Text(
                text = "已续写 ${turn.segments} 段",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .testTag("segment-badge-$index"),
            )
        }
        if (turn.truncated) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .testTag("truncated-bar-$index"),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "已达输出上限",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    textAlign = TextAlign.Start,
                )
                Button(onClick = onContinue, modifier = Modifier.testTag("continue-btn-$index")) {
                    Text("继续输出", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
