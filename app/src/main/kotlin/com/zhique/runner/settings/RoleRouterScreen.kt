package com.zhique.runner.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.zhique.core.ai.AgentRole
import com.zhique.core.ai.ModelRef
import com.zhique.core.ai.Modality
import com.zhique.core.ai.Preset
import com.zhique.core.ai.RoleRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 角色路由屏：五槽 + 省钱/均衡/质量预设 + 每槽绑定（视觉槽纯文本硬拦，规格 §4.4）。
 * 项目覆盖入口在项目详情（M4 接线），本屏提供默认项目维度的绑定。
 */
@Composable
fun RoleRouterScreen(
    store: RoleBindingStore,
    defaultProviderId: String,
    scope: CoroutineScope,
    modifier: Modifier = Modifier,
) {
    val router = RoleRouter(defaultProviderId)
    val bindings by store.bindings.collectAsState(initial = RoleBindings())
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
                    role, router, preset, current?.model, scope, store,
                    // 无手动绑定时显示的是预设推荐模型而非实际绑定，措辞须区分
                    // （TV 走查：用户没配 gpt-4o 也显示「当前：gpt-4o」，误导）
                    bound = userRoles.containsKey(role) || projectOverrides.containsKey(role),
                )
            }
        }
    }
}

@Composable
private fun SlotRow(
    role: AgentRole,
    router: RoleRouter,
    preset: Preset,
    currentModel: String?,
    scope: CoroutineScope,
    store: RoleBindingStore,
    bound: Boolean,
) {
    var input by remember(currentModel) { mutableStateOf(currentModel ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    val modality = currentModel?.let { router.modalityOf(it) }?.name?.lowercase() ?: "未配置"

    Column(Modifier.fillMaxWidth().testTag("slot-${role.name}")) {
        Text("${role.label}（${role.hint}）", style = MaterialTheme.typography.titleSmall)
        Text(
            if (bound) "当前：${currentModel ?: "未配置"} · $modality"
            else "预设推荐：${currentModel ?: "未配置"} · $modality（未手动绑定，实际走默认服务商模型）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.testTag("slot-current-${role.name}"),
        )
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
                onClick = {
                    val model = input.trim()
                    if (model.isEmpty()) return@OutlinedButton
                    if (role == AgentRole.VISION && router.modalityOf(model) != Modality.VISION) {
                        error = "视觉槽必须选择 vision 模型：「$model」是纯文本模型"
                        return@OutlinedButton
                    }
                    scope.launch { store.bind(role.name, ModelRef(router.defaultProviderId, model)) }
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
}

internal const val PROJECT_SCOPE = "__default_project__"
