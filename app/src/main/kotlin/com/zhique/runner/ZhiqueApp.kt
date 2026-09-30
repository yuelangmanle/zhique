package com.zhique.runner

import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.zhique.runner.runner.RunnerScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * M1 最小导航：首页 ↔ 运行器。底部三 Tab / 导出中心 / 设置在 M5+ 接入。
 * project meta 解析走 IO 协程，不在组合期做磁盘读（Important 6）。
 */
@Composable
fun ZhiqueApp(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingProjectId by rememberSaveable { mutableStateOf<String?>(null) }
    var runnerProject by remember { mutableStateOf<ProjectMeta?>(null) }

    val toast: (String) -> Unit = { msg ->
        scope.launch { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
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
        if (meta == null) {
            HomeScreen(
                repo = container.repo,
                onRun = {
                    runnerProject = null
                    pendingProjectId = it.id
                },
                onToast = toast,
            )
        } else {
            RunnerScreen(
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
        }
    }
}
