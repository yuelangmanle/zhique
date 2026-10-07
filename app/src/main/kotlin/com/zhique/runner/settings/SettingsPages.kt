package com.zhique.runner.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.zhique.core.ai.UsageMeter
import com.zhique.core.apilot.ApilotAuditStore
import com.zhique.runner.paste.PastePreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 通用设置页骨架：顶栏（标题左对齐 + 细线分隔）+ 可滚动内容（克制视觉基调）。 */
@Composable
internal fun SettingsPageScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("settings-page-back")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                )
            }
            androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                content()
            }
        }
    }
}

@Composable
internal fun SwitchRow(tag: String, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    // 整行可点切换（Material 列表项惯例）：测试对行 tag 的点击与真机点行都落到同一动作
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, onValueChange = onChange)
            .padding(vertical = 6.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
internal fun RadioRow(tag: String, title: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 4.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(title, style = MaterialTheme.typography.bodyLarge)
    }
}

// ================= 输出·思考·上下文 =================

/** 「输出·思考·上下文」设置页（规格 §7 AI 服务商子节点）。 */
@Composable
fun OutputContextScreen(
    prefs: AiPreferences,
    onBack: () -> Unit,
    onToast: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var maxTokens by remember { mutableStateOf(AiPreferences.DEFAULT_MAX_OUTPUT_TOKENS.toString()) }
    var segments by remember { mutableIntStateOf(AiPreferences.DEFAULT_CONTINUE_SEGMENTS) }
    var collapse by remember { mutableStateOf(true) }
    var contextLimit by remember { mutableStateOf("0") }
    var budget by remember { mutableIntStateOf(75) }
    var compact by remember { mutableIntStateOf(80) }

    LaunchedEffect(Unit) {
        val s = prefs.snapshot()
        maxTokens = s.maxOutputTokens.toString()
        segments = s.continueSegments
        collapse = s.thinkingCollapsed
        contextLimit = s.contextLimit.toString()
        budget = s.budgetPercent
        compact = s.compactThresholdPercent
    }

    SettingsPageScaffold("输出 · 思考 · 上下文", onBack) {
        Text("输出", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = maxTokens,
            onValueChange = { maxTokens = it.filter { c -> c.isDigit() } },
            label = { Text("单轮输出上限（tokens）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("oc-max-tokens"),
        )
        Spacer(Modifier.height(12.dp))
        Text("思考流", style = MaterialTheme.typography.titleMedium)
        SwitchRow(
            tag = "oc-thinking-collapse",
            title = "思考默认折叠",
            subtitle = "思考过程默认收起，展开可逐字回放",
            checked = collapse,
            onChange = {
                collapse = it
                scope.launch { prefs.setThinkingCollapsed(it) }
            },
        )
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text("防截断续写", style = MaterialTheme.typography.titleMedium)
        Text(
            "输出触顶时自动携带尾部前缀续写，最多 N 段；0 = 关闭改为手动「继续输出」。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("oc-segments-row")) {
            OutlinedButton(
                onClick = {
                    if (segments > 0) {
                        segments--
                        scope.launch { runCatching { prefs.setContinueSegments(segments) } }
                    }
                },
                modifier = Modifier.testTag("oc-segments-dec"),
            ) { Text("-") }
            Text(
                "自动续写段数：$segments",
                Modifier.padding(horizontal = 16.dp).testTag("oc-segments-value"),
            )
            OutlinedButton(
                onClick = {
                    if (segments < 10) {
                        segments++
                        scope.launch { runCatching { prefs.setContinueSegments(segments) } }
                    }
                },
                modifier = Modifier.testTag("oc-segments-inc"),
            ) { Text("+") }
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text("上下文", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = contextLimit,
            onValueChange = { contextLimit = it.filter { c -> c.isDigit() } },
            label = { Text("上下文上限（0 = 按模型窗口）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("oc-context-limit"),
        )
        Text(
            "工作预算占比：$budget%",
            Modifier.testTag("oc-budget-value"),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            "自动压缩阈值：$compact%",
            Modifier.testTag("oc-compact-value"),
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(8.dp))
        Row {
            OutlinedButton(
                onClick = {
                    if (budget > 10) {
                        budget -= 5
                        scope.launch { prefs.setBudgetPercent(budget) }
                    }
                },
                modifier = Modifier.testTag("oc-budget-dec"),
            ) { Text("-5%") }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(
                onClick = {
                    if (budget < 100) {
                        budget += 5
                        scope.launch { prefs.setBudgetPercent(budget) }
                    }
                },
                modifier = Modifier.testTag("oc-budget-inc"),
            ) { Text("+5%") }
            Spacer(Modifier.width(24.dp))
            OutlinedButton(
                onClick = {
                    if (compact > 50) {
                        compact -= 5
                        scope.launch { prefs.setCompactThresholdPercent(compact) }
                    }
                },
                modifier = Modifier.testTag("oc-compact-dec"),
            ) { Text("阈值-5%") }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(
                onClick = {
                    if (compact < 95) {
                        compact += 5
                        scope.launch { prefs.setCompactThresholdPercent(compact) }
                    }
                },
                modifier = Modifier.testTag("oc-compact-inc"),
            ) { Text("阈值+5%") }
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                scope.launch {
                    runCatching {
                        prefs.setMaxOutputTokens(maxTokens.toInt())
                        prefs.setContextLimit(contextLimit.toInt())
                    }
                        .onSuccess { onToast("已保存") }
                        .onFailure { onToast("保存失败：${it.message}") }
                }
            },
            modifier = Modifier.testTag("oc-save"),
        ) { Text("保存") }
    }
}

// ================= Token 用量统计 =================

/** Token 用量统计页（规格 §7「Token 用量统计」）：按服务商/按项目两口径。 */
@Composable
fun TokenStatsScreen(
    usageMeter: UsageMeter,
    onBack: () -> Unit,
    resolveProvider: suspend (String) -> String? = { null },
    resolveProject: suspend (String) -> String? = { null },
) {
    var byProvider by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var byProject by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var providerNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var projectNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    LaunchedEffect(Unit) {
        val all = runCatching { usageMeter.all() }.getOrDefault(emptyMap())
        byProvider = all.filterKeys { it.startsWith("provider:") }
            .mapKeys { it.key.removePrefix("provider:") }
        byProject = all.filterKeys { it.startsWith("project:") }
            .mapKeys { it.key.removePrefix("project:") }
        // 显示名解析（TV 走查：直接显示 UUID 用户不可读），解析失败回退原 id
        providerNames = byProvider.keys.associateWith { id ->
            runCatching { resolveProvider(id) }.getOrNull()?.takeIf { it.isNotBlank() } ?: id
        }
        projectNames = byProject.keys.associateWith { id ->
            runCatching { resolveProject(id) }.getOrNull()?.takeIf { it.isNotBlank() } ?: id
        }
    }

    SettingsPageScaffold("Token 用量统计", onBack) {
        Text("按服务商", style = MaterialTheme.typography.titleMedium)
        TokenTable(byProvider, names = providerNames, tagPrefix = "token-provider")
        Spacer(Modifier.height(16.dp))
        Text("按项目", style = MaterialTheme.typography.titleMedium)
        TokenTable(byProject, names = projectNames, tagPrefix = "token-project")
    }
}

@Composable
private fun TokenTable(
    data: Map<String, Long>,
    tagPrefix: String,
    names: Map<String, String> = emptyMap(),
) {
    if (data.isEmpty()) {
        Text(
            "暂无记录",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().testTag("$tagPrefix-table"),
    ) {
        Column(Modifier.padding(12.dp)) {
            data.forEach { (k, v) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp).testTag("$tagPrefix-$k")) {
                    Text(names[k] ?: k.ifBlank { "（默认）" }, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Text("$v tokens", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

// ================= 通用 =================

/** 「通用」设置页（规格 §7）：外观/运行器/智能粘贴/编辑器/Web/通知 六组。 */
@Composable
fun GeneralScreen(
    general: GeneralPreferences,
    paste: PastePreferences,
    web: WebPreferences,
    onBack: () -> Unit,
    onToast: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var layout by remember { mutableStateOf(GeneralPreferences.LAYOUT_DRAWER) }
    var bubbleRemember by remember { mutableStateOf(true) }
    var immersive by remember { mutableStateOf(false) }
    var autoRun by remember { mutableStateOf(false) }
    var clipboardDetect by remember { mutableStateOf(true) }
    var cleanStrictConservative by remember { mutableStateOf(false) }
    var fontMono by remember { mutableStateOf(GeneralPreferences.FONT_MONO) }
    var fontSize by remember { mutableIntStateOf(14) }
    var autoIndent by remember { mutableStateOf(true) }
    var desktopUA by remember { mutableStateOf(false) }
    var downloadAsk by remember { mutableStateOf(true) }
    var eruda by remember { mutableStateOf(false) }
    var notifyExport by remember { mutableStateOf(true) }
    var notifyAgent by remember { mutableStateOf(true) }
    var notifyVersion by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        val g = general.snapshot()
        layout = g.runnerDefaultLayout
        bubbleRemember = g.bubbleRememberPosition
        immersive = g.immersiveMode
        fontSize = g.editorFontSize
        autoIndent = g.editorAutoIndent
        notifyExport = g.notifyExportDone
        notifyAgent = g.notifyAgentDone
        notifyVersion = g.notifyNewVersion
        autoRun = paste.autoRun.first()
        clipboardDetect = paste.clipboardDetection.first()
        cleanStrictConservative = paste.cleanStrictness.first() == PastePreferences.CLEAN_CONSERVATIVE
        fontMono = g.editorFontFamily
        val w = web.snapshot()
        desktopUA = w.desktopUA
        downloadAsk = w.downloadBehavior == WebPreferences.DOWNLOAD_ASK
        eruda = w.erudaEnabled
    }

    SettingsPageScaffold("通用", onBack) {
        Text("外观", style = MaterialTheme.typography.titleMedium)
        Text(
            "主题跟随系统（晨光浅底 / 深空暗底，随系统深色模式切换）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("general-theme"),
        )
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text("运行器", style = MaterialTheme.typography.titleMedium)
        Text("默认布局", style = MaterialTheme.typography.bodyLarge)
        RadioRow("general-layout-drawer", "底部抽屉", layout == GeneralPreferences.LAYOUT_DRAWER) {
            layout = GeneralPreferences.LAYOUT_DRAWER
            scope.launch { general.setRunnerDefaultLayout(layout) }
        }
        RadioRow("general-layout-split", "上下分屏", layout == GeneralPreferences.LAYOUT_SPLIT) {
            layout = GeneralPreferences.LAYOUT_SPLIT
            scope.launch { general.setRunnerDefaultLayout(layout) }
        }
        RadioRow("general-layout-bubble", "悬浮球浮层", layout == GeneralPreferences.LAYOUT_BUBBLE) {
            layout = GeneralPreferences.LAYOUT_BUBBLE
            scope.launch { general.setRunnerDefaultLayout(layout) }
        }
        SwitchRow(
            tag = "general-bubble-remember",
            title = "悬浮球位置记忆",
            subtitle = "退出运行器时记住悬浮球位置",
            checked = bubbleRemember,
            onChange = {
                bubbleRemember = it
                scope.launch { general.setBubbleRemember(it) }
            },
        )
        SwitchRow(
            tag = "general-immersive",
            title = "沉浸模式",
            subtitle = "运行器隐藏系统栏",
            checked = immersive,
            onChange = {
                immersive = it
                scope.launch { general.setImmersiveMode(it) }
            },
        )
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text("智能粘贴", style = MaterialTheme.typography.titleMedium)
        SwitchRow(
            tag = "general-paste-autorun",
            title = "粘贴后自动运行",
            subtitle = "关闭则停在预览页（高置信时才自动运行）",
            checked = autoRun,
            onChange = {
                autoRun = it
                scope.launch { paste.setAutoRun(it) }
            },
        )
        SwitchRow(
            tag = "general-paste-detect",
            title = "剪贴板检测",
            subtitle = "回首页自动识别剪贴板中的代码（关闭不显剪贴板卡）",
            checked = clipboardDetect,
            onChange = {
                clipboardDetect = it
                scope.launch { paste.setClipboardDetection(it) }
            },
        )
        Text("清洗严格度", style = MaterialTheme.typography.bodyLarge)
        Text(
            "标准：剥围栏+剥行号污染+剔说明文字；保守：只剥结构围栏，内容原样保留。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RadioRow("general-paste-strict-standard", "标准", !cleanStrictConservative) {
            cleanStrictConservative = false
            scope.launch { paste.setCleanStrictness(PastePreferences.CLEAN_STANDARD) }
        }
        RadioRow("general-paste-strict-conservative", "保守", cleanStrictConservative) {
            cleanStrictConservative = true
            scope.launch { paste.setCleanStrictness(PastePreferences.CLEAN_CONSERVATIVE) }
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text("编辑器", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("general-font-row")) {
            Text("代码字号：$fontSize", Modifier.weight(1f))
            OutlinedButton(onClick = {
                if (fontSize > 10) {
                    fontSize--
                    scope.launch { runCatching { general.setEditorFontSize(fontSize) } }
                }
            }, modifier = Modifier.testTag("general-font-dec")) { Text("-") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                if (fontSize < 28) {
                    fontSize++
                    scope.launch { runCatching { general.setEditorFontSize(fontSize) } }
                }
            }, modifier = Modifier.testTag("general-font-inc")) { Text("+") }
        }
        SwitchRow(
            tag = "general-auto-indent",
            title = "自动缩进",
            subtitle = "换行时延续上一行缩进",
            checked = autoIndent,
            onChange = {
                autoIndent = it
                scope.launch { general.setEditorAutoIndent(it) }
            },
        )
        Text("代码字体", style = MaterialTheme.typography.bodyLarge)
        Text(
            "平台等宽三档（重开编辑器生效）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RadioRow("general-font-family-monospace", "等宽（默认）", fontMono == GeneralPreferences.FONT_MONO) {
            fontMono = GeneralPreferences.FONT_MONO
            scope.launch { general.setEditorFontFamily(fontMono) }
        }
        RadioRow("general-font-family-sans", "无衬线等宽", fontMono == GeneralPreferences.FONT_SANS_MONO) {
            fontMono = GeneralPreferences.FONT_SANS_MONO
            scope.launch { general.setEditorFontFamily(fontMono) }
        }
        RadioRow("general-font-family-serif", "衬线等宽", fontMono == GeneralPreferences.FONT_SERIF_MONO) {
            fontMono = GeneralPreferences.FONT_SERIF_MONO
            scope.launch { general.setEditorFontFamily(fontMono) }
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text("Web", style = MaterialTheme.typography.titleMedium)
        Text(
            "WebGPU 状态：进入运行器时自动检测，缺失自动降级 WebGL",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("general-webgpu"),
        )
        SwitchRow(
            tag = "general-desktop-ua",
            title = "桌面模式 UA",
            subtitle = "使用桌面浏览器 User-Agent（重开运行器生效）",
            checked = desktopUA,
            onChange = {
                desktopUA = it
                scope.launch { web.setDesktopUA(it) }
            },
        )
        SwitchRow(
            tag = "general-download-ask",
            title = "下载前询问",
            subtitle = "关闭则直接下载到「下载」目录",
            checked = downloadAsk,
            onChange = {
                downloadAsk = it
                scope.launch { web.setDownloadBehavior(if (it) WebPreferences.DOWNLOAD_ASK else WebPreferences.DOWNLOAD_DIRECT) }
            },
        )
        SwitchRow(
            tag = "general-eruda",
            title = "eruda 高级调试面板",
            subtitle = "页尾注入 eruda（Elements/Network/Console），重开运行器生效",
            checked = eruda,
            onChange = {
                eruda = it
                scope.launch { web.setErudaEnabled(it) }
            },
        )
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text("通知", style = MaterialTheme.typography.titleMedium)
        SwitchRow(
            tag = "general-notify-export",
            title = "导出完成",
            subtitle = "APK 打包签名完成后提醒",
            checked = notifyExport,
            onChange = {
                notifyExport = it
                scope.launch { general.setNotifyExportDone(it) }
            },
        )
        SwitchRow(
            tag = "general-notify-agent",
            title = "Agent 完成",
            subtitle = "Agent 任务完成提醒",
            checked = notifyAgent,
            onChange = {
                notifyAgent = it
                scope.launch { general.setNotifyAgentDone(it) }
            },
        )
        SwitchRow(
            tag = "general-notify-version",
            title = "新版本",
            subtitle = "自更新通道发现新版本提醒",
            checked = notifyVersion,
            onChange = {
                notifyVersion = it
                scope.launch { general.setNotifyNewVersion(it) }
            },
        )
    }
}

// ================= 隐私与安全 =================

/**
 * 「隐私与安全」设置页（规格 §7）：应用锁（PIN 设置/生物识别开关/关闭锁）、
 * 审计日志（Apilot 桥接记录查看与清理）、隐私告知记录。
 */
@Composable
fun PrivacyScreen(
    privacy: PrivacyPreferences,
    lock: AppLockController,
    audit: ApilotAuditStore,
    onBack: () -> Unit,
    onToast: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var lockEnabled by remember { mutableStateOf(false) }
    var biometric by remember { mutableStateOf(false) }
    var pinInput by remember { mutableStateOf("") }
    var pinSetupMode by remember { mutableStateOf(false) }
    var auditRecords by remember { mutableStateOf(0) }
    var notices by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var showNotices by remember { mutableStateOf(false) }
    var showAudit by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            val s = privacy.lockSnapshot()
            lockEnabled = s.enabled
            biometric = s.biometric
            auditRecords = audit.list().size
            notices = privacy.notices()
        }
    }
    LaunchedEffect(Unit) { refresh() }

    SettingsPageScaffold("隐私与安全", onBack) {
        Text("应用锁", style = MaterialTheme.typography.titleMedium)
        if (!lockEnabled) {
            if (pinSetupMode) {
                OutlinedTextField(
                    value = pinInput,
                    onValueChange = { pinInput = it.filter { c -> c.isDigit() }.take(8) },
                    label = { Text("输入 4–8 位数字 PIN") },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().testTag("privacy-pin-input"),
                )
                // 二次确认：PIN 错一次就锁死应用，必须重输一遍防手滑
                var pinConfirm by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = pinConfirm,
                    onValueChange = { pinConfirm = it.filter { c -> c.isDigit() }.take(8) },
                    label = { Text("再输一次确认") },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().testTag("privacy-pin-confirm"),
                )
                Row {
                    Button(
                        onClick = {
                            scope.launch {
                                runCatching { privacy.setPin(pinInput) }
                                    .onSuccess {
                                        pinInput = ""
                                        pinSetupMode = false
                                        onToast("应用锁已开启")
                                        refresh()
                                    }
                                    .onFailure { onToast("设置失败：${it.message}") }
                            }
                        },
                        enabled = pinInput.length in 4..8 && pinInput == pinConfirm,
                        modifier = Modifier.testTag("privacy-pin-save"),
                    ) { Text("保存") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { pinSetupMode = false }, modifier = Modifier.testTag("privacy-pin-cancel")) {
                        Text("取消")
                    }
                }
            } else {
                Button(onClick = { pinSetupMode = true }, modifier = Modifier.testTag("privacy-lock-setup")) {
                    Text("设置 PIN 开启应用锁")
                }
            }
        } else {
            Text(
                "应用锁已开启：进入织雀需验证 PIN" + if (biometric) "或生物识别" else "",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("privacy-lock-status"),
            )
            OutlinedTextField(
                value = pinInput,
                onValueChange = { pinInput = it.filter { c -> c.isDigit() }.take(8) },
                label = { Text("输入旧 PIN 以更换") },
                singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().testTag("privacy-pin-change"),
            )
            Button(
                onClick = {
                    scope.launch {
                        val ok = privacy.verifyPin(pinInput)
                        if (ok) {
                            privacy.setPin(pinInput) // 同 PIN 换盐重哈希
                            pinInput = ""
                            onToast("PIN 已更换")
                        } else {
                            onToast("旧 PIN 不正确")
                        }
                    }
                },
                enabled = pinInput.length in 4..8,
                modifier = Modifier.testTag("privacy-pin-change-save"),
            ) { Text("更换 PIN") }
            SwitchRow(
                tag = "privacy-biometric",
                title = "生物识别解锁",
                subtitle = "锁屏时优先走指纹/面容（失败回落 PIN）",
                checked = biometric,
                onChange = {
                    biometric = it
                    scope.launch { privacy.setBiometric(it) }
                },
            )
            TextButton(
                onClick = {
                    scope.launch {
                        privacy.disableLock()
                        onToast("应用锁已关闭")
                        refresh()
                    }
                },
                modifier = Modifier.testTag("privacy-lock-disable"),
            ) { Text("关闭应用锁") }
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text("审计日志", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("privacy-audit-row")) {
            Text(
                "Apilot 桥接记录：$auditRecords 条（不含 Key 与 payload）",
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = { showAudit = !showAudit }, modifier = Modifier.testTag("privacy-audit-view")) {
                Text(if (showAudit) "收起" else "查看")
            }
        }
        if (showAudit) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().testTag("privacy-audit-list"),
            ) {
                Column(Modifier.padding(12.dp)) {
                    val records = audit.list()
                    if (records.isEmpty()) {
                        Text("暂无记录", style = MaterialTheme.typography.bodySmall)
                    }
                    records.takeLast(20).forEach { r ->
                        Text(
                            java.time.Instant.ofEpochMilli(r.time).toString() + " " + r.direction +
                                " " + r.summary + if (r.hasKey) " (带Key)" else "",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            TextButton(
                onClick = {
                    audit.clear()
                    refresh()
                    onToast("审计日志已清理")
                },
                modifier = Modifier.testTag("privacy-audit-clear"),
            ) { Text("清理审计日志") }
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text("隐私告知记录", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("privacy-notice-row")) {
            Text(
                "已确认 ${notices.size} 次「代码将发送至你配置的服务商」告知",
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = { showNotices = !showNotices }, modifier = Modifier.testTag("privacy-notice-view")) {
                Text(if (showNotices) "收起" else "查看")
            }
        }
        if (showNotices) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().testTag("privacy-notice-list"),
            ) {
                Column(Modifier.padding(12.dp)) {
                    if (notices.isEmpty()) Text("暂无记录", style = MaterialTheme.typography.bodySmall)
                    notices.forEach { (t, scene) -> Text("$t · $scene", style = MaterialTheme.typography.bodySmall) }
                }
            }
            Spacer(Modifier.height(4.dp))
            TextButton(
                onClick = {
                    scope.launch {
                        privacy.clearNotices()
                        refresh()
                        onToast("告知记录已清理")
                    }
                },
                modifier = Modifier.testTag("privacy-notice-clear"),
            ) { Text("清理告知记录") }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ================= 开发者（预留空壳） =================

/** 「开发者」设置页：调试开关 / 后端调试 / 日志导出 / 协议文档 / 意图测试器占位。 */
@Composable
fun DeveloperScreen(
    web: WebPreferences,
    onBack: () -> Unit,
    onToast: (String) -> Unit = {},
    debug: (@Composable () -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var eruda by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { eruda = web.erudaEnabled.first() }

    var docContent by remember { mutableStateOf<String?>(null) }
    var docTitle by remember { mutableStateOf("") }

    SettingsPageScaffold("开发者", onBack) {
        Text(
            "预留空壳（v0.1）：正式调试台随 M10 开放。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        SwitchRow(
            tag = "dev-debug-eruda",
            title = "eruda 面板（快捷开关）",
            subtitle = "与「通用 → Web」同一开关",
            checked = eruda,
            onChange = {
                eruda = it
                scope.launch { web.setErudaEnabled(it) }
            },
        )
        SwitchRow(
            tag = "dev-webview-debug",
            title = "WebView 远程调试（仅 debug 构建生效）",
            subtitle = "release 构建强制关闭，跟随应用可调试标志",
            checked = true,
            onChange = { onToast("跟随构建类型，不可手动切换") },
        )
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text("日志导出", style = MaterialTheme.typography.titleMedium)
        Button(
            onClick = {
                runCatching {
                    val dir = java.io.File(context.cacheDir, "dev-logs").apply { mkdirs() }
                    val f = java.io.File(dir, "zhique-dev-${System.currentTimeMillis()}.txt")
                    val pm = context.packageManager
                    val info = pm.getPackageInfo(context.packageName, 0)
                    f.writeText(
                        buildString {
                            appendLine("织雀开发者日志")
                            appendLine("version: ${info.versionName} (${info.longVersionCode})")
                            appendLine("android: ${android.os.Build.VERSION.RELEASE} (sdk ${android.os.Build.VERSION.SDK_INT})")
                            appendLine("device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                            appendLine("eruda: $eruda")
                            appendLine("generated: ${java.time.Instant.now()}")
                        },
                    )
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.zqfile",
                        f,
                    )
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(
                        android.content.Intent.createChooser(send, "导出日志"),
                        null,
                    )
                }.onFailure { onToast("导出失败：${it.message}") }
            },
            modifier = Modifier.testTag("dev-log-export"),
        ) { Text("导出诊断日志") }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        // ---- 后端调试（DebugHub + 本机 HTTP）：开关/端口/事件流入口 ----
        debug?.invoke()
        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        Text("zq.* 协议文档", style = MaterialTheme.typography.titleMedium)
        val docs = remember { ZqDocs.list() }
        docs.forEach { name ->
            Text(
                "zq.$name",
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        runCatching { docTitle = "zq.$name"; docContent = ZqDocs.read(name) }
                            .onFailure { onToast("读取失败：${it.message}") }
                    }
                    .padding(vertical = 8.dp)
                    .testTag("dev-doc-$name"),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text("意图测试器", style = MaterialTheme.typography.titleMedium)
        Text(
            "占位：用于演练 zq.* 意图与权限矩阵交互（随 M10 开放）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("dev-intent-placeholder"),
        )
    }

    docContent?.let { content ->
        AlertDialog(
            onDismissRequest = { docContent = null },
            title = { Text(docTitle) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(content, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = { docContent = null }) { Text("关闭") }
            },
            modifier = Modifier.testTag("dev-doc-dialog"),
        )
    }
}

/** zq.* 协议文档访问口（core:paste resources 的 zq-docs 目录，与提示词桥同一份）。 */
object ZqDocs {
    private val known = listOf(
        "camera", "clipboard", "file", "notification", "screen",
        "sensor", "share", "bluetooth", "location", "mic",
    )

    fun list(): List<String> = known.filter { read(it) != null }

    fun read(name: String): String? = runCatching {
        ZqDocs::class.java.classLoader?.getResourceAsStream("zq-docs/$name.md")
            ?.bufferedReader()?.use { it.readText() }
    }.getOrNull()
}
