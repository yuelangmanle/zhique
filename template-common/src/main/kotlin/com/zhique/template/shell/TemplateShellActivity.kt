package com.zhique.template.shell

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.zhique.core.permission.PermissionRegistry
import com.zhique.core.permission.zq.ZqBluetooth
import com.zhique.core.permission.zq.ZqCamera
import com.zhique.core.permission.zq.ZqClipboard
import com.zhique.core.permission.zq.ZqDispatcher
import com.zhique.core.permission.zq.ZqEnv
import com.zhique.core.permission.zq.ZqFile
import com.zhique.core.permission.zq.ZqLocation
import com.zhique.core.permission.zq.ZqMic
import com.zhique.core.permission.zq.ZqNotify
import com.zhique.core.permission.zq.ZqScreen
import com.zhique.core.permission.zq.ZqSensor
import com.zhique.core.permission.zq.ZqShare
import com.zhique.core.project.ProjectMeta
import com.zhique.core.project.ProjectRepository
import com.zhique.core.web.WebViewHost
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 模板壳基类（M6 Task 6.1）：启动全屏加载项目页；壳内授权卡与织雀同体验；
 * 无编辑器/无 AI/无粘贴。子类声明变体与能力注册（min=零能力；full=全量桥）。
 *
 * 项目文件来源：APK `assets/project/`（导出时由 AssetInjector 注入），每次启动
 * 解包到 filesDir/shell-root/projects/<id>/ ——代码资源覆盖更新，project.json
 * 不覆盖（授权矩阵/使用计数跨版本存续，覆盖安装数据保留）。
 */
abstract class TemplateShellActivity : ComponentActivity() {

    /** 变体标记（min | full）。 */
    protected abstract fun variant(): String

    /** 注册本变体暴露的 zq 能力（min：零能力；full：全量）。 */
    protected abstract fun registerCapabilities(dispatcher: ZqDispatcher, env: ZqEnv)

    private lateinit var root: FrameLayout
    private lateinit var host: WebViewHost
    private var dispatcher: ZqDispatcher? = null
    private val prompt = TemplatePermissionPrompt()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loading: View? = null
    private var permissionCard: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = ProjectRepository(File(filesDir, "shell-root"))
        val meta = ensureProject(repo)
        applyBrand(meta)
        installRuntime(repo, meta)
        showLoading()
    }

    private fun installRuntime(repo: ProjectRepository, meta: ProjectMeta) {
        val full = variant() == "full"
        val projection = if (full) TemplateProjectionGateway(this) else null
        val saf = if (full) TemplateSafGateway(this) else null
        val registry = PermissionRegistry(repo, prompt = prompt)
        host = WebViewHost(this, TemplateProjectSource(assets))
        val env = TemplateZqWiring.install(
            host = host,
            projectId = meta.id,
            activity = this,
            scope = lifecycleScope,
            registry = registry,
            projection = projection,
            saf = saf,
        )
        val d = ZqDispatcher(env)
        registerCapabilities(d, env)
        d.attachTo(host.zqRouter)
        dispatcher = d

        host.onCapabilityDetected = { hideLoading() }
        host.onCrashGiveUp = { showGiveUp() }
        root = FrameLayout(this).apply { setBackgroundColor(0xFF12131A.toInt()) }
        root.addView(
            host.webView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
        setContentView(root)
        // 壳内授权卡：与织雀同体验（谁的项目、要什么、干什么、授予/拒绝）
        scope.launch {
            prompt.current.collect { pending ->
                permissionCard?.let { (it.parent as? ViewGroup)?.let { p -> p.removeView(it) } }
                permissionCard = pending?.let { TemplatePermissionCard.show(root, prompt, it) }
            }
        }
    }

    /** 项目元数据：本地 project.json 保留（授权状态跨版本），缺失时从注入资产补建。 */
    private fun ensureProject(repo: ProjectRepository): ProjectMeta {
        val idFile = File(filesDir, "shell-root/current-id")
        val existingId = idFile.takeIf { it.isFile }?.readText()?.trim()
        if (!existingId.isNullOrBlank()) {
            runCatching { repo.meta(existingId) }.getOrNull()?.let {
                extractProjectAssets(repo, existingId)
                return it
            }
        }
        val created = repo.create(PROJECT_NAME_FALLBACK, "")
        idFile.parentFile?.mkdirs()
        idFile.writeText(created.id)
        extractProjectAssets(repo, created.id)
        // 注入资产带 project.json（导出向导写入的应用名/图标色）→ 取名与配色
        runCatching {
            val raw = assets.open("$ASSET_ROOT/$META_FILE").bufferedReader().use { it.readText() }
            val injected = json.decodeFromString(ProjectMeta.serializer(), raw)
            repo.rename(created.id, injected.name)
        }
        return repo.meta(created.id)
    }

    /** 解包 assets/project 全部文件到项目目录；跳过 project.json（本地授权状态不覆盖）。 */
    private fun extractProjectAssets(repo: ProjectRepository, id: String) {
        extractDir(ASSET_ROOT, repo.projectDir(id))
    }

    private fun extractDir(assetPath: String, target: File) {
        val children = runCatching { assets.list(assetPath) ?: emptyArray() }.getOrDefault(emptyArray())
        target.mkdirs()
        for (child in children) {
            val rel = "$assetPath/$child"
            val out = File(target, child)
            if (child == META_FILE) continue // 本地矩阵优先，见类注释
            val sub = runCatching { assets.list(rel) ?: emptyArray() }.getOrDefault(emptyArray())
            if (sub.isNotEmpty()) {
                extractDir(rel, out)
            } else {
                runCatching { assets.open(rel).use { i -> out.outputStream().use { i.copyTo(it) } } }
            }
        }
    }

    /** 品牌注入：应用名/图标色（导出向导步①写入注入 project.json），壳窗口着色。 */
    private fun applyBrand(meta: ProjectMeta) {
        title = meta.name
        val color = runCatching { Color.parseColor(meta.iconColor) }.getOrDefault(0xFF46509F.toInt())
        window.statusBarColor = color
        window.navigationBarColor = color
        setTaskDescription(android.app.ActivityManager.TaskDescription(meta.name))
    }

    private fun showLoading() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xFF12131A.toInt())
            addView(ProgressBar(this@TemplateShellActivity))
            addView(
                TextView(this@TemplateShellActivity).apply {
                    text = "加载中…"
                    setTextColor(0xFFF2F3F7.toInt())
                    gravity = Gravity.CENTER
                    setPadding(0, TemplatePermissionCard.dp(context, 12), 0, 0)
                },
            )
        }
        loading = box
        addContentView(
            box,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
    }

    private fun hideLoading() {
        loading?.let { (it.parent as? ViewGroup)?.removeView(it) }
        loading = null
    }

    private fun showGiveUp() {
        hideLoading()
        addContentView(
            FrameLayout(this).apply {
                setBackgroundColor(0xFF12131A.toInt())
                addView(
                    TextView(this@TemplateShellActivity).apply {
                        text = "页面运行时多次崩溃，已停止自动恢复。\n请重启应用。"
                        setTextColor(0xFFF2F3F7.toInt())
                        gravity = Gravity.CENTER
                    },
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER,
                    ),
                )
            },
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
    }

    override fun onResume() {
        super.onResume()
        if (::host.isInitialized) host.resume()
    }

    override fun onPause() {
        if (::host.isInitialized) host.pause()
        super.onPause()
    }

    override fun onDestroy() {
        scope.cancel()
        dispatcher?.shutdown()
        if (::host.isInitialized) host.destroy()
        super.onDestroy()
    }

    companion object {
        const val ASSET_ROOT = "project"
        const val PROJECT_NAME_FALLBACK = "应用"
        const val META_FILE = "project.json"

        private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    }
}

/** min 变体壳：仅网络常规声明，无任何 zq 能力（zq 调用按「未注册能力」优雅拒绝）。 */
class TemplateShellMin : TemplateShellActivity() {
    override fun variant() = "min"
    override fun registerCapabilities(dispatcher: ZqDispatcher, env: ZqEnv) = Unit
}

/** full 变体壳：全量桥权限（十能力；W3C/OS/SAF/投影网关由 wiring 注入）。 */
class TemplateShellFull : TemplateShellActivity() {
    override fun variant() = "full"
    override fun registerCapabilities(dispatcher: ZqDispatcher, env: ZqEnv) {
        dispatcher.register(ZqCamera())
        dispatcher.register(ZqMic())
        dispatcher.register(ZqFile())
        dispatcher.register(ZqLocation())
        dispatcher.register(ZqSensor())
        dispatcher.register(ZqBluetooth())
        dispatcher.register(ZqNotify())
        dispatcher.register(ZqClipboard())
        dispatcher.register(ZqShare())
        dispatcher.register(ZqScreen())
    }
}
