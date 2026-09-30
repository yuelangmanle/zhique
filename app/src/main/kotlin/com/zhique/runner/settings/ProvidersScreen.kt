package com.zhique.runner.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.AssistChip
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * AI 服务商屏（规格 §5.3 屏 9 的 M3 部分）：已接入列表 + 新增/编辑表单。
 * Apilot 双向流转与织雀提示词入口占位（待 M8 / M5 接线）。
 */
@Composable
fun ProvidersScreen(
    controller: ProvidersController,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val providers by controller.providers.collectAsState()
    val form by controller.form.collectAsState()
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("AI 服务商", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (form == null) {
                    Button(onClick = controller::startNew, modifier = Modifier.testTag("provider-add")) {
                        Text("添加")
                    }
                }
            }

            if (form == null) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (providers.isEmpty()) {
                        Text("还没有接入任何服务商", color = MaterialTheme.colorScheme.secondary)
                    }
                    providers.forEach { p ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .testTag("provider-row-${p.name}"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                                Text(p.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${badgeOf(p.protocol)} · ${modalityBadgeOf(p)} · ${p.model}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                )
                            }
                            IconButton(onClick = { controller.delete(p.id) }, modifier = Modifier.testTag("provider-del-${p.name}")) {
                                Icon(Icons.Filled.Delete, contentDescription = "删除")
                            }
                            OutlinedButton(onClick = { controller.edit(p) }, modifier = Modifier.testTag("provider-edit-${p.name}")) {
                                Text("编辑")
                            }
                        }
                    }
                }
            } else {
                ProviderFormView(controller, form!!)
            }
        }
    }
}

private fun badgeOf(protocol: String): String = when (protocol) {
    com.zhique.core.ai.Protocol.ANTHROPIC_MESSAGES -> "anthropic"
    com.zhique.core.ai.Protocol.GOOGLE_GENAI -> "gemini"
    else -> "openai 兼容"
}

private fun modalityBadgeOf(p: ProviderConfig): String = when (p.modalityManual) {
    "vision" -> "vision"
    "text" -> "text"
    else -> com.zhique.core.ai.ModelCatalog.modality(p.model).name.lowercase()
}

@Composable
private fun ProviderFormView(controller: ProvidersController, form: ProviderForm) {
    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .testTag("provider-form"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = form.name,
            onValueChange = { v -> controller.updateForm { it.copy(name = v) } },
            label = { Text("名称") },
            modifier = Modifier.fillMaxWidth().testTag("provider-name"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                com.zhique.core.ai.Protocol.OPENAI_COMPATIBLE to "OpenAI 兼容",
                com.zhique.core.ai.Protocol.ANTHROPIC_MESSAGES to "Anthropic",
                com.zhique.core.ai.Protocol.GOOGLE_GENAI to "Gemini",
            ).forEach { (proto, label) ->
                FilterChip(
                    selected = form.protocol == proto,
                    onClick = { controller.onProtocolChange(proto) },
                    label = { Text(label) },
                    modifier = Modifier.testTag("proto-$proto"),
                )
            }
        }
        OutlinedTextField(
            value = form.baseUrl,
            onValueChange = { v -> controller.updateForm { it.copy(baseUrl = v) } },
            label = { Text("Base URL") },
            modifier = Modifier.fillMaxWidth().testTag("provider-base"),
        )
        OutlinedTextField(
            value = form.apiKey,
            onValueChange = { v -> controller.updateForm { it.copy(apiKey = v) } },
            label = { Text("API Key（密文存储）") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth().testTag("provider-key"),
        )
        OutlinedTextField(
            value = form.model,
            onValueChange = { v -> controller.updateForm { it.copy(model = v) } },
            label = { Text("默认模型") },
            modifier = Modifier.fillMaxWidth().testTag("provider-model"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("能力：${form.modalityBadge}", style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.testTag("modality-badge"))
            OutlinedButton(onClick = controller::fetchModels, enabled = !form.busy,
                modifier = Modifier.testTag("fetch-models")) { Text("拉取模型列表") }
            OutlinedButton(onClick = controller::probeVision, enabled = !form.busy,
                modifier = Modifier.testTag("probe-vision")) { Text("探测视觉") }
        }
        if (form.modelOptions.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                form.modelOptions.take(6).forEach { m ->
                    AssistChip(onClick = { controller.updateForm { it.copy(model = m) } }, label = { Text(m) })
                }
            }
        }
        OutlinedTextField(
            value = form.maxOutputManual.toString(),
            onValueChange = { v ->
                controller.updateForm { it.copy(maxOutputManual = v.filter { c -> c.isDigit() }.toIntOrNull() ?: 0) }
            },
            label = { Text("输出上限（0 = 按模型默认）") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().testTag("provider-maxout"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = form.modalityManual?.let { "手动覆盖：$it" } ?: "能力跟知识库",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = { controller.setModalityManual(if (form.modalityManual == "vision") "text" else "vision") }) {
                Text("切换覆盖")
            }
        }
        form.notice?.let {
            Text(it, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.testTag("provider-notice"))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = controller::save, modifier = Modifier.testTag("provider-save")) { Text("保存") }
            OutlinedButton(onClick = controller::cancelForm) { Text("取消") }
        }
    }
}
