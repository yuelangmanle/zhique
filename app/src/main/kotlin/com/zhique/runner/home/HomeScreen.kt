package com.zhique.runner.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhique.core.project.ProjectMeta
import com.zhique.core.project.ProjectRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

/** 把 #RRGGBB 字符串安全解析为颜色，失败回退语义靛蓝。 */
internal fun parseIconColor(hex: String): Color =
    runCatching {
        Color(android.graphics.Color.parseColor(hex))
    }.getOrDefault(Color(0xFF46509F))

/**
 * 首页项目列表（规格 §5.3 屏 2 的 M1 版）：名称/时间/▶ 运行，
 * 长按菜单：重命名/移动分组/复制/zip 导出/删除确认（决策 28）。
 * FAB 新建空项目；空态提供「运行示例：星空」。
 * M2 新增：剪贴板置顶卡（ON_RESUME 读一次 + 显式刷新，含代码特征才显示）。
 * 磁盘 IO 全部经 [HomeController] 的 IO 协程，UI 只消费 StateFlow（Important 6）。
 */
@Composable
fun HomeScreen(
    repo: ProjectRepository,
    onRun: (ProjectMeta) -> Unit,
    onToast: (String) -> Unit,
    modifier: Modifier = Modifier,
    clipboardText: (() -> String?)? = null,
    onPastePreview: (String) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenPermissions: (ProjectMeta) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember(repo) {
        HomeController(
            repo = repo,
            scope = scope,
            onToast = onToast,
            onRun = onRun,
            sampleHtml = {
                runCatching {
                    context.assets.open("samples/stars.html").bufferedReader().use { it.readText() }
                }.getOrNull()
            },
            clipboardText = clipboardText,
        )
    }
    val projects by controller.projects.collectAsState()
    val historyIds by controller.historyIds.collectAsState()
    val clipboardCandidate by controller.clipboardCandidate.collectAsState()

    // 回前台读一次剪贴板（规格 §2.1：不做后台监听）
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) controller.checkClipboard()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var menuFor by remember { mutableStateOf<ProjectMeta?>(null) }
    var renameFor by remember { mutableStateOf<ProjectMeta?>(null) }
    var regroupFor by remember { mutableStateOf<ProjectMeta?>(null) }
    var deleteFor by remember { mutableStateOf<ProjectMeta?>(null) }

    Scaffold(
        modifier = modifier.testTag("home-screen"),
        floatingActionButton = {
            FloatingActionButton(
                onClick = { controller.createEmpty() },
                modifier = Modifier.testTag("fab-new"),
            ) { Text("+", style = MaterialTheme.typography.headlineSmall) }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "织雀",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { controller.checkClipboard() },
                    modifier = Modifier.testTag("clipboard-refresh"),
                ) { Text("刷新剪贴板") }
                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.testTag("open-settings"),
                ) {
                    Icon(Icons.Filled.Settings, contentDescription = "设置")
                }
            }
            val candidate = clipboardCandidate
            if (candidate != null) {
                ClipboardCard(
                    text = candidate,
                    onPreview = {
                        controller.consumeClipboard()
                        onPastePreview(candidate)
                    },
                    onDismiss = { controller.dismissClipboard() },
                )
            }
            if (projects.isEmpty()) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("还没有项目", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "粘贴代码即建项目（M2），或先玩示例",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(20.dp))
                    Button(
                        onClick = { controller.createSample() },
                        modifier = Modifier.testTag("sample-button"),
                    ) { Text("运行示例：星空") }
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(projects, key = { it.id }) { project ->
                        ProjectCard(
                            project = project,
                            onRun = { onRun(project) },
                            onLongPress = { menuFor = project },
                            menuExpanded = menuFor?.id == project.id,
                            onDismissMenu = { if (menuFor?.id == project.id) menuFor = null },
                            onRename = { menuFor = null; renameFor = project },
                            onMoveGroup = { menuFor = null; regroupFor = project },
                            onCopy = { menuFor = null; controller.copy(project.id) },
                            onExportZip = { menuFor = null; controller.exportZip(project.id) },
                            onDelete = { menuFor = null; deleteFor = project },
                            onPermissions = { menuFor = null; onOpenPermissions(project) },
                        )
                    }
                }
            }
        }
    }

    // ---- 对话框 ----
    val renameTarget = renameFor
    if (renameTarget != null) {
        TextInputDialog(
            title = "重命名项目",
            initial = renameTarget.name,
            onDismiss = { renameFor = null },
            onConfirm = { name ->
                controller.rename(renameTarget.id, name)
                renameFor = null
            },
        )
    }
    val regroupTarget = regroupFor
    if (regroupTarget != null) {
        TextInputDialog(
            title = "移动到分组",
            initial = regroupTarget.group,
            hint = "留空表示取消分组",
            onDismiss = { regroupFor = null },
            onConfirm = { group ->
                controller.moveGroup(regroupTarget.id, group)
                regroupFor = null
            },
        )
    }
    val deleteTarget = deleteFor
    if (deleteTarget != null) {
        val hasHistory = deleteTarget.id in historyIds
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("删除「${deleteTarget.name}」？") },
            text = {
                Text(
                    if (hasHistory) "该项目含历史快照，删除后不可恢复。"
                    else "删除后不可恢复。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    controller.delete(deleteTarget.id)
                    deleteFor = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteFor = null }) { Text("取消") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectCard(
    project: ProjectMeta,
    onRun: () -> Unit,
    onLongPress: () -> Unit,
    menuExpanded: Boolean,
    onDismissMenu: () -> Unit,
    onRename: () -> Unit,
    onMoveGroup: () -> Unit,
    onCopy: () -> Unit,
    onExportZip: () -> Unit,
    onDelete: () -> Unit,
    onPermissions: () -> Unit = {},
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("project-card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = {}, onLongClick = onLongPress)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 图标容器：项目 iconColor（规格 §3.5）
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(parseIconColor(project.iconColor)),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    project.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        append(timeFormat.format(Date(project.updatedAt)))
                        if (project.group.isNotBlank()) append(" · ${project.group}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                TextButton(onClick = onRun, modifier = Modifier.testTag("run-${project.id}")) {
                    Text("▶", color = MaterialTheme.colorScheme.primary)
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = onDismissMenu) {
                    DropdownMenuItem(text = { Text("重命名") }, onClick = onRename)
                    DropdownMenuItem(text = { Text("移动分组") }, onClick = onMoveGroup)
                    DropdownMenuItem(
                        text = { Text("权限") },
                        onClick = onPermissions,
                        modifier = Modifier.testTag("menu-permission"),
                    )
                    DropdownMenuItem(text = { Text("复制项目") }, onClick = onCopy)
                    DropdownMenuItem(text = { Text("zip 导出") }, onClick = onExportZip)
                    DropdownMenuItem(text = { Text("删除") }, onClick = onDelete)
                }
            }
        }
    }
}

@Composable
private fun ClipboardCard(
    text: String,
    onPreview: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .testTag("clipboard-card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "检测到剪贴板中的代码",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text.lineSequence().firstOrNull { it.isNotBlank() }?.take(60) ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPreview, modifier = Modifier.testTag("clipboard-paste")) {
                    Text("粘贴预览")
                }
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("clipboard-dismiss")) {
                    Text("忽略")
                }
            }
        }
    }
}

@Composable
private fun TextInputDialog(
    title: String,
    initial: String,
    hint: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(if (hint.isBlank()) "名称" else hint) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
