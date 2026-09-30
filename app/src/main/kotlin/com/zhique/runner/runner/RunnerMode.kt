package com.zhique.runner.runner

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** 运行器三模式（规格 §5.3 屏 4：底部抽屉 / 上下分屏 / 悬浮球浮层）。 */
enum class RunnerMode(val label: String) {
    DRAWER("抽屉"),
    SPLIT("分屏"),
    BUBBLE("悬浮球"),
}

/** 右上角胶囊三态切换器（选中态写回 projectMeta.runnerMode，由调用方负责持久化）。 */
@Composable
fun RunnerModeSwitcher(
    mode: RunnerMode,
    onModeChange: (RunnerMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .testTag("mode-switcher")
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RunnerMode.entries.forEach { m ->
            val selected = m == mode
            Text(
                text = m.label,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                    )
                    .clickable { onModeChange(m) }
                    .testTag("mode-${m.name}")
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}
