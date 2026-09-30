package com.zhique.runner

import android.app.Application
import com.zhique.core.project.ProjectRepository
import java.io.File

/** 进程级依赖容器。 */
class AppContainer(context: Application) {
    val root: File = File(context.filesDir, "zhique-root")
    val repo: ProjectRepository = ProjectRepository(root)

    fun projectDir(projectId: String): File = File(root, "projects/$projectId")
}

class ZhiqueApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
