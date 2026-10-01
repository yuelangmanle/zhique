package com.zhique.runner.settings

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhique.core.paste.PromptBridge

/**
 * 织雀提示词桥屏（Task 8.2，规格 §5.3 屏 14 的 M8 部分）：
 * 想法输入 + zq 能力多选 chip + 实时预览 + 复制/系统分享。
 * 粘贴预览的 CompatHint 反向兜底入口经 [initialIdea] 预填。
 */
@Composable
fun PromptBridgeScreen(
    controller: PromptBridgeController,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    initialIdea: String? = null,
    onToast: (String) -> Unit = {},
) {
    val state by controller.state.collectAsState()
    val context = LocalContext.current
    remember(initialIdea) { initialIdea?.let { controller.setIdea(it) }; true }
    val preview = remember(state) { controller.preview() }
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .testTag("pb-scroll"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row {
                IconButton(onClick = onBack, modifier = Modifier.testTag("pb-back")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                Text(
                    "织雀提示词",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            Text(
                "把想法与用得上的 zq.* 能力说明拼成一条提示词，复制或分享给任意外部 AI，" +
                    "拿回单文件完整 HTML 再粘贴回织雀运行。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
            OutlinedTextField(
                value = state.idea,
                onValueChange = controller::setIdea,
                label = { Text("你想做什么？（第一句，会原样带给外部 AI）") },
                modifier = Modifier.fillMaxWidth().testTag("pb-idea"),
                minLines = 2,
            )
            Text("勾选用得上的手机能力（只带勾选的文档段）", style = MaterialTheme.typography.titleSmall)
            FlowChips(
                selected = state.selected,
                onToggle = controller::toggle,
            )
            Card(Modifier.fillMaxWidth().testTag("pb-preview-card")) {
                Text(
                    preview,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    modifier = Modifier.padding(12.dp).testTag("pb-preview"),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        controller.copyToClipboard(context)
                        onToast("已复制全文，去外部 AI 粘贴吧")
                    },
                    modifier = Modifier.weight(1f).testTag("pb-copy"),
                ) { Text("复制全文") }
                OutlinedButton(
                    onClick = {
                        runCatching { context.startActivity(controller.shareIntent()) }
                            .onFailure { onToast("没有可用的分享目标") }
                    },
                    modifier = Modifier.weight(1f).testTag("pb-share"),
                ) { Text("系统分享") }
            }
        }
    }
}

/** zq 能力多选 chip 行（横向滚动，避免十项换行失控）。 */
@Composable
private fun FlowChips(selected: Set<String>, onToggle: (String) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()).testTag("pb-chips"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PromptBridge.KNOWN_APIS.forEach { id ->
            FilterChip(
                selected = id in selected,
                onClick = { onToggle(id) },
                label = { Text("zq.$id") },
                modifier = Modifier.testTag("pb-chip-$id"),
            )
        }
    }
}
