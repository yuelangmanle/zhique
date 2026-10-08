package com.zhique.runner.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import com.zhique.core.ai.AgentRole
import com.zhique.core.ai.AiError
import com.zhique.core.ai.ModelListFetcher
import com.zhique.core.ai.ModelRef
import com.zhique.core.ai.Modality
import com.zhique.core.ai.Preset
import com.zhique.core.ai.RoleRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 角色路由屏：五槽 + 省钱/均衡/质量预设 + 每槽绑定（视觉槽纯文本硬拦，规格 §4.4）。
 *
 * 快捷选模型（用户反馈：此前只能手抄 `deepseek-ai/DeepSeek-V4-Flash-0731` 这类长名）：
 * 每槽可先选服务商（已存 Provider chips），再「从模型列表选」——目录有缓存直接弹层，
 * 无缓存一键现场拉取（ProviderModelCatalog，拉一次全局可用）；手填仍保留。
 */
@Composable
fun RoleRouterScreen(
    store: RoleBindingStore,
    defaultProviderId: String,
    scope: CoroutineScope,
    modifier: Modifier = Modifier,
    providers: List<ProviderConfig> = emptyList(),
    catalog: ProviderModelCatalog? = null,
    fetcher: ModelListFetcher? = null,
    keyDecrypt: suspend (String) -> String = { "" },
) {
    val router = RoleRouter(defaultProviderId)
    val bindings by store.bindings.collectAsState(initial = RoleBindings())
    val catalogMap by catalog?.catalog?.collectAsState() ?: remember { mutableStateOf(emptyMap<String, List<String>>()) }
    val preset = runCatching { Preset.valueOf(bindings.preset) }.getOrDefault(Preset.BALANCED)
    val userRoles = bindings.roles.mapKeys { (k, _) ->
        runCatching { AgentRole.valueOf(k) }.getOrNull() ?: return@mapKeys null
    }.filterKeys { it != null }.mapKeys { it.key!! }
    val projectOverrides = (bindings.projectOverrides[PROJECT_SCOPE] ?: emptyMap()).mapKeys { (k, _) ->
        runCatching { AgentRole.valueOf(k) }.getOrNull()
    }.filterKeys { it != null }.mapKeys { it.key!! }

    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp)
                .testTag("roles-root"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("角色路由", style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Preset.entries.forEach { p ->
                    FilterChip(
                        selected = preset == p,
                        onClick = { scope.launch { store.setPreset(p.name) } },
                        label = { Text(p.label) },
                        modifier = Modifier.testTag("preset-${p.name}"),
                    )
                }
            }

            AgentRole.entries.forEach { role ->
                val current = runCatching {
                    router.resolve(role, userRoles, projectOverrides, preset)
                }.getOrNull()
                SlotRow(
                    role, router, preset, current?.model, current?.providerId ?: defaultProviderId,
                    scope, store,
                    bound = userRoles.containsKey(role) || projectOverrides.containsKey(role),
                    providers = providers,
                    catalogModels = { pid -> catalogMap[pid].orEmpty() },
                    onFetchModels = { pid, onDone ->
                        val provider = providers.firstOrNull { it.id == pid }
                        if (provider != null && catalog != null && fetcher != null) {
                            scope.launch {
                                runCatching {
                                    catalog.fetchInto(pid, provider.protocol, provider.baseUrl, keyDecrypt(pid), fetcher)
                                }.onSuccess { onDone(it) }
                                    .onFailure { onDone(emptyList()) }
                            }
                        }
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SlotRow(
    role: AgentRole,
    router: RoleRouter,
    preset: Preset,
    currentModel: String?,
    currentProviderId: String,
    scope: CoroutineScope,
    store: RoleBindingStore,
    bound: Boolean,
    providers: List<ProviderConfig>,
    catalogModels: (String) -> List<String>,
    onFetchModels: (String, (List<String>) -> Unit) -> Unit,
) {
    var input by remember(currentModel) { mutableStateOf(currentModel ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    // 槽内选择的服务商（默认全局默认服务商；绑定时随 ModelRef 落库）
    var pickedProvider by remember(currentProviderId) { mutableStateOf(currentProviderId) }
    var pickerOpen by remember { mutableStateOf(false) }
    var pickerBusy by remember { mutableStateOf(false) }
    val modality = currentModel?.let { router.modalityOf(it) }?.name?.lowercase() ?: "未配置"
    val providerName = providers.firstOrNull { it.id == pickedProvider }?.name ?: "未知服务商"

    Column(Modifier.fillMaxWidth().testTag("slot-${role.name}")) {
        Text("${role.label}（${role.hint}）", style = MaterialTheme.typography.titleSmall)
        Text(
            if (bound) "当前：${currentModel ?: "未配置"} · $modality"
            else "预设推荐：${currentModel ?: "未配置"} · $modality（未手动绑定，实际走默认服务商模型）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.testTag("slot-current-${role.name}"),
        )
        // 服务商选择（多服务商时显示；单服务商固定不必选）
        if (providers.size > 1) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth().testTag("slot-providers-${role.name}"),
            ) {
                providers.forEach { p ->
                    FilterChip(
                        selected = pickedProvider == p.id,
                        onClick = { pickedProvider = p.id },
                        label = { Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.widthIn(max = 200.dp).testTag("slot-prov-${role.name}-${p.name}"),
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = {
                    input = it
                    error = null
                },
                label = { Text("模型名") },
                modifier = Modifier.weight(1f).testTag("slot-input-${role.name}"),
            )
            OutlinedButton(
                onClick = { pickerOpen = true },
                enabled = providers.isNotEmpty(),
                modifier = Modifier.testTag("slot-pick-${role.name}"),
            ) { Text("从列表选") }
            OutlinedButton(
                onClick = {
                    val model = input.trim()
                    if (model.isEmpty()) return@OutlinedButton
                    if (role == AgentRole.VISION && router.modalityOf(model) != Modality.VISION) {
                        error = "视觉槽必须选择 vision 模型：「$model」是纯文本模型"
                        return@OutlinedButton
                    }
                    scope.launch { store.bind(role.name, ModelRef(pickedProvider, model)) }
                },
                modifier = Modifier.testTag("slot-save-${role.name}"),
            ) { Text("绑定") }
        }
        error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.testTag("slot-error-${role.name}"),
            )
        }
    }

    if (pickerOpen) {
        val cached = catalogModels(pickedProvider)
        AlertDialog(
            onDismissRequest = { pickerOpen = false },
            title = { Text("$providerName · 选择模型") },
            text = {
                Column {
                    if (pickerBusy) {
                        Text("正在拉取模型列表…", style = MaterialTheme.typography.bodySmall)
                    } else if (cached.isEmpty()) {
                        Text(
                            "还没有该服务商的模型列表（在「AI 服务商」拉取过会自动缓存）。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(
                            onClick = {
                                pickerBusy = true
                                onFetchModels(pickedProvider) { models ->
                                    pickerBusy = false
                                }
                            },
                            modifier = Modifier.testTag("slot-fetch-${role.name}"),
                        ) { Text("立即拉取") }
                    } else {
                        Text(
                            "共 ${cached.size} 个，点选即绑定",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        LazyColumn(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            items(cached) { m ->
                                Text(
                                    m,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (role == AgentRole.VISION && router.modalityOf(m) != Modality.VISION) {
                                                error = "视觉槽必须选择 vision 模型：「$m」是纯文本模型"
                                            } else {
                                                input = m
                                                scope.launch { store.bind(role.name, ModelRef(pickedProvider, m)) }
                                                pickerOpen = false
                                            }
                                        }
                                        .padding(vertical = 10.dp)
                                        .testTag("slot-model-${role.name}-$m"),
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { pickerOpen = false }) { Text("关闭") }
            },
            modifier = Modifier.testTag("slot-picker-${role.name}"),
        )
    }
}

internal const val PROJECT_SCOPE = "__default_project__"
