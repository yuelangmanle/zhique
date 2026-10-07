package com.zhique.runner.home

import com.zhique.runner.ui.kit.LiquidGlass

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhique.core.project.ProjectMeta
import com.zhique.core.project.ProjectRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

/** 把 #RRGGBB 字符串安全解析为颜色，失败回退雀青强调色。 */
internal fun parseIconColor(hex: String): Color =
    runCatching {
        Color(android.graphics.Color.parseColor(hex))
    }.getOrDefault(Color(0xFF2F6D5F))

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
    onChat: (ProjectMeta) -> Unit = {},
    onEdit: (ProjectMeta) -> Unit = {},
    onToast: (String) -> Unit,
    modifier: Modifier = Modifier,
    clipboardText: (() -> String?)? = null,
    onPastePreview: (String) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenPermissions: (ProjectMeta) -> Unit = {},
    /** 剪贴板检测开关（M9 §7「智能粘贴」）：关=不自动检测、不显剪贴板卡与刷新按钮。 */
    clipboardDetection: Boolean = true,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loadSample: (String) -> String? = { asset ->
        runCatching {
            context.assets.open("samples/$asset").bufferedReader().use { it.readText() }
        }.getOrNull()
    }
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
            onShareZip = { f -> com.zhique.runner.export.ExportDelivery.shareZip(context, f) },
        )
    }
    val projects by controller.projects.collectAsState()
    val historyIds by controller.historyIds.collectAsState()
    val clipboardCandidate by controller.clipboardCandidate.collectAsState()

    // 回前台读一次剪贴板（规格 §2.1：不做后台监听）；检测开关关闭时跳过
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner, clipboardDetection) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && clipboardDetection) {
                controller.checkClipboard()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var menuFor by remember { mutableStateOf<ProjectMeta?>(null) }
    var renameFor by remember { mutableStateOf<ProjectMeta?>(null) }
    var regroupFor by remember { mutableStateOf<ProjectMeta?>(null) }
    var deleteFor by remember { mutableStateOf<ProjectMeta?>(null) }

    // 克制视觉：平色中性底（原 Aurora 渐变已移除，层级交给灰阶与细线）
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Scaffold(
        modifier = modifier.testTag("home-screen"),
        containerColor = Color.Transparent,
        floatingActionButton = {
            // 液态玻璃圆形新建钮（iOS 风格：材质浮层替代彩色 FAB）
            LiquidGlass(
                modifier = Modifier
                    .clickable { controller.createEmpty() }
                    .testTag("fab-new"),
                shape = androidx.compose.foundation.shape.CircleShape,
            ) {
                androidx.compose.foundation.layout.Box(
                    Modifier.size(56.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = "新建项目",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            val pasteNow = {
                val text = clipboardText?.invoke()
                if (text.isNullOrBlank()) onToast("剪贴板是空的——先去复制一段代码") else onPastePreview(text)
            }
            // 顶栏（视觉升级：品牌字标 + 紧凑图标动作；粘贴为主行动单独成钮）
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "织雀",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = pasteNow,
                    modifier = Modifier.testTag("paste-entry"),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Text("粘贴代码", style = MaterialTheme.typography.labelLarge)
                }
                Spacer(Modifier.width(4.dp))
                if (clipboardDetection) {
                    IconButton(
                        onClick = { controller.checkClipboard() },
                        modifier = Modifier.testTag("clipboard-refresh"),
                    ) { Icon(Icons.Filled.Refresh, contentDescription = "刷新剪贴板") }
                }
                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.testTag("open-settings"),
                ) {
                    Icon(Icons.Filled.Settings, contentDescription = "设置")
                }
            }
            val candidate = clipboardCandidate
            if (clipboardDetection && candidate != null) {
                ClipboardBanner(
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
                        .padding(horizontal = 32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .size(84.dp)
                            .clip(RoundedCornerShape(28.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)),
                        contentAlignment = Alignment.Center,
                    ) { Text("🪶", style = MaterialTheme.typography.displaySmall) }
                    Spacer(Modifier.size(20.dp))
                    Text("还没有项目", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "从任何 AI 复制 HTML 代码，回来点「粘贴代码」即可运行",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Spacer(Modifier.size(24.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = pasteNow,
                            modifier = Modifier.testTag("empty-paste"),
                            shape = RoundedCornerShape(22.dp),
                        ) { Text("粘贴代码") }
                    }
                    Spacer(Modifier.size(28.dp))
                    // 示例库（空态呈现全部预设示例，一键创建即玩）
                    SampleGrid(compact = false) { entry ->
                        controller.createFromSample(entry.name, loadSample(entry.asset))
                    }
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item(key = "sample-strip") {
                        SampleStrip { entry ->
                            controller.createFromSample(entry.name, loadSample(entry.asset))
                        }
                    }
                    items(projects, key = { it.id }) { project ->
                        ProjectCard(
                            project = project,
                            onRun = { onRun(project) },
                            onChat = { onChat(project) },
                            onEdit = { onEdit(project) },
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
            suggestions = projects.mapNotNull { it.group.takeIf { g -> g.isNotBlank() } }.distinct(),
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
/** 示例库·空态网格（两列卡片，渐变主色 + emoji + 描述）。 */
@Composable
private fun SampleGrid(compact: Boolean, onPick: (SampleEntry) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            "✨ 示例库",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        androidx.compose.foundation.layout.FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            maxItemsInEachRow = 2,
        ) {
            SampleCatalog.ALL.forEach { entry ->
                SampleTile(entry = entry, modifier = Modifier.weight(1f), onPick = onPick)
            }
        }
    }
}

/** 示例库·有项目时的横向滑动条。 */
@Composable
private fun SampleStrip(onPick: (SampleEntry) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
        Text(
            "✨ 示例库 · 一键创建",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        androidx.compose.foundation.lazy.LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(SampleCatalog.ALL) { entry ->
                SampleTile(
                    entry = entry,
                    modifier = Modifier.width(150.dp),
                    onPick = onPick,
                )
            }
        }
    }
}

/** 单个示例卡：渐变底 + emoji + 名称 + 描述。 */
@Composable
private fun SampleTile(entry: SampleEntry, modifier: Modifier, onPick: (SampleEntry) -> Unit) {
    androidx.compose.material3.Card(
        modifier = modifier
            .testTag("sample-${entry.asset}")
            .clickable { onPick(entry) },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            Modifier.padding(13.dp),
        ) {
            Text(entry.emoji, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.size(8.dp))
            Text(
                entry.name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.size(2.dp))
            Text(
                entry.desc,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ProjectCard(
    project: ProjectMeta,
    onRun: () -> Unit,
    onChat: () -> Unit = {},
    onEdit: () -> Unit = {},
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
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onRun, onLongClick = onLongPress)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 克制缩略图：强调色容器面 + 首字（原渐变已移除）
            Box(
                Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    project.name.take(1),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    project.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.size(3.dp))
                Text(
                    buildString {
                        append(relativeTime(project.updatedAt))
                        if (project.group.isNotBlank()) append(" · ${project.group}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // AI 对话入口（文字胶囊：与运行键形状区分，不误读）
            Box(
                Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .clickable(onClick = onChat)
                    .padding(horizontal = 13.dp, vertical = 8.dp)
                    .testTag("chat-${project.id}"),
            ) {
                Text(
                    "AI",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Spacer(Modifier.width(10.dp))
            // 主行动：实心圆形运行钮（视觉焦点）
            Box(
                Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(21.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable(onClick = onRun)
                    .testTag("run-${project.id}"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = "运行",
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
            Box {
                DropdownMenu(expanded = menuExpanded, onDismissRequest = onDismissMenu) {
                    DropdownMenuItem(text = { Text("编辑代码") }, onClick = onEdit)
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

/** 相对时间（今天=HH:mm，昨天=昨天，7 天内=N 天前，更早=MM-dd）。 */
internal fun relativeTime(at: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - at
    val dayMs = 24 * 60 * 60 * 1000L
    return when {
        diff < 60_000L -> "刚刚"
        diff < 60 * 60_000L -> "${diff / 60_000L} 分钟前"
        diff < dayMs && java.util.Calendar.getInstance().apply { timeInMillis = at }
            .get(java.util.Calendar.DAY_OF_YEAR) ==
            java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR) ->
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(at))
        diff < 2 * dayMs -> "昨天"
        diff < 7 * dayMs -> "${diff / dayMs} 天前"
        else -> SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(at))
    }
}

/** 剪贴板横幅（克制视觉：次级容器面 + 细线，发现即可点）。 */
@Composable
private fun ClipboardBanner(
    text: String,
    onPreview: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .testTag("clipboard-card"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            Modifier
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .clickable(onClick = onPreview)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("📋", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "剪贴板里有代码",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.size(2.dp))
                Text(
                    text.lineSequence().firstOrNull { it.isNotBlank() }?.take(48) ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            ) {
                Text(
                    "粘贴",
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("clipboard-dismiss"),
            ) {
                Icon(Icons.Filled.Close, contentDescription = "忽略", tint = Color.White.copy(alpha = 0.9f))
            }
        }
    }
}

@Composable
private fun TextInputDialog(
    title: String,
    initial: String,
    hint: String = "",
    // 现有值快捷选择（移动分组用：免手输、避免拼写不一致分裂成新分组）
    suggestions: List<String> = emptyList(),
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(if (hint.isBlank()) "名称" else hint) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (suggestions.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        suggestions.take(4).forEach { s ->
                            AssistChip(
                                onClick = { text = s },
                                label = { Text(s) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
