package com.zhique.runner.editor

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.widget.CodeEditor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.eclipse.tm4e.core.registry.IGrammarSource
import org.eclipse.tm4e.core.registry.IThemeSource

/** 编辑器打开的一个文件（[language] 决定 TextMate 语法）。 */
data class EditorFile(val path: String, val content: String, val language: String)

data class EditorUiState(
    val files: List<EditorFile> = emptyList(),
    val activeIndex: Int = 0,
    val agentRunning: Boolean = false,
    val selectedText: String = "",
    val aiInput: String = "",
    val dirty: Boolean = false,
) {
    val activeFile: EditorFile? get() = files.getOrNull(activeIndex)
}

/** 编辑器「问 AI 这段」上下文（→ ChatController.sendWithContext）。 */
data class EditorAskContext(val question: String, val selection: String, val language: String)

/**
 * 轻编辑器控制器（规格 F3）：项目文件 Tab + sora-editor 互操作；
 * Agent 会话运行中只读（agentRunningProvider 采样）；改即存（IO 协程落盘）。
 */
class EditorController(
    private val repo: com.zhique.core.project.ProjectRepository,
    private val projectId: String,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val agentRunningProvider: () -> Boolean = { false },
    private val onToast: (String) -> Unit = {},
) {
    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    /** 打开项目文件（跳过 meta/记忆/快照目录）。 */
    fun open() {
        scope.launch(io) {
            val files = runCatching {
                com.zhique.core.agent.tools.ProjectFiles.walk(repo, projectId)
                    .filter { it != "project.json" }
                    .sortedWith(compareBy({ !it.endsWith(".html") }, { it }))
                    .map { path ->
                        EditorFile(
                            path = path,
                            content = runCatching { repo.readFile(projectId, path) }.getOrDefault(""),
                            language = EditorLanguages.languageOf(path),
                        )
                    }
            }.getOrDefault(emptyList())
            _state.update { it.copy(files = files, activeIndex = 0, agentRunning = agentRunningProvider()) }
        }
    }

    fun selectTab(index: Int) {
        _state.update { it.copy(activeIndex = index, selectedText = "", agentRunning = agentRunningProvider()) }
    }

    /** 编辑器内容变化 → 落盘（改即存；失败 toast）。 */
    fun onContentChange(path: String, content: String) {
        _state.update { s ->
            s.copy(files = s.files.map { if (it.path == path) it.copy(content = content) else it }, dirty = true)
        }
        scope.launch(io) {
            runCatching { repo.writeFile(projectId, path, content) }
                .onFailure { onToast("保存失败：${it.message}") }
        }
    }

    fun setSelection(text: String) {
        _state.update { it.copy(selectedText = text) }
    }

    fun setAiInput(text: String) {
        _state.update { it.copy(aiInput = text) }
    }

    fun refreshAgentRunning() {
        _state.update { it.copy(agentRunning = agentRunningProvider()) }
    }
}

/** TextMate 语法/主题装配（assets 内置 HTML/CSS/JS 三语法；失败回退无高亮）。 */
object EditorLanguages {
    const val THEME_ASSET = "textmate/themes/zhique-dark.json"

    fun languageOf(path: String): String = when {
        path.endsWith(".html") || path.endsWith(".htm") -> "html"
        path.endsWith(".css") -> "css"
        path.endsWith(".js") || path.endsWith(".mjs") || path.endsWith(".json") -> "javascript"
        path.endsWith(".md") -> "markdown"
        else -> "text"
    }

    /** 语法文件映射（scope → assets 路径）。 */
    fun grammarAsset(language: String): String? = when (language) {
        "html" -> "textmate/tmlanguage/html.json"
        "css" -> "textmate/tmlanguage/css.json"
        "javascript" -> "textmate/tmlanguage/javascript.json"
        else -> null
    }

    /** 构建语言；assets 缺失/解析失败回退 [EmptyLanguage]（编辑器不因高亮崩）。 */
    fun create(context: Context, path: String): Language {
        val language = languageOf(path)
        val asset = grammarAsset(language) ?: return EmptyLanguage()
        return runCatching {
            val grammar = context.assets.open(asset)
            val theme = context.assets.open(THEME_ASSET)
            TextMateLanguage.createNoCompletion(
                IGrammarSource.fromInputStream(grammar, asset.substringAfterLast('/'), Charsets.UTF_8),
                IThemeSource.fromInputStream(theme, "zhique-dark.json", Charsets.UTF_8),
            )
        }.getOrElse { EmptyLanguage() }
    }

    /** 主题配色（失败返回 null，用编辑器默认）。 */
    fun colorScheme(context: Context): TextMateColorScheme? = runCatching {
        TextMateColorScheme.create(
            IThemeSource.fromInputStream(
                context.assets.open(THEME_ASSET),
                "zhique-dark.json",
                Charsets.UTF_8,
            ),
        )
    }.getOrNull()
}

/** 选中变化透出的 CodeEditor 子类（sora-editor 的选中回调为 protected）。 */
class ZqCodeEditor(context: Context) : CodeEditor(context) {
    var onSelectionChangedCallback: (() -> Unit)? = null

    internal var releaseObserved = false
        private set

    override fun release() {
        releaseObserved = true
        super.release()
    }

    override fun onSelectionChanged(newPos: Int) {
        super.onSelectionChanged(newPos)
        onSelectionChangedCallback?.invoke()
    }
}

/**
 * 轻编辑器屏（规格 §5.3 屏 6）：文件 Tab + 代码编辑（TextMate HTML/CSS/JS 高亮）+
 * 选中浮出「问 AI 这段/修这段/复制」+ AI 输入条 + Agent 运行中只读横幅。
 * [editorSlot] 注入真实 [ZqCodeEditor] 或测试桩（同 RunnerScreen webView 模式）。
 */
@Composable
fun EditorScreen(
    controller: EditorController,
    onBack: () -> Unit,
    onAskAi: (EditorAskContext) -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.runtime.LaunchedEffect(Unit) { controller.open() }
    val state by controller.state.collectAsState()
    EditorContent(
        state = state,
        onBack = onBack,
        onSelectTab = controller::selectTab,
        onContentChange = controller::onContentChange,
        onSelection = controller::setSelection,
        onAiInput = controller::setAiInput,
        onAskAi = onAskAi,
        modifier = modifier,
        editorSlot = { m, path, content, readOnly, onContent, onSel ->
            RealEditorSlot(m, path, content, readOnly, onContent, onSel)
        },
    )
}

/** 纯渲染形态（测试注入编辑器桩）。 */
@Composable
fun EditorContent(
    state: EditorUiState,
    onBack: () -> Unit,
    onSelectTab: (Int) -> Unit,
    onContentChange: (String, String) -> Unit,
    onSelection: (String) -> Unit,
    onAiInput: (String) -> Unit,
    onAskAi: (EditorAskContext) -> Unit,
    modifier: Modifier = Modifier,
    editorSlot: @Composable (
        modifier: Modifier,
        path: String,
        content: String,
        readOnly: Boolean,
        onContentChange: (String) -> Unit,
        onSelection: (String) -> Unit,
    ) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column {
            // 顶栏
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("editor-back")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                Text("编辑器", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                state.activeFile?.let { Text(it.language, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary) }
            }

            if (state.agentRunning) {
                Text(
                    "Agent 会话运行中，编辑器只读",
                    color = Color(0xFFF59E0B),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0x1AF59E0B))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .testTag("editor-readonly-banner"),
                )
            }

            // 文件 Tab
            LazyRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(state.files, key = { it.path }) { file ->
                    FilterChip(
                        selected = state.activeFile?.path == file.path,
                        onClick = { onSelectTab(state.files.indexOfFirst { it.path == file.path }) },
                        label = { Text(file.path.substringAfterLast('/')) },
                        modifier = Modifier.testTag("editor-tab-${file.path}"),
                    )
                }
            }

            // 选中浮出操作条
            if (state.selectedText.isNotBlank()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AssistChip(
                        onClick = {
                            onAskAi(
                                EditorAskContext(
                                    question = "解释这段代码",
                                    selection = state.selectedText,
                                    language = state.activeFile?.language ?: "",
                                ),
                            )
                        },
                        label = { Text("问 AI 这段") },
                        modifier = Modifier.testTag("ask-ai-selection"),
                    )
                    AssistChip(
                        onClick = {
                            onAskAi(
                                EditorAskContext(
                                    question = "修复这段代码的问题",
                                    selection = state.selectedText,
                                    language = state.activeFile?.language ?: "",
                                ),
                            )
                        },
                        label = { Text("修这段") },
                        modifier = Modifier.testTag("fix-selection"),
                    )
                    AssistChip(
                        onClick = { clipboard.setText(AnnotatedString(state.selectedText)) },
                        label = { Text("复制") },
                        modifier = Modifier.testTag("copy-selection"),
                    )
                }
            }

            // 代码区
            val active = state.activeFile
            if (active != null) {
                editorSlot(
                    Modifier.weight(1f).fillMaxWidth().testTag("editor-code"),
                    active.path,
                    active.content,
                    state.agentRunning,
                    { onContentChange(active.path, it) },
                    onSelection,
                )
            } else {
                Column(
                    Modifier.weight(1f).fillMaxWidth().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("项目还没有可编辑的文件", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // AI 输入条
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OutlinedTextField(
                    value = state.aiInput,
                    onValueChange = onAiInput,
                    modifier = Modifier.weight(1f).testTag("editor-ai-input"),
                    placeholder = { Text(if (state.selectedText.isBlank()) "问点什么…" else "针对选中代码提问…") },
                    singleLine = true,
                )
                IconButton(
                    onClick = {
                        onAskAi(
                            EditorAskContext(
                                question = state.aiInput.ifBlank { "看这段代码" },
                                selection = state.selectedText,
                                language = state.activeFile?.language ?: "",
                            ),
                        )
                        onAiInput("")
                    },
                    enabled = state.activeFile != null,
                    modifier = Modifier.testTag("editor-ai-send"),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
                }
            }
        }
    }
}

/** 真实 sora-editor 互操作（AndroidView + TextMate 高亮 + 内容/选中透出；离开组合即 [CodeEditor.release]）。 */
@Composable
fun RealEditorSlot(
    modifier: Modifier,
    path: String,
    content: String,
    readOnly: Boolean,
    onContentChange: (String) -> Unit,
    onSelection: (String) -> Unit,
    onEditorCreated: (ZqCodeEditor) -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val editor = androidx.compose.runtime.remember(path) {
        ZqCodeEditor(context).apply {
            setEditorLanguage(EditorLanguages.create(context, path))
            EditorLanguages.colorScheme(context)?.let { setColorScheme(it) }
            isEditable = !readOnly
            subscribeEvent(ContentChangeEvent::class.java) { event, _ ->
                if (event.action != ContentChangeEvent.ACTION_SET_NEW_TEXT) {
                    onContentChange(text.toString())
                }
            }
            onSelectionChangedCallback = {
                val cursor = cursor
                onSelection(
                    if (cursor.isSelected) text.substring(cursor.left, cursor.right) else "",
                )
            }
        }.also(onEditorCreated)
    }
    androidx.compose.ui.viewinterop.AndroidView(
        modifier = modifier.background(Color(0xFF12131A), RoundedCornerShape(8.dp)),
        factory = { editor },
        update = { e ->
            if (e.text.toString() != content) {
                e.setText(content)
            }
            e.isEditable = !readOnly
        },
    )
    androidx.compose.runtime.DisposableEffect(path) {
        onDispose { editor.release() }
    }
}
