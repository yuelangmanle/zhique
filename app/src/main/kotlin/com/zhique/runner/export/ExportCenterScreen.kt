package com.zhique.runner.export

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.zhique.core.export.KeystoreManager
import com.zhique.core.project.ProjectMeta
import com.zhique.core.project.ProjectRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val centerTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

/**
 * 导出中心（规格 §5.3 屏 8，底部 Tab 落地）：密钥库备份状态置顶（决策29-3）、
 * 全部导出物（版本/包名/时间）、「推送更新」（M7 发布状态机接线：绑定了仓库的项目一键推送）。
 */
@Composable
fun ExportCenterScreen(
    repo: ProjectRepository,
    keystore: KeystoreManager,
    onExport: (ProjectMeta) -> Unit,
    onPush: (ProjectMeta) -> Unit,
    onToast: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
    exportedApk: (String) -> java.io.File? = { null },
) {
    var projects by remember { mutableStateOf<List<ProjectMeta>>(emptyList()) }
    val backupController = remember(ioDispatcher) {
        KeystoreBackupController(
            keystore = keystore,
            existingFingerprints = {
                runCatching { repo.list() }.getOrDefault(emptyList())
                    .mapNotNull { it.export?.certSha256 }.filter { it.isNotBlank() }
            },
            scope = kotlinx.coroutines.CoroutineScope(ioDispatcher),
            ioDispatcher = ioDispatcher,
            onToast = onToast,
        )
    }

    LaunchedEffect(Unit) {
        val loaded = withContext(ioDispatcher) {
            runCatching { repo.list() }.getOrDefault(emptyList())
        }
        projects = loaded
        backupController.refresh()
    }

    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(Modifier.fillMaxSize().testTag("export-center-root")) {
            // 密钥库备份状态置顶（决策29-3，与权限中心同款常驻组件）
            item {
                KeystoreBackupCard(
                    controller = backupController,
                    testPrefix = "center",
                    modifier = Modifier.padding(16.dp),
                )
            }

            // 导出物清单
            item {
                Text(
                    "全部导出物",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        .testTag("center-list-anchor"),
                )
            }
            val exported = projects.filter { it.export != null }
            if (exported.isEmpty()) {
                item {
                    Text(
                        "还没有导出过应用——在项目卡上点「导出」开始",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp).testTag("center-empty"),
                    )
                }
            }
            items(exported, key = { it.id }) { p ->
                val record = p.export!!
                val ctx = androidx.compose.ui.platform.LocalContext.current
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onExport(p) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${record.packageName} · v${record.versionCode}（${record.versionName}）· " +
                                centerTimeFormat.format(Date(record.at)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            val apk = exportedApk(p.id)
                            if (apk != null) {
                                // 直接重装上次产物（重度用户：卸载/换机后免走三步向导）
                                com.zhique.core.telemetry.DebugHub.event(
                                    "flow", "export.reinstall", detail = mapOf("pkg" to record.packageName),
                                )
                                ExportDelivery.installApk(ctx!!, apk)
                            } else onToast("上次产物已被系统清理，请重新导出")
                        },
                        modifier = Modifier.testTag("center-reinstall-${p.id}"),
                    ) { Text("重装") }
                    Button(
                        onClick = { onExport(p) },
                        modifier = Modifier.testTag("center-export-${p.id}"),
                    ) { Text("导出") }
                }
            }

            // 未导出过的项目也可发起导出
            val fresh = projects.filter { it.export == null }
            if (fresh.isNotEmpty()) {
                item {
                    Text(
                        "可导出的项目",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                items(fresh, key = { "fresh-${it.id}" }) { p ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onExport(p) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "尚未导出",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(
                            onClick = { onExport(p) },
                            modifier = Modifier.testTag("center-export-${p.id}"),
                        ) { Text("导出") }
                    }
                }
            }

            // 推送更新（M7：绑定仓库的项目一键发起状态机推送；未绑定的给引导入口）
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .testTag("center-push-card"),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("推送更新（GitHub）", style = MaterialTheme.typography.titleSmall)
                        val bound = projects.filter { it.repo != null }
                        if (bound.isEmpty()) {
                            Text(
                                "还没有绑定仓库——在项目发布向导首次发布后，这里可一键推送更新",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp).testTag("center-push-empty"),
                            )
                        }
                        bound.forEach { p ->
                            val binding = p.repo!!
                            Row(
                                Modifier.fillMaxWidth().padding(top = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("${binding.owner}/${binding.repo}", style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        binding.lastPushedSha?.let { "上次推送 ${it.take(7)}" } ?: "尚未推送",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Button(
                                    onClick = { onPush(p) },
                                    modifier = Modifier.testTag("center-push-${p.id}"),
                                ) { Text("推送更新") }
                            }
                        }
                    }
                }
            }
        }
    }
}
