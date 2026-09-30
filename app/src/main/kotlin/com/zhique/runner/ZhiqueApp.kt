package com.zhique.runner

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
    var chatAsk by remember { mutableStateOf<EditorAskContext?>(null) }
    var wizardProject by remember { mutableStateOf<ProjectMeta?>(null) }
    var publishProject by remember { mutableStateOf<ProjectMeta?>(null) }
    // Agent 会话运行真值（编辑器只读横幅的依据；不靠全屏互斥兜底）
    var agentRunning by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf(TAB_PROJECTS) }
    var settingsPage by rememberSaveable { mutableStateOf<String?>(null) }
    var permFocus by rememberSaveable { mutableStateOf<String?>(null) }

    // 首启引导：仅在未完成时显示（X4）
    var onboardingNeeded by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        onboardingNeeded = !container.onboardingPrefs.isDone()
    }

    val toast: (String) -> Unit = { msg ->
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
    val fullScreen = onboardingNeeded == true || runnerProject != null ||
        agentMeta0 != null || editorMeta0 != null || pasteDraft != null || wizardMeta0 != null ||
        publishMeta0 != null

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
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
                                        val created = runCatching {
                                            container.repo.create(HomeController.SAMPLE_NAME, HomeController.EMPTY_HTML)
                                        }.getOrNull()
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
                    )
                    meta != null -> RunnerScreen(
                        project = meta,
                        projectDir = container.projectDir(meta.id),
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
                            runnerProject = null
                            agentProject = meta
                        },
                        onOpenEditor = {
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
                            AgentScreen(controller = controller, onBack = { agentProject = null })
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
                        EditorScreen(
                            controller = controller,
                            onBack = { editorProject = null },
                            onAskAi = { ask ->
                                chatAsk = ask
                                editorProject = null
                                tab = TAB_SETTINGS
                                settingsPage = "chat"
                            },
                        )
                    }
                    pasteDraft != null -> {
                        val controller = remember(pasteDraft) {
                            PastePreviewController(
                                repo = container.repo,
                                scope = scope,
                                autoRunStore = container.pastePreferences,
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
                                executor = { projectId, appName, variant ->
                                    withContext(Dispatchers.IO) {
                                        container.exportService.export(projectId, appName, variant)
                                    }
                                },
                                scope = scope,
                                onToast = toast,
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
                    tab == TAB_EXPORT -> com.zhique.runner.export.ExportCenterScreen(
                        repo = container.repo,
                        keystore = container.keystoreManager,
                        onExport = { wizardProject = it },
                        onPush = { publishProject = it },
                        onToast = toast,
                    )
                    tab == TAB_SETTINGS -> when (settingsPage) {
                        "chat" -> ChatPage(
                            container = container,
                            scope = scope,
                            onBack = { settingsPage = null; chatAsk = null },
                            onToast = toast,
                            initialAsk = chatAsk,
                        )
                        "providers" -> ProvidersScreen(
                            controller = remember {
                                ProvidersController(container.providerStore, ModelListFetcher(), scope)
                            },
                            onBack = { settingsPage = null },
                        )
                        "router" -> RoleRouterPage(container, scope, onBack = { settingsPage = null })
                        "permissions" -> PermissionCenterPage(
                            container = container,
                            focusProjectId = permFocus,
                            onBack = { settingsPage = null; permFocus = null },
                            onOpenKeystore = { settingsPage = null; tab = TAB_EXPORT },
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
                        else -> SettingsScreen(
                            onOpenChat = { settingsPage = "chat" },
                            onOpenProviders = { settingsPage = "providers" },
                            onOpenRoleRouter = { settingsPage = "router" },
                            onOpenPermissionCenter = { settingsPage = "permissions" },
                            onOpenPublishSync = { settingsPage = "publish" },
                            onOpenAbout = { settingsPage = "about" },
                        )
                    }
                    else -> HomeScreen(
                        repo = container.repo,
                        onRun = {
                            runnerProject = null
                            pendingProjectId = it.id
                        },
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

            if (!fullScreen && onboardingNeeded == false) {
                NavigationBar(modifier = Modifier.fillMaxWidth().testTag("bottom-nav")) {
                    NavigationBarItem(
                        selected = tab == TAB_PROJECTS,
                        onClick = { tab = TAB_PROJECTS },
                        icon = { Text("▦") },
                        label = { Text("项目") },
                        modifier = Modifier.testTag("tab-projects"),
                    )
                    NavigationBarItem(
                        selected = tab == TAB_EXPORT,
                        onClick = { tab = TAB_EXPORT },
                        icon = { Text("⬆") },
                        label = { Text("导出中心") },
                        modifier = Modifier.testTag("tab-export"),
                    )
                    NavigationBarItem(
                        selected = tab == TAB_SETTINGS,
                        onClick = { tab = TAB_SETTINGS },
                        icon = { Text("⚙") },
                        label = { Text("设置") },
                        modifier = Modifier.testTag("tab-settings"),
                    )
                }
            }
            }
            // 授权卡浮在最上层（M5：zq/W3C 授权路径的唯一 UI 出口）
            com.zhique.runner.permission.PermissionPromptHost(container.permissionPrompt)
        }
    }
}

private const val TAB_PROJECTS = "projects"
private const val TAB_EXPORT = "export"
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
) {
    var controller by remember { mutableStateOf<ChatController?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
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
        // 编辑器「问 AI 这段」→ 选中范围作附加上下文自动发送（规格 F3）
        LaunchedEffect(initialAsk, c) {
            initialAsk?.let { ask -> c.sendWithContext(ask.selection, ask.language, ask.question) }
        }
        ChatScreen(controller = c, onBack = onBack)
    } else {
        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
            Text(
                notice ?: "正在接入…",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(24.dp),
            )
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
    LaunchedEffect(Unit) {
        defaultProviderId = container.providerStore.list().firstOrNull()?.id ?: ""
    }
    Box(Modifier.fillMaxSize()) {
        RoleRouterScreen(
            store = container.roleBindingStore,
            defaultProviderId = defaultProviderId,
            scope = scope,
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
    com.zhique.runner.permission.PermissionCenterScreen(
        repo = container.repo,
        registry = container.permissionRegistry,
        focusProjectId = focusProjectId,
        onBack = onBack,
        onOpenKeystore = onOpenKeystore,
        keystore = keystore,
    )
}
