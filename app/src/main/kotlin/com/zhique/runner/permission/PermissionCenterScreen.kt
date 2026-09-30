package com.zhique.runner.permission

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhique.core.permission.Capability
import com.zhique.core.permission.PermissionRegistry
import com.zhique.core.permission.PState
import com.zhique.core.project.ProjectRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 四态 chip 的语义色与文案（靛蓝交互/绿健康/琥珀等待/红错误）。 */
internal fun chipColor(state: PState): Color = when (state) {
    PState.GRANTED -> Color(0xFF3E9B4F)
    PState.ASKING -> Color(0xFFB8860B)
    PState.DENIED -> Color(0xFFC0392B)
    PState.NOT_ASKED -> Color(0xFF8A8F98)
}

internal fun chipLabel(state: PState): String = when (state) {
    PState.GRANTED -> "授予"
    PState.ASKING -> "询问"
    PState.DENIED -> "拒绝"
    PState.NOT_ASKED -> "未申请"
}

private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

/**
 * 权限中心（规格 §5.3 屏 11 / §7）：项目×能力矩阵（四色态 chip）、点击改状态、
 * 运行中项目提醒条、全局区（密钥库状态入口，M6 前占位）+ 导出最小权限建议。
 * 矩阵写走 [PermissionRegistry.set]/[revoke]（project.json 原子小写，同步即时持久化）。
 */
@Composable
fun PermissionCenterScreen(
    repo: ProjectRepository,
    registry: PermissionRegistry,
    focusProjectId: String? = null,
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
    onOpenKeystore: () -> Unit = {},
) {
    var projects by remember { mutableStateOf<List<com.zhique.core.project.ProjectMeta>>(emptyList()) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    val matrix = remember { mutableStateMapOf<String, PState>() }
    val usage = remember { mutableStateMapOf<String, Int>() }
    var suggest by remember { mutableStateOf<List<String>>(emptyList()) }
    var menuFor by remember { mutableStateOf<String?>(null) }

    fun refresh(pid: String) {
        for ((cap, state) in registry.matrix(pid)) matrix[cap] = state
        for ((cap, count) in registry.usage(pid)) usage[cap] = count
        suggest = registry.suggestForExport(pid)
    }

    // 项目列表（IO）+ 初始选中（focus 优先）
    LaunchedEffect0 {
        val list = withContext(Dispatchers.IO) { runCatching { repo.list() }.getOrDefault(emptyList()) }
        projects = list
        val target = focusProjectId?.takeIf { f -> list.any { it.id == f } }
            ?: list.firstOrNull()?.id
        selectedId = target
        target?.let { refresh(it) }
    }

    val runningIds by RunningProjects.ids.collectAsState()

    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Surface(tonalElevation = 2.dp, color = MaterialTheme.colorScheme.surface) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("perm-center-back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                    Text("权限中心", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                }
            }

            // 运行中项目提醒条（琥珀）
            if (selectedId != null && selectedId in runningIds) {
                Text(
                    "项目运行中——状态更改对运行中的调用即时生效（吊销后流立即停止）",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF7A5A00),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFFFF3CD))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag("perm-running-bar"),
                )
            }

            LazyColumn(Modifier.fillMaxSize().testTag("perm-center-root")) {
                // 项目选择
                item {
                    Text(
                        "项目",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                    )
                    Row(
                        Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        projects.take(8).forEach { p ->
                            Box {
                                Text(
                                    p.name,
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (p.id == selectedId) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.surfaceVariant
                                            },
                                        )
                                        .clickable { selectedId = p.id; refresh(p.id) }
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                        .testTag("perm-project-${p.id}"),
                                )
                            }
                        }
                    }
                }

                // 矩阵：项目 × 能力
                items(Capability.ALL, key = { it.id }) { cap ->
                    val state = matrix[cap.id] ?: PState.NOT_ASKED
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(cap.title, style = MaterialTheme.typography.titleSmall)
                            Text(
                                cap.summary + usageLine(usage[cap.id]),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Box {
                            Text(
                                chipLabel(state),
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(chipColor(state).copy(alpha = 0.16f))
                                    .clickable { menuFor = cap.id }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                                    .testTag("cap-chip-${cap.id}"),
                            )
                            DropdownMenu(
                                expanded = menuFor == cap.id,
                                onDismissRequest = { if (menuFor == cap.id) menuFor = null },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("允许") },
                                    onClick = {
                                        menuFor = null
                                        selectedId?.let { pid ->
                                            registry.set(pid, cap.id, PState.GRANTED)
                                            refresh(pid)
                                        }
                                    },
                                    modifier = Modifier.testTag("cap-set-GRANTED-${cap.id}"),
                                )
                                DropdownMenuItem(
                                    text = { Text("拒绝") },
                                    onClick = {
                                        menuFor = null
                                        selectedId?.let { pid ->
                                            registry.set(pid, cap.id, PState.DENIED)
                                            refresh(pid)
                                        }
                                    },
                                    modifier = Modifier.testTag("cap-set-DENIED-${cap.id}"),
                                )
                                DropdownMenuItem(
                                    text = { Text("清除（未申请）") },
                                    onClick = {
                                        menuFor = null
                                        selectedId?.let { pid ->
                                            registry.revoke(pid, cap.id)
                                            refresh(pid)
                                        }
                                    },
                                    modifier = Modifier.testTag("cap-set-NOT_ASKED-${cap.id}"),
                                )
                            }
                        }
                    }
                }

                // 导出建议（运行期真实使用记录）
                item {
                    Text(
                        "导出建议（按真实使用记录生成最小权限清单）",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                    )
                    Text(
                        if (suggest.isEmpty()) "暂未使用任何能力" else suggest.joinToString("、") { id -> Capability.fromId(id)?.title ?: id },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp).testTag("export-suggest"),
                    )
                }

                // 全局区：密钥库状态入口（M6 前占位）
                item {
                    Text(
                        "全局",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpenKeystore() }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                            .testTag("keystore-entry"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("导出与签名", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "密钥库状态 · 备份 · 恢复（M6 接入）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.size(24.dp))
                }
            }
        }
    }
}

private fun usageLine(count: Int?): String =
    if (count != null && count > 0) " · 已使用 $count 次" else ""

/** 组合期单发副作用的小助手（等价 LaunchedEffect(Unit)，便于测试命名隔离）。 */
@Composable
private fun LaunchedEffect0(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) {
    androidx.compose.runtime.LaunchedEffect(key1 = Unit, block = block)
}
