package com.zhique.runner.runner

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.zhique.core.project.ProjectMeta
import com.zhique.core.web.CapabilityReport
import com.zhique.core.web.WebViewHost
import com.zhique.core.web.debug.EventBuffer
import com.zhique.core.web.debug.Timeline
import com.zhique.core.web.debug.TimelineReducer
import com.zhique.core.web.debug.ZqProtocol
import com.zhique.runner.ui.theme.ZqSpring
import kotlinx.coroutines.launch
import kotlin.math.abs

/** 分屏分界吸附档：30 / 50 / 70%。 */
object SplitSnap {
    val levels = listOf(0.3f, 0.5f, 0.7f)
    fun target(fraction: Float): Float = levels.minBy { abs(it - fraction) }
}

/**
 * 运行器内容（可测核心）：顶栏（返回/项目名/三态切换器）+ 三模式内容。
 * [webView] 由调用方注入（真实 [com.zhique.core.web.WebViewHost] 或测试桩）。
 */
@Composable
fun RunnerContent(
    projectName: String,
    mode: RunnerMode,
    onModeChange: (RunnerMode) -> Unit,
    timeline: Timeline,
    capability: CapabilityReport?,
    onBack: () -> Unit,
    onSendToAgent: () -> Unit,
    onReload: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenEditor: () -> Unit = {},
    webView: @Composable (Modifier) -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        // 顶栏
        Surface(tonalElevation = 2.dp, color = MaterialTheme.colorScheme.surface) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("runner-back")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                Text(
                    projectName,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onOpenEditor, modifier = Modifier.testTag("open-editor")) {
                    Icon(Icons.Filled.Edit, contentDescription = "编辑")
                }
                RunnerModeSwitcher(mode, onModeChange, Modifier.padding(end = 8.dp))
            }
        }
        when (mode) {
            RunnerMode.DRAWER -> Box(Modifier.fillMaxSize()) {
                webView(Modifier.fillMaxSize().testTag("web-host"))
                val drawerState = remember { DebugDrawerState() }
                DebugDrawer(
                    state = drawerState,
                    mode = mode,
                    onModeChange = onModeChange,
                    timeline = timeline,
                    capability = capability,
                    onSendToAgent = onSendToAgent,
                    onReload = onReload,
                    modifier = Modifier.matchParentSize(),
                )
            }

            RunnerMode.SPLIT -> SplitLayout(
                timeline = timeline,
                capability = capability,
                onSendToAgent = onSendToAgent,
                onModeChange = onModeChange,
                mode = mode,
                webView = webView,
            )

            RunnerMode.BUBBLE -> Box(Modifier.fillMaxSize()) {
                webView(Modifier.fillMaxSize().testTag("web-host"))
                val bubbleState = remember { FloatingBubbleState() }
                FloatingBubble(
                    state = bubbleState,
                    summary = (timeline.problems + timeline.console)
                        .takeLast(3)
                        .joinToString("\n") { entryLabel(it) },
                    onOpenAgent = onSendToAgent,
                )
            }
        }
    }
}

@Composable
private fun SplitLayout(
    timeline: Timeline,
    capability: CapabilityReport?,
    onSendToAgent: () -> Unit,
    onModeChange: (RunnerMode) -> Unit,
    mode: RunnerMode,
    webView: @Composable (Modifier) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val split = remember { Animatable(0.5f) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val h = constraints.maxHeight.toFloat()
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(split.value.coerceIn(0.15f, 0.85f))) {
                webView(Modifier.fillMaxSize().testTag("web-host"))
            }
            // 可拖分界线，释放 spring 吸附 30/50/70%
            // 与 DebugDrawer 同一跟手模式：起点定格 + 绝对位移 snapTo（增量累加会漂移）
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(16.dp)
                    .testTag("split-divider")
                    .pointerInput(h) {
                        var startFraction = 0.5f
                        detectVerticalDragGestures(
                            onDragStart = { startFraction = split.value },
                            onVerticalDrag = { change, dy ->
                                change.consume()
                                scope.launch {
                                    split.snapTo(
                                        (startFraction + dy / h)
                                            .coerceIn(SplitSnap.levels.first(), SplitSnap.levels.last()),
                                    )
                                }
                            },
                            onDragEnd = {
                                scope.launch { split.animateTo(SplitSnap.target(split.value), ZqSpring) }
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
            }
            // AI 面板占位（M4 接线）
            Box(
                Modifier
                    .weight(1f - split.value.coerceIn(0.15f, 0.85f))
                    .background(MaterialTheme.colorScheme.surface),
            ) {
                Text(
                    "AI 面板（M4 接线）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .testTag("ai-panel"),
                )
            }
        }
    }
}

/**
 * 运行器屏（真实 WebView）：持有 [WebViewHost]，采集事件折算时间线，
 * 模式切换写回 projectMeta.runnerMode；M5 起注入 zq 全能力桥与 W3C 权限网关
 * （[registry] 为 null 时行为与 M4 一致，测试路径用）。
 * [bridge] 供「交给 Agent」把宿主/事件缓冲登记给 Agent 会话（M4 接线）。
 */
@Composable
fun RunnerScreen(
    project: ProjectMeta,
    projectDir: java.io.File,
    onBack: () -> Unit,
    onToast: (String) -> Unit,
    onModePersist: (String, RunnerMode) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
    bridge: com.zhique.runner.agent.AgentBridge? = null,
    onSendToAgent: () -> Unit = {},
    onOpenEditor: () -> Unit = {},
    registry: com.zhique.core.permission.PermissionRegistry? = null,
    /** M9：eruda 高级面板开关（设置「通用 → Web」，重开运行器生效）。 */
    erudaEnabled: Boolean = false,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val host = remember(project.id) {
        WebViewHost(context, projectDir).also { it.erudaEnabled = erudaEnabled }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var recreateKey by remember { mutableIntStateOf(0) }
    var capability by remember { mutableStateOf<CapabilityReport?>(null) }
    val buffer = remember(project.id) { EventBuffer() }
    // 版本号单调递增：容量截断后 buffer 尺寸恒定，size 不能当重算信号（Critical 1）
    var timelineVersion by remember(project.id) { mutableLongStateOf(0L) }
    var mode by remember(project.id) {
        mutableStateOf(
            runCatching { RunnerMode.valueOf(project.runnerMode.uppercase()) }
                .getOrDefault(RunnerMode.DRAWER),
        )
    }
    val composeScope = rememberCoroutineScope()

    // M5：zq 全能力桥（dispatcher + W3C 网关）；运行中登记；销毁时全量关停
    // （审查修复 I2：location/sensor 监听、订阅句柄、相机取景浮层一并收掉）
    DisposableEffect(host, registry) {
        val dispatcher = registry?.let { ZqWiring.install(project, projectDir, context, host, composeScope, it) }
        com.zhique.runner.permission.RunningProjects.enter(project.id)
        onDispose {
            com.zhique.runner.permission.RunningProjects.exit(project.id)
            dispatcher?.shutdown()
        }
    }

    LaunchedEffect(host) {
        launch { host.capability.collect { capability = it } }
        host.events.collect { e ->
            // zq_call 只在此处一次性分发（副作用层）；未注册能力立即回 rejected，
            // 页面 promise 不等 30s 超时（Critical 2 + Important 3）
            if (e.type == TimelineReducer.TYPE_ZQ_CALL) {
                val handled = host.zqRouter.route(e)
                if (!handled) {
                    host.evaluate(
                        ZqProtocol.rejectJs(e.id, "未注册能力: ${e.ns}.${e.fn}"),
                    )
                }
            } else {
                buffer.append(e)
                timelineVersion = buffer.version
            }
        }
    }
    DisposableEffect(host) {
        host.onWebViewRecreated = { recreateKey++ }
        host.onCapabilityDetected = { report ->
            if (report.degraded) onToast("WebGPU 不可用，已降级 WebGL")
        }
        host.onCrashGiveUp = { onToast("页面多次崩溃，已停止自动恢复") }
        bridge?.host = host
        bridge?.buffer = buffer
        onDispose {
            bridge?.unbind(host)
            host.destroy()
        }
    }
    DisposableEffect(host) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> host.resume()
                Lifecycle.Event.ON_PAUSE -> host.pause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val timeline = remember(timelineVersion) {
        TimelineReducer.reduce(buffer.events)
    }

    // Aurora Glass（质量审查 Important-2）：运行域强制深空主题——浅色系统进运行器
    // 也是深空底 + 深空光斑，双域随屏不随系统
    com.zhique.runner.ui.theme.ZqTheme(darkTheme = true) {
        com.zhique.runner.ui.components.AuroraBackground(
            Modifier.fillMaxSize(),
            domain = com.zhique.runner.ui.components.AuroraDomain.DARK,
        ) {
    Box(Modifier.fillMaxSize()) {
        RunnerContent(
            projectName = project.name,
            mode = mode,
            onModeChange = { m ->
                mode = m
                onModePersist(project.id, m)
            },
            timeline = timeline,
            capability = capability,
            onBack = onBack,
            onSendToAgent = onSendToAgent,
            onReload = { host.reload() },
            modifier = modifier,
            onOpenEditor = onOpenEditor,
            webView = { m ->
                key(recreateKey) {
                    AndroidView(modifier = m, factory = { host.webView })
                }
            },
        )
        // 审查修复 #4：zq.camera.startPreview 的取景浮层（Compose 层）
        ZqWiring.ZqCameraPreviewOverlay()
    }
        }
    }
}
