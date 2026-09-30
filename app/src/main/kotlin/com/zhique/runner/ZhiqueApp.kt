package com.zhique.runner

import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.zhique.core.project.ProjectMeta
import com.zhique.runner.home.HomeScreen
import com.zhique.runner.runner.RunnerScreen

/**
 * M1 最小导航：首页 ↔ 运行器。底部三 Tab / 导出中心 / 设置在 M5+ 接入。
 */
@Composable
fun ZhiqueApp(container: AppContainer) {
    val context = LocalContext.current
    var runnerProjectId by rememberSaveable { mutableStateOf<String?>(null) }

    val toast: (String) -> Unit = { msg ->
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val meta: ProjectMeta? = runnerProjectId?.let {
            runCatching { container.repo.meta(it) }.getOrNull()
        }
        if (meta == null) {
            HomeScreen(
                repo = container.repo,
                onRun = { runnerProjectId = it.id },
                onToast = toast,
            )
        } else {
            RunnerScreen(
                project = meta,
                projectDir = container.projectDir(meta.id),
                onBack = { runnerProjectId = null },
                onToast = toast,
                onModePersist = { id, mode ->
                    runCatching { container.repo.setRunnerMode(id, mode.name.lowercase()) }
                },
            )
        }
    }
}
