package com.zhique.runner

import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import com.zhique.core.project.ProjectMeta
import com.zhique.runner.home.HomeScreen
import com.zhique.runner.paste.PastePreviewController
import com.zhique.runner.paste.PastePreviewScreen
import com.zhique.runner.runner.RunnerScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 导航：首页 ↔ 运行器 ↔ 智能粘贴预览（M2）。
 * 粘贴入口三合一：首页剪贴板卡、系统分享目标（MainActivity.sharedText）、预览屏手动重跑。
 * meta 解析与管道 IO 均在 IO 协程，不在组合期做磁盘读（Important 6）。
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

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val meta = runnerProject
        when {
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
            )
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
            )
        }
    }
}
