package com.zhique.runner.settings

import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * AI 服务商屏（规格 §5.3 屏 9 的 M3 部分）：已接入列表 + 新增/编辑表单。
 * M8：Apilot「双向流转」两张动作卡（从 Apilot 接入 / 同步到 Apilot）+
 * 安装检测 + 上次同步时间 + 桥接记录清除（§4.9）；织雀提示词入口（§4.10）。
 */
@Composable
fun ProvidersScreen(
    controller: ProvidersController,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    apilot: ApilotController? = null,
    onOpenPromptBridge: (() -> Unit)? = null,
) {
    val providers by controller.providers.collectAsState()
    val form by controller.form.collectAsState()
    val context = LocalContext.current
    val apilotState: ApilotController.UiState? = apilot?.state?.collectAsState()?.value
    // 显式稳定 key 注册（真机 B7：rememberLauncherForActivityResult 默认 key 含
    // 随机成分，MIUI 上 FragmentActivity 校验 requestCode 只取低 16 位，重复
    // 注册累积后越界崩溃 "Can only use lower 16 bits for requestCode"）
    val registry = requireNotNull(androidx.activity.compose.LocalActivityResultRegistryOwner.current) {
        "Activity Result registry 不可用（宿主必须是 ComponentActivity）"
    }.activityResultRegistry
    val pickLauncher = remember {
        registry.register(
            "zhique-apilot-pick",
            ActivityResultContracts.StartActivityForResult(),
        ) { result: ActivityResult ->
            apilot?.handleActivityResult(result.resultCode, result.data, context.contentResolver)
        }
    }
    val syncLauncher = remember {
        registry.register(
            "zhique-apilot-sync",
            ActivityResultContracts.StartActivityForResult(),
        ) { result: ActivityResult ->
            apilot?.handleSyncResult(result.resultCode)
        }
    }
    // 网关一键授权（Apilot v2.5.0+ GRANT_GATEWAY）：回传 baseUrl/model 直接落 Provider
    val gatewayLauncher = remember {
        registry.register(
            "zhique-apilot-gateway",
            ActivityResultContracts.StartActivityForResult(),
        ) { result: ActivityResult ->
            apilot?.handleGatewayResult(result.resultCode, result.data)
        }
    }
    val scope = rememberCoroutineScope()
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("AI 服务商", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (form == null) {
                    onOpenPromptBridge?.let { open ->
                        OutlinedButton(onClick = open, modifier = Modifier.testTag("open-prompt-bridge")) {
                            Text("织雀提示词")
                        }
                    }
                    Button(onClick = controller::startNew, modifier = Modifier.padding(start = 8.dp).testTag("provider-add")) {
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
                if (apilot != null && apilotState != null) {
                    ApilotSection(apilot, apilotState!!, pickLauncher, syncLauncher, gatewayLauncher, scope)
                }
            } else {
                ProviderFormView(controller, form!!)
            }
        }
    }
}

/**
 * Apilot 双向流转区（§4.9）：安装检测 + ← 接入 / → 同步两张动作卡 +
 * 上次同步时间 + 记录清除。未安装时动作卡禁用并提示包名。
 */
@Composable
private fun ApilotSection(
    controller: ApilotController,
    state: ApilotController.UiState,
    pickLauncher: androidx.activity.result.ActivityResultLauncher<android.content.Intent>?,
    syncLauncher: androidx.activity.result.ActivityResultLauncher<android.content.Intent>?,
    gatewayLauncher: androidx.activity.result.ActivityResultLauncher<android.content.Intent>?,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    Card(Modifier.fillMaxWidth().testTag("apilot-section")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Apilot 双向流转", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(
                    if (state.installed) "已检测到 Apilot" else "未检测到 Apilot",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.installed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.testTag("apilot-installed"),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 一键网关（Apilot v2.5.0+ 推荐）：不用选配置，直接拿到 baseUrl+model
                OutlinedButton(
                    onClick = {
                        runCatching { gatewayLauncher?.launch(controller.gatewayIntent()) }
                            .onFailure { controller.setNotice("无法打开 Apilot（${it.message}），请确认已安装 v2.5.0+ 并启动网关") }
                    },
                    enabled = state.installed && !state.busy && gatewayLauncher != null,
                    modifier = Modifier.weight(1f).testTag("apilot-gateway"),
                ) { Text("⚡ 网关一键接入") }
                OutlinedButton(
                    onClick = {
                        // 真机修复：launch 全包 runCatching——ActivityNotFound / 包名解析失败
                        // 等场景不允许闪退（真机反馈"点从 Apilot 接入闪退"）
                        runCatching { pickLauncher?.launch(controller.pickIntent()) }
                            .onFailure { controller.setNotice("无法打开 Apilot（${it.message}），请确认已安装最新版") }
                    },
                    enabled = state.installed && !state.busy && pickLauncher != null,
                    modifier = Modifier.weight(1f).testTag("apilot-import"),
                ) { Text("← 方案授权接入") }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val plan = controller.buildSync()
                            if (plan == null) {
                                controller.setNotice("还没有可推送的服务商，先添加一个")
                            } else {
                                controller.markSyncLaunched(plan.hasKey, plan.providerCount)
                                runCatching { syncLauncher?.launch(plan.intent) }
                                    .onFailure { controller.setNotice("无法打开 Apilot（${it.message}），请确认已安装最新版") }
                            }
                        }
                    },
                    enabled = state.installed && !state.busy && syncLauncher != null,
                    modifier = Modifier.weight(1f).testTag("apilot-sync"),
                ) { Text("→ 同步到 Apilot") }
            }
            Text(
                "上次：接入 ${formatSyncTime(state.lastImportAt)} · 推送 ${formatSyncTime(state.lastExportAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.testTag("apilot-last-sync"),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                state.notice?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.weight(1f).testTag("apilot-notice"),
                    )
                } ?: androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                TextButton(onClick = controller::clearRecords, modifier = Modifier.testTag("apilot-clear")) {
                    Text("清除记录")
                }
            }
            if (!state.installed) {
                Text(
                    "安装 Apilot 后可用（包名 ${com.zhique.core.apilot.ApilotBridge().apilotPackage}）；" +
                        "授权页四档 scope 逐项确认，Key 不勾不回传。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
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
        // 反馈紧跟按钮行：notice 放在表单底部时用户点击「拉取/探测」后看不到任何变化
        form.notice?.let {
            Text(it, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.fillMaxWidth().testTag("provider-notice"))
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = controller::save, modifier = Modifier.testTag("provider-save")) { Text("保存") }
            OutlinedButton(onClick = controller::cancelForm) { Text("取消") }
        }
    }
}
