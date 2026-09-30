package com.zhique.runner.paste

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 智能粘贴预览（规格 §5.3 屏 3）：解析结果卡 + 清洗报告（可展开/整体撤销）
 * + 命名编辑 +「存为草稿 / 运行 ▶」+「粘贴后自动运行」开关。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PastePreviewScreen(
    controller: PastePreviewController,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s by controller.state.collectAsState()
    var reportExpanded by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.testTag("paste-preview-screen"),
        topBar = {
            TopAppBar(
                title = { Text("智能粘贴预览") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("paste-back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        bottomBar = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = { controller.saveAsDraft() },
                    modifier = Modifier.weight(1f).testTag("paste-save-draft"),
                ) { Text("存为草稿") }
                Button(
                    onClick = { controller.saveAndRun() },
                    modifier = Modifier.weight(1f).testTag("paste-run"),
                ) { Text("运行 ▶") }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---- 解析结果卡 ----
            Card(
                modifier = Modifier.fillMaxWidth().testTag("paste-form-card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            s.formLabel,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.testTag("paste-form-label"),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "置信度 ${(s.confidence * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    LinearProgressIndicator(
                        progress = { s.confidence },
                        modifier = Modifier.fillMaxWidth().testTag("paste-confidence"),
                    )
                    if (s.aiFallbackSuggested) {
                        Text(
                            "识别置信度较低，建议保存后用 AI 兜底解析（仅结构化，不改逻辑）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("paste-ai-fallback"),
                        )
                    }
                    if (s.aiFallbackUsed) {
                        Text(
                            "已用 AI 兜底解析（仅结构化，不改逻辑）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.testTag("paste-ai-fallback-used"),
                        )
                    }
                    if (s.hints.isNotEmpty()) {
                        Text(
                            "检测到 ${s.hints.size} 项兼容性提示：${s.hints.joinToString("、") { it.api }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("paste-hints"),
                        )
                    }
                    s.error?.let { error ->
                        Text(
                            error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("paste-error"),
                        )
                    }
                }
            }

            // ---- 清洗报告卡 ----
            Card(
                modifier = Modifier.fillMaxWidth().testTag("paste-report"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(
                    Modifier
                        .padding(14.dp)
                        .animateContentSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "清洗报告（${s.actions.size}）",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = { reportExpanded = !reportExpanded },
                            modifier = Modifier.testTag("paste-report-toggle"),
                        ) { Text(if (reportExpanded) "收起" else "展开") }
                        TextButton(
                            onClick = { controller.setCleaning(!s.cleaningApplied) },
                            modifier = Modifier.testTag("paste-undo-clean"),
                        ) {
                            Text(if (s.cleaningApplied) "撤销清洗" else "恢复清洗")
                        }
                    }
                    if (reportExpanded) {
                        if (s.actions.isEmpty()) {
                            Text(
                                if (s.cleaningApplied) "没有需要清洗的内容" else "已撤销清洗（原始输入重跑）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            s.actions.forEach { action ->
                                Column(Modifier.testTag("paste-report-item")) {
                                    Text(action.kind, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        action.excerpt,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ---- 命名 ----
            OutlinedTextField(
                value = s.name,
                onValueChange = controller::setName,
                label = { Text("项目名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("paste-name"),
            )

            // ---- 粘贴后自动运行（DataStore 通用偏好键，M5 设置屏复用） ----
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("粘贴后自动运行", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "关闭时停在预览（默认）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = s.autoRun,
                    onCheckedChange = { controller.setAutoRun(it) },
                    modifier = Modifier.testTag("paste-auto-run"),
                )
            }
            Spacer(Modifier.size(8.dp))
        }
    }
}
