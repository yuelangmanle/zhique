package com.zhique.runner

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import com.zhique.core.project.ProjectRepository
import com.zhique.runner.paste.PastePreferences
import java.io.File

/** 进程级依赖容器。 */
class AppContainer(context: Application) {
    val root: File = File(context.filesDir, "zhique-root")
    val repo: ProjectRepository = ProjectRepository(root)

    /** 粘贴偏好（DataStore，进程内单实例）。 */
    val pastePreferences: PastePreferences by lazy { PastePreferences.fromContext(context) }

    fun projectDir(projectId: String): File = File(root, "projects/$projectId")
}

class ZhiqueApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

/** 前台读剪贴板一次（回前台/显式刷新时调用，不做后台监听）。 */
fun readClipboardText(context: Context): String? = runCatching {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    cm?.primaryClip?.getItemAt(0)?.text?.toString()
}.getOrNull()
