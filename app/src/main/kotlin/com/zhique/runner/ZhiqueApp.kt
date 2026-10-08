package com.zhique.runner

import com.zhique.runner.ui.kit.LiquidGlass
import com.zhique.runner.ui.kit.ZqAmbient
import com.zhique.runner.ui.kit.zqTapSensor

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import com.zhique.core.ai.AgentRole
import com.zhique.core.ai.ModelListFetcher
import com.zhique.core.project.ProjectMeta
import com.zhique.runner.agent.AgentBridge
import com.zhique.runner.agent.AgentController
import com.zhique.runner.agent.AgentScreen
import com.zhique.runner.ai.AiWiring
import com.zhique.runner.editor.EditorAskContext
import com.zhique.runner.editor.EditorController
import com.zhique.runner.editor.EditorScreen
import com.zhique.runner.chat.ChatController
import com.zhique.runner.chat.ChatScreen
import com.zhique.runner.home.HomeController
import com.zhique.runner.home.HomeScreen
import com.zhique.runner.onboarding.OnboardingController
import com.zhique.runner.onboarding.OnboardingScreen
import com.zhique.runner.paste.PastePreviewController
import com.zhique.runner.paste.ProviderAiFallback
import com.zhique.runner.paste.PastePreviewScreen
import com.zhique.runner.runner.RunnerScreen
import com.zhique.runner.settings.ProvidersController
import com.zhique.runner.settings.ProvidersScreen
import com.zhique.runner.settings.RoleRouterScreen
import com.zhique.runner.settings.SettingsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 导航（M4 接线）：底部三 Tab「项目 / 导出中心(M6 占位) / 设置」；
 * 项目 Tab 串 首页 ↔ 运行器 ↔ 智能粘贴预览 ↔ Agent 会话（交给 Agent）；
 * 设置 Tab 串 对话 / AI 服务商 / 角色路由（M3 三屏接进导航）。
 * 粘贴入口三合一：首页剪贴板卡、系统分享目标、预览屏手动重跑。
 */
@Composable
fun ZhiqueApp(
    container: AppContainer,
    sharedText: MutableState<String?>? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingProjectId by rememberSaveable { mutableStateOf<String?>(null) }
    var runnerProject by remember { mutableStateOf<ProjectMeta?>(null) }
    var pasteDraft by remember { mutableStateOf<String?>(null) }
    var agentProject by remember { mutableStateOf<ProjectMeta?>(null) }
    val agentBridge = remember { AgentBridge() }
    var editorProject by remember { mutableStateOf<ProjectMeta?>(null) }
    var chatProject by remember { mutableStateOf<ProjectMeta?>(null) }
    // 子页回退链（PM 审计修复：运行器→编辑器/Agent/对话 后返回此前一律掉回首页，
    // 重度用户来回切项目无从谈起）。进入子页时记下来源，返回时逐级还原。
    var runnerReturn by remember { mutableStateOf<ProjectMeta?>(null) }
    var editorReturn by remember { mutableStateOf<ProjectMeta?>(null) }
    val backFromSub: () -> Unit = {
        when {
            editorReturn != null -> {
                editorProject = editorReturn
                editorReturn = null
            }
            runnerReturn != null -> {
                runnerProject = runnerReturn
                runnerReturn = null
            }
        }
    }
    var chatAsk by remember { mutableStateOf<EditorAskContext?>(null) }
    var wizardProject by remember { mutableStateOf<ProjectMeta?>(null) }
    var publishProject by remember { mutableStateOf<ProjectMeta?>(null) }
    // Agent 会话运行真值（编辑器只读横幅的依据；不靠全屏互斥兜底）
    var agentRunning by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf(TAB_PROJECTS) }
    var settingsPage by rememberSaveable { mutableStateOf<String?>(null) }
    var permFocus by rememberSaveable { mutableStateOf<String?>(null) }
    // 织雀提示词桥预填（CompatHint 反向兜底入口带入粘贴原句）
    var promptSeed by remember { mutableStateOf<String?>(null) }

    // M9：应用锁状态（前台恢复即锁；验证通过放行）
    val appLock = container.appLockController
    val lockState by appLock.state.collectAsState()
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    // M9：eruda 开关（响应式跟随设置——此前只在启动读一次，改开关后要重启 app 才生效）
    val erudaEnabled by container.webPreferences.erudaEnabled.collectAsState(initial = false)

    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> appLock.onForeground()
                // 质量审查 Important-4：ON_PAUSE 即重锁（选 ON_PAUSE 而非 FLAG_SECURE——
                // 分屏/画中画下 ON_STOP 不触发但内容并排可见；FLAG_SECURE 会连带禁用户自己的截屏分享）
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> appLock.onBackground()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 首启引导：仅在未完成时显示（X4）
    var onboardingNeeded by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        onboardingNeeded = !container.onboardingPrefs.isDone()
    }

    val toast: (String) -> Unit = { msg ->
        com.zhique.core.telemetry.DebugHub.event(
            "feedback", "toast", detail = mapOf("text" to msg),
        )
        scope.launch { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
    }

    // 系统分享 → 粘贴预览（消费即清空，防旋转重复进入）
    LaunchedEffect(sharedText?.value) {
        val text = sharedText?.value
        if (!text.isNullOrBlank()) {
            pasteDraft = text
            sharedText.value = null
            runnerProject = null
            agentProject = null
        }
    }

    // 选中项目 id → meta：磁盘读在 IO 线程
    LaunchedEffect(pendingProjectId) {
        val id = pendingProjectId ?: return@LaunchedEffect
        runnerProject = withContext(Dispatchers.IO) {
            runCatching { container.repo.meta(id) }.getOrNull()
        }
        if (runnerProject == null) {
            pendingProjectId = null
            toast("项目不存在或已损坏")
        }
    }

    val agentMeta0 = agentProject
    val editorMeta0 = editorProject
    val wizardMeta0 = wizardProject
    val publishMeta0 = publishProject
    val chatMeta0 = chatProject
    val fullScreen = onboardingNeeded == true || runnerProject != null ||
        agentMeta0 != null || editorMeta0 != null || chatMeta0 != null || pasteDraft != null ||
        wizardMeta0 != null || publishMeta0 != null

    // 调试：导航状态 → 屏幕名（单点覆盖全部屏幕/流程切换；DebugHub 去重）
    val currentScreenName = when {
        onboardingNeeded == true -> "onboarding"
        onboardingNeeded == null -> "loading"
        runnerProject != null -> "runner"
        agentMeta0 != null -> "agent"
        editorMeta0 != null -> "editor"
        chatMeta0 != null -> "chat"
        pasteDraft != null -> "paste"
        wizardMeta0 != null -> "export-wizard"
        publishMeta0 != null -> "publish-wizard"
        settingsPage != null -> "settings:$settingsPage"
        tab == TAB_SETTINGS -> "settings"
        else -> "home"
    }
    LaunchedEffect(currentScreenName) {
        com.zhique.core.telemetry.DebugHub.screen(currentScreenName)
    }

    // 系统返回键按「当前最深层界面」逐级回退（质量修复：此前无 BackHandler，
    // 二级页按返回直接 finish Activity 退出应用）。回退语义与 when 分支优先级一致。
    // 引导页吞掉返回（防误触退出）；首页根允许系统默认行为（退出应用）。
    androidx.activity.compose.BackHandler(enabled = onboardingNeeded == true) { /* 留在引导 */ }
    androidx.activity.compose.BackHandler(enabled = onboardingNeeded == false) {
        when {
            runnerProject != null -> { runnerProject = null; pendingProjectId = null; runnerReturn = null }
            agentMeta0 != null -> { agentProject = null; backFromSub() }
            editorMeta0 != null -> { editorProject = null; chatAsk = null; backFromSub() }
            chatProject != null -> { chatProject = null; chatAsk = null; backFromSub() }
            pasteDraft != null -> pasteDraft = null
            wizardMeta0 != null -> wizardProject = null
            publishMeta0 != null -> publishProject = null
            chatAsk != null -> chatAsk = null
            settingsPage != null -> { settingsPage = null; permFocus = null }
            permFocus != null -> permFocus = null
            promptSeed != null -> promptSeed = null
            tab != TAB_PROJECTS -> tab = TAB_PROJECTS
        }
    }

    // 全局 insets（真机循环修复：targetSdk 36 强制 edge-to-edge，内容默认顶到
    // 状态栏下。根节点统一 systemBarsPadding，各屏不再自行处理；运行器顶栏
    // 的单独 statusBarsPadding 已移除避免双重留白）
    Surface(
        Modifier
            .fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        ZqAmbient(Modifier.fillMaxSize().systemBarsPadding()) {
        Box(Modifier.fillMaxSize().zqTapSensor()) {
            Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                val meta = runnerProject
                when {
                    onboardingNeeded == null -> Unit // 引导状态读取中
                    onboardingNeeded == true -> OnboardingScreen(
                        controller = remember {
                            OnboardingController(
                                store = container.providerStore,
                                prefs = container.onboardingPrefs,
                                fetcher = ModelListFetcher(),
                                scope = scope,
                                onDone = { onboardingNeeded = false },
                                onSkipPlaySample = {
                                    onboardingNeeded = false
                                    scope.launch(Dispatchers.IO) {
                                        // 与首页「运行示例」同源：加载 assets/samples/stars.html
                                        // （质量修复：此前误建 EMPTY_HTML 空项目，名星空示例实为空白页）
                                        val html = runCatching {
                                            context.assets.open("samples/stars.html")
                                                .bufferedReader().use { it.readText() }
                                        }.getOrNull()
                                        val created = html?.let { body ->
                                            runCatching {
                                                container.repo.create(HomeController.SAMPLE_NAME, body)
                                            }.getOrNull()
                                        }
                                        withContext(Dispatchers.Main) {
                                            if (created != null) {
                                                pendingProjectId = created.id
                                            } else {
                                                toast("示例创建失败")
                                            }
                                        }
                                    }
                                },
                            )
                        },
                        apilot = container.apilotController,
                    )
                    meta != null -> RunnerScreen(
                        project = meta,
                        projectDir = container.projectDir(meta.id),
                        erudaEnabled = erudaEnabled,
                        onBack = {
                            runnerProject = null
                            pendingProjectId = null
                        },
                        onToast = toast,
                        onModePersist = { id, mode ->
                            scope.launch(Dispatchers.IO) {
                                runCatching { container.repo.setRunnerMode(id, mode.name.lowercase()) }
                            }
                        },
                        bridge = agentBridge,
                        onSendToAgent = {
                            runnerReturn = meta
                            runnerProject = null
                            agentProject = meta
                        },
                        onOpenChat = {
                            runnerReturn = meta
                            runnerProject = null
                            chatProject = meta
                        },
                        onOpenEditor = {
                            runnerReturn = meta
                            runnerProject = null
                            editorProject = meta
                        },
                        registry = container.permissionRegistry,
                    )
                    agentMeta0 != null -> {
                        val agentMeta = agentMeta0
                        var agentController by remember(agentMeta.id) { mutableStateOf<AgentController?>(null) }
                        LaunchedEffect(agentMeta.id) {
                            val wired = AiWiring(container).wire(AgentRole.AGENT_MAIN, agentMeta.id)
                            // 隐私告知记录（规格 §6：首次使用 Agent/云 API 前告知；此处落确认时间戳）
                            launch {
                                runCatching {
                                    container.privacyPreferences.recordNotice(
                                        "agent",
                                        java.time.Instant.now().toString(),
                                    )
                                }
                            }
                            agentController = wired?.let { w ->
                                AgentController(
                                    scope = scope,
                                    deps = AgentController.Deps(
                                        projectId = agentMeta.id,
                                        projectName = agentMeta.name,
                                        repo = container.repo,
                                        vision = w.vision,
                                        contextLimit = w.contextWindow,
                                        llm = w.chat,
                                        fastChat = w.fastChat,
                                        template = w.template,
                                        fastTemplate = w.fastTemplate,
                                        memory = newGlobalMemory(container),
                                        web = agentBridge.webControl(),
                                        recordUsage = w.recordUsage,
                                        onToast = toast,
                                        onNotify = container.eventNotifier,
                                    ),
                                )
                            }
                            if (wired == null) toast("请先在「AI 服务商」添加服务商")
                        }
                        LaunchedEffect(agentController) {
                            agentController?.state?.collect { agentRunning = it.running }
                        }
                        val controller = agentController
                        if (controller != null) {
                            AgentScreen(controller = controller, onBack = { agentProject = null; backFromSub() })
                        } else {
                            // 无 Provider 空态（真机夜间循环：此前白屏无任何反馈）
                            Box(
                                Modifier.fillMaxSize().padding(32.dp),
                                contentAlignment = androidx.compose.ui.Alignment.Center,
                            ) {
                                Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                                    Text("还没有接入 AI 服务商", style = MaterialTheme.typography.titleMedium)
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        "接入后 Agent 即可自主修复代码",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.height(20.dp))
                                    Button(onClick = {
                                        agentProject = null
                                        tab = TAB_SETTINGS
                                        settingsPage = "providers"
                                    }) { Text("去接入") }
                                    Spacer(Modifier.height(12.dp))
                                    TextButton(onClick = { agentProject = null }) { Text("返回") }
                                }
                            }
                        }
                    }
                    editorMeta0 != null -> {
                        val editorMeta = editorMeta0
                        val controller = remember(editorMeta.id) {
                            EditorController(
                                repo = container.repo,
                                projectId = editorMeta.id,
                                scope = scope,
                                agentRunningProvider = { agentRunning },
                                onToast = toast,
                            )
                        }
                        LaunchedEffect(editorMeta.id, agentRunning) {
                            controller.open()
                            controller.refreshAgentRunning()
                        }
                        var editorFont by remember(editorMeta.id) { mutableStateOf("monospace") }
                        LaunchedEffect(editorMeta.id) {
                            editorFont = container.generalPreferences.editorFontFamily.first()
                        }
                        EditorScreen(
                            controller = controller,
                            fontFamily = editorFont,
                            onBack = { editorProject = null; backFromSub() },
                            onAskAi = { ask ->
                                // PM 审计修复：不再跳设置 tab（导航断裂）；全屏对话 + 返回还原编辑器
                                chatAsk = ask
                                editorReturn = editorMeta
                                editorProject = null
                                chatProject = editorMeta
                            },
                        )
                    }
                    chatMeta0 != null -> ChatPage(
                        container = container,
                        scope = scope,
                        onBack = { chatProject = null; chatAsk = null; backFromSub() },
                        onToast = toast,
                        initialAsk = chatAsk,
                        projectId = chatMeta0.id,
                        onGoProviders = { chatProject = null; tab = TAB_SETTINGS; settingsPage = "providers" },
                    )
                    pasteDraft != null -> {
                        val controller = remember(pasteDraft) {
                            PastePreviewController(
                                repo = container.repo,
                                scope = scope,
                                autoRunStore = container.pastePreferences,
                                strictness = {
                                    if (container.pastePreferences.cleanStrictness.first() ==
                                        com.zhique.runner.paste.PastePreferences.CLEAN_CONSERVATIVE
                                    ) {
                                        com.zhique.core.paste.CleanStrict.CONSERVATIVE
                                    } else {
                                        com.zhique.core.paste.CleanStrict.STANDARD
                                    }
                                },
                                onToast = toast,
                                onRun = { created ->
                                    pasteDraft = null
                                    runnerProject = null
                                    pendingProjectId = created.id
                                },
                            )
                        }
                        LaunchedEffect(controller) {
                            // AI 兜底接线（Task 4.6）：快循环角色通道注入后再跑管道
                            val wired = runCatching {
                                AiWiring(container).wire(com.zhique.core.ai.AgentRole.FAST_LOOP)
                            }.getOrNull()
                            if (wired != null) controller.setAiParser(ProviderAiFallback(wired))
                            pasteDraft?.let { controller.start(it) }
                        }
                        PastePreviewScreen(
                            controller = controller,
                            onBack = { pasteDraft = null },
                            onOpenPromptBridge = { seed ->
                                promptSeed = seed
                                pasteDraft = null
                                tab = TAB_SETTINGS
                                settingsPage = "promptbridge"
                            },
                        )
                    }
                    wizardMeta0 != null -> {
                        val wMeta = wizardMeta0
                        val controller = remember(wMeta.id) {
                            com.zhique.runner.export.ExportController(
                                projectId = wMeta.id,
                                repo = container.repo,
                                registry = container.permissionRegistry,
                                keystore = container.keystoreManager,
                                executor = { projectId, appName, variant, iconColor ->
                                    withContext(Dispatchers.IO) {
                                        container.exportService.export(projectId, appName, variant, iconColor)
                                    }
                                },
                                scope = scope,
                                onToast = toast,
                                onNotify = container.eventNotifier,
                            )
                        }
                        com.zhique.runner.export.ExportWizardScreen(
                            controller = controller,
                            onDone = { wizardProject = null },
                            onToast = toast,
                        )
                    }
                    publishMeta0 != null -> {
                        val pMeta = publishMeta0
                        // AI commit message 生成器（快循环角色；失败回落启发式，规格 F11「可改」）
                        var aiGen by remember(pMeta.id) {
                            mutableStateOf<(suspend (String) -> String?)?>(null)
                        }
                        LaunchedEffect(pMeta.id) {
                            val wired = runCatching {
                                AiWiring(container).wire(AgentRole.FAST_LOOP)
                            }.getOrNull()
                            if (wired != null) {
                                aiGen = { summary ->
                                    val req = wired.fastTemplate.copy(
                                        messages = listOf(
                                            com.zhique.core.ai.ChatMessage(
                                                role = "user",
                                                content = "根据以下项目变更摘要写一条简洁的中文 commit message：" +
                                                    "一行、不带引号、不加句号。\n$summary",
                                            ),
                                        ),
                                    )
                                    val sb = StringBuilder()
                                    wired.fastChat(req).collect { ev ->
                                        if (ev is com.zhique.core.ai.StreamEvent.ContentDelta) sb.append(ev.text)
                                    }
                                    sb.toString().trim().lines().firstOrNull { it.isNotBlank() }
                                        ?.take(120)?.takeIf { it.isNotBlank() }
                                }
                            }
                        }
                        val controller = remember(pMeta.id) {
                            com.zhique.runner.publish.PublishController(
                                projectId = pMeta.id,
                                repo = container.repo,
                                git = container.gitRepo,
                                api = container.githubApi,
                                pats = container.patStore,
                                engine = container.releaseJobEngine,
                                aiCommitMessage = { summary -> aiGen?.invoke(summary) },
                                apkResolver = { id ->
                                    withContext(Dispatchers.IO) { container.exportedApk(id) }
                                },
                                scope = scope,
                                onToast = toast,
                            )
                        }
                        com.zhique.runner.publish.PublishWizardScreen(
                            controller = controller,
                            onDone = { publishProject = null },
                            onToast = toast,
                            onOpenPatGuide = {
                                publishProject = null
                                tab = TAB_SETTINGS
                                settingsPage = "publish"
                            },
                        )
                    }
                    tab == TAB_SETTINGS -> when (settingsPage) {
                        "exports" -> androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
                            androidx.compose.foundation.layout.Row(
                                Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            ) {
                                androidx.compose.material3.IconButton(onClick = { settingsPage = null }, modifier = Modifier.testTag("exports-back")) {
                                    androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                                }
                                androidx.compose.material3.Text("交付与档案", style = MaterialTheme.typography.titleMedium)
                            }
                            com.zhique.runner.export.ExportCenterScreen(
                                repo = container.repo,
                                keystore = container.keystoreManager,
                                onExport = { wizardProject = it },
                                onPush = { publishProject = it },
                                onToast = toast,
                            )
                        }
                        "chat" -> ChatPage(
                            container = container,
                            scope = scope,
                            onBack = { settingsPage = null; chatAsk = null },
                            onToast = toast,
                            initialAsk = chatAsk,
                            onGoProviders = { settingsPage = "providers" },
                        )
                        "providers" -> ProvidersScreen(
                            controller = remember {
                                ProvidersController(container.providerStore, ModelListFetcher(), scope, catalog = container.modelCatalog)
                            },
                            apilot = container.apilotController,
                            onBack = { settingsPage = null },
                            onOpenPromptBridge = { settingsPage = "promptbridge" },
                        )
                        "router" -> RoleRouterPage(container, scope, onBack = { settingsPage = null })
                        "permissions" -> PermissionCenterPage(
                            container = container,
                            focusProjectId = permFocus,
                            onBack = { settingsPage = null; permFocus = null },
                            onOpenKeystore = { settingsPage = "exports" },
                            keystore = container.keystoreManager,
                        )
                        "publish" -> Box(Modifier.fillMaxSize()) {
                            com.zhique.runner.publish.PublishSyncScreen(
                                patStore = container.patStore,
                                prefs = container.publishPreferences,
                                onBack = { settingsPage = null },
                                onToast = toast,
                            )
                        }
                        "about" -> AboutPage(
                            container = container,
                            scope = scope,
                            onBack = { settingsPage = null },
                            onToast = toast,
                        )
                        "promptbridge" -> com.zhique.runner.settings.PromptBridgeScreen(
                            controller = remember {
                                com.zhique.runner.settings.PromptBridgeController()
                            },
                            onBack = {
                                settingsPage = null
                                promptSeed = null
                            },
                            initialIdea = promptSeed,
                            onToast = toast,
                        )
                        "diagnostics" -> com.zhique.runner.settings.DiagnosticsScreen(
                            controller = remember {
                                com.zhique.runner.settings.DiagnosticsController(
                                    store = container.providerStore,
                                    scope = scope,
                                )
                            },
                            onBack = { settingsPage = null },
                        )
                        "output" -> com.zhique.runner.settings.OutputContextScreen(
                            prefs = container.aiPreferences,
                            onBack = { settingsPage = null },
                            onToast = toast,
                        )
                        "tokens" -> com.zhique.runner.settings.TokenStatsScreen(
                            usageMeter = container.usageMeter,
                            onBack = { settingsPage = null },
                            resolveProvider = { id ->
                                container.providerStore.list().firstOrNull { it.id == id }?.name
                            },
                            resolveProject = { id ->
                                container.repo.list().firstOrNull { it.id == id }?.name
                            },
                        )
                        "general" -> com.zhique.runner.settings.GeneralScreen(
                            general = container.generalPreferences,
                            paste = container.pastePreferences,
                            web = container.webPreferences,
                            onBack = { settingsPage = null },
                            onToast = toast,
                        )
                        "privacy" -> com.zhique.runner.settings.PrivacyScreen(
                            privacy = container.privacyPreferences,
                            lock = container.appLockController,
                            audit = container.apilotAudit,
                            onBack = { settingsPage = null },
                            onToast = toast,
                        )
                        "developer" -> com.zhique.runner.settings.DeveloperScreen(
                            web = container.webPreferences,
                            onBack = { settingsPage = null },
                            onToast = toast,
                            debug = {
                                com.zhique.runner.settings.DebugBackendCard(
                                    prefs = container.debugPreferences,
                                    server = container.debugServer,
                                    debuggableDefault = (context.applicationInfo.flags and
                                        android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0,
                                    onOpenEvents = { settingsPage = "devents" },
                                )
                            },
                        )
                        "devents" -> com.zhique.runner.settings.DebugEventsScreen(
                            serverPort = { container.debugServer.port },
                            onBack = { settingsPage = null },
                        )
                        else -> SettingsScreen(
                            onOpenChat = { settingsPage = "chat" },
                            onOpenProviders = { settingsPage = "providers" },
                            onOpenRoleRouter = { settingsPage = "router" },
                            onOpenOutputContext = { settingsPage = "output" },
                            onOpenTokenStats = { settingsPage = "tokens" },
                            onOpenDiagnostics = { settingsPage = "diagnostics" },
                            onOpenPermissionCenter = { settingsPage = "permissions" },
                            onOpenPublishSync = { settingsPage = "publish" },
                            onOpenExports = { settingsPage = "exports" },
                            onOpenGeneral = { settingsPage = "general" },
                            onOpenPrivacy = { settingsPage = "privacy" },
                            onOpenAbout = { settingsPage = "about" },
                            onOpenDeveloper = { settingsPage = "developer" },
                        )
                    }
                    else -> {
                        val clipboardDetect by container.pastePreferences.clipboardDetection
                            .collectAsState(initial = true)
                        HomeScreen(
                        repo = container.repo,
                        clipboardDetection = clipboardDetect,
                        onRun = {
                            runnerProject = null
                            pendingProjectId = it.id
                        },
                        onChat = { chatProject = it },
                        onEdit = { editorProject = it },
                        onDeliver = { wizardProject = it },
                        onToast = toast,
                        clipboardText = { readClipboardText(context) },
                        onPastePreview = { text ->
                            pasteDraft = text
                        },
                        onOpenSettings = { tab = TAB_SETTINGS },
                        onOpenPermissions = { project ->
                            permFocus = project.id
                            tab = TAB_SETTINGS
                            settingsPage = "permissions"
                        },
                        )
                    }
                }
            }

            if (!fullScreen && onboardingNeeded == false) {
                // iOS 液态玻璃浮层标签栏：半透明材质 + 高光描边，环境底从中透出
                LiquidGlass(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag("bottom-nav"),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                ) {
                    Row(Modifier.fillMaxWidth().height(56.dp)) {
                        GlassTabItem("▦", "项目", tab == TAB_PROJECTS, Modifier.weight(1f).testTag("tab-projects")) { tab = TAB_PROJECTS }
                        GlassTabItem("⚙", "设置", tab == TAB_SETTINGS, Modifier.weight(1f).testTag("tab-settings")) { tab = TAB_SETTINGS }
                    }
                }
            }
            }
            // 授权卡浮在最上层（M5：zq/W3C 授权路径的唯一 UI 出口）
            com.zhique.runner.permission.PermissionPromptHost(container.permissionPrompt)
            // M9：应用锁验证门（最顶层，锁住全部内容）。
            // unknown（读盘前）也拦——否则启用应用锁的设备冷启动有 ~2s 内容闪现窗口
            if (lockState.locked || lockState.unknown) {
                if (lockState.unknown && !lockState.locked) {
                    // 未知期渲染空白壳（不渲染 PIN 输入，未配置时避免误导）
                    Box(Modifier.fillMaxSize())
                } else {
                    com.zhique.runner.settings.AppLockScreen(
                        controller = appLock,
                        state = lockState,
                    )
                }
            }
        }
        }
    }
}

/** 玻璃标签项：选中 tint + 半粗字，未选中灰阶（iOS 标签栏语义）。 */
@Composable
private fun GlassTabItem(
    glyph: String,
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(glyph, style = MaterialTheme.typography.titleMedium, color = tint)
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}

private const val TAB_PROJECTS = "projects"
private const val TAB_SETTINGS = "settings"

private fun newGlobalMemory(container: AppContainer): com.zhique.core.agent.Memory =
    com.zhique.core.agent.Memory(
        java.io.File(container.root, "global-memory.md"),
        container.repo,
    )

/** 对话页：AiWiring 装配 ChatController（上下文窗口真值 + UsageMeter 挂点）。 */
@Composable
private fun ChatPage(
    container: AppContainer,
    scope: kotlinx.coroutines.CoroutineScope,
    onBack: () -> Unit,
    onToast: (String) -> Unit,
    initialAsk: EditorAskContext? = null,
    projectId: String? = null,
    onGoProviders: () -> Unit = {},
) {
    var controller by remember { mutableStateOf<ChatController?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        launch {
            runCatching {
                container.privacyPreferences.recordNotice("chat", java.time.Instant.now().toString())
            }
        }
        val wired = AiWiring(container).wire(AgentRole.CHAT)
        controller = wired?.let { w ->
            ChatController(
                chat = w.chat,
                newRequest = { history -> w.template.copy(messages = history) },
                contextWindow = w.contextWindow,
                contextBudget = com.zhique.core.agent.ContextBudget(contextLimit = w.contextWindow),
                fastChat = w.fastChat,
                fastTemplate = w.fastTemplate,
                recordUsage = w.recordUsage,
                scope = scope,
            )
        }
        if (wired == null) notice = "请先在「AI 服务商」添加服务商"
    }
    val c = controller
    if (c != null) {
        // 项目上下文注入（PM 审计修复）：从项目卡/运行器进入的对话，首条 system 消息
        // 携带 index.html（截 6000 字符），AI 才知道"这个项目"是什么
        LaunchedEffect(c, projectId) {
            val pid = projectId ?: return@LaunchedEffect
            val html = withContext(Dispatchers.IO) {
                runCatching { container.repo.readFile(pid, "index.html") }.getOrNull()
            } ?: return@LaunchedEffect
            c.seedSystemContext(
                "你是织雀内的编程助手。当前项目「${container.repo.meta(pid)?.name ?: pid}」的 index.html 如下（超长时截断）：\n" +
                    "```html\n" + html.take(6000) + "\n```\n" +
                    "用户会要求你解释、修改或继续开发这个项目。回答代码修改时给出可直接替换的完整片段。",
            )
        }
        // 编辑器「问 AI 这段」→ 选中范围作附加上下文自动发送（规格 F3）
        LaunchedEffect(initialAsk, c) {
            initialAsk?.let { ask -> c.sendWithContext(ask.selection, ask.language, ask.question) }
        }
        // 空历史欢迎语：项目对话直接打招呼（空屏对话区让用户不知道能干什么）；
        // 无项目自由对话同样给引导。项目名读盘走 IO（QA 审查：组合期阻塞读 +
        // meta() 对已删项目直接 throw——对话停留期间项目被删即组合期崩溃）
        var welcome by remember { mutableStateOf("自由对话（未绑定项目）。想让我修改某个作品，回到主页点项目卡上的「AI」即可带上页面上下文。") }
        LaunchedEffect(projectId) {
            val pid = projectId ?: return@LaunchedEffect
            val name = withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { container.repo.meta(pid).name }.getOrNull()
            }
            if (name != null) welcome = "这是「$name」的对话。可以直接问我怎么改这个页面。"
        }
        ChatScreen(controller = c, onBack = onBack, welcomeText = welcome)
    } else {
        // 无 Provider 空态（真机夜间循环：只有一行提示无处可去——补引导按钮）
        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
            Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                Text(
                    notice ?: "正在接入…",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(24.dp),
                )
                if (notice?.contains("服务商") == true) {
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onGoProviders) { Text("去接入") }
                }
            }
        }
    }
}

@Composable
private fun RoleRouterPage(
    container: AppContainer,
    scope: kotlinx.coroutines.CoroutineScope,
    onBack: () -> Unit,
) {
    var defaultProviderId by remember { mutableStateOf("") }
    var providers by remember { mutableStateOf<List<com.zhique.runner.settings.ProviderConfig>>(emptyList()) }
    LaunchedEffect(Unit) {
        container.modelCatalog.load()
        val list = container.providerStore.list()
        defaultProviderId = list.firstOrNull()?.id ?: ""
        providers = list
    }
    Box(Modifier.fillMaxSize()) {
        RoleRouterScreen(
            store = container.roleBindingStore,
            defaultProviderId = defaultProviderId,
            scope = scope,
            providers = providers,
            catalog = container.modelCatalog,
            fetcher = container.modelListFetcher,
            keyDecrypt = container.providerStore::decryptKeyById,
        )
        androidx.compose.material3.IconButton(
            onClick = onBack,
            modifier = Modifier
                .padding(4.dp)
                .testTag("router-back"),
        ) {
            androidx.compose.material3.Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
            )
        }
    }
}

/** 关于页（M7 Task 7.3）：版本真值 + 真实 UpdateChecker（GitHub Releases 自更新）。 */
@Composable
private fun AboutPage(
    container: AppContainer,
    scope: kotlinx.coroutines.CoroutineScope,
    onBack: () -> Unit,
    onToast: (String) -> Unit,
) {
    val context = LocalContext.current
    val version = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
        }.getOrDefault("0.0.0")
    }
    val downloadsDir = remember {
        @Suppress("DEPRECATION")
        context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
            ?: java.io.File(context.filesDir, "updates")
    }
    val controller = remember {
        val checker = com.zhique.core.publish.UpdateChecker(container.githubApi)
        com.zhique.runner.settings.AboutController(
            currentVersion = version,
            channelProvider = { container.publishPreferences.channelNow() },
            check = { channel, current -> checker.check(channel, current) },
            changelogs = {
                container.githubApi.listReleases("", UpdateCheckerRefs.owner, UpdateCheckerRefs.repo)
            },
            download = { info, dir, onProgress -> checker.downloadApk(info, dir, onProgress) },
            downloadsDir = downloadsDir,
            scope = scope,
            localChangelog = {
                // 内置更新日志（assets/CHANGELOG.md）——无 GitHub 也能查
                runCatching {
                    context.assets.open("CHANGELOG.md").bufferedReader().use { it.readText() }
                }.getOrNull()
            },
            onNotify = container.eventNotifier,
        )
    }
    com.zhique.runner.settings.AboutScreen(controller = controller, onBack = onBack, onToast = onToast)
}

private object UpdateCheckerRefs {
    const val owner = com.zhique.core.publish.UpdateChecker.ZHIQUE_OWNER
    const val repo = com.zhique.core.publish.UpdateChecker.ZHIQUE_REPO
}

/** 权限中心页（M5）：注册表 + 项目焦点；「导出与签名」入口切到导出中心（M6 接线）。 */
@Composable
private fun PermissionCenterPage(
    container: AppContainer,
    focusProjectId: String?,
    onBack: () -> Unit,
    onOpenKeystore: () -> Unit = {},
    keystore: com.zhique.core.export.KeystoreManager? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val osGateway = androidx.compose.runtime.remember(context) {
        (context as? androidx.activity.ComponentActivity)
            ?.let { com.zhique.runner.runner.ActivityOsPermissionGateway(it) }
    }
    com.zhique.runner.permission.PermissionCenterScreen(
        repo = container.repo,
        registry = container.permissionRegistry,
        focusProjectId = focusProjectId,
        onBack = onBack,
        onOpenKeystore = onOpenKeystore,
        keystore = keystore,
        osGateway = osGateway,
    )
}
