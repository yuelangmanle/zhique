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
import com.zhique.runner.export.ExportCenterPlaceholder
import com.zhique.runner.home.HomeController
import com.zhique.runner.home.HomeScreen
import com.zhique.runner.onboarding.OnboardingController
import com.zhique.runner.onboarding.OnboardingScreen
import com.zhique.runner.paste.PastePreviewController
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
    var tab by rememberSaveable { mutableStateOf(TAB_PROJECTS) }
    var settingsPage by rememberSaveable { mutableStateOf<String?>(null) }

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
    val fullScreen = onboardingNeeded == true || runnerProject != null ||
        agentMeta0 != null || editorMeta0 != null || pasteDraft != null

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
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
                                onToast = toast,
                            )
                        }
                        LaunchedEffect(editorMeta.id) { controller.open() }
                        LaunchedEffect(agentProject != null) { controller.refreshAgentRunning() }
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
                            pasteDraft?.let { controller.start(it) }
                        }
                        PastePreviewScreen(
                            controller = controller,
                            onBack = { pasteDraft = null },
                        )
                    }
                    tab == TAB_EXPORT -> ExportCenterPlaceholder()
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
                        else -> SettingsScreen(
                            onOpenChat = { settingsPage = "chat" },
                            onOpenProviders = { settingsPage = "providers" },
                            onOpenRoleRouter = { settingsPage = "router" },
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
