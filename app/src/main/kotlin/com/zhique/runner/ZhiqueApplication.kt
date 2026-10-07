package com.zhique.runner

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.zhique.core.common.crypto.CryptoStore
import com.zhique.core.project.ProjectRepository
import com.zhique.runner.paste.PastePreferences
import com.zhique.runner.settings.AndroidKeystoreProvider
import com.zhique.runner.settings.ProviderStore
import com.zhique.runner.settings.RoleBindingStore
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 进程级依赖容器。密钥主密钥来源可注入（测试用软件 KeyProvider，生产走 AndroidKeystore）。 */
class AppContainer(
    context: Application,
    keyProvider: com.zhique.core.common.crypto.KeyProvider = AndroidKeystoreProvider(),
) {
    val root: File = File(context.filesDir, "zhique-root")
    val repo: ProjectRepository = ProjectRepository(root)

    /** 粘贴偏好（DataStore，进程内单实例）。 */
    val pastePreferences: PastePreferences by lazy { PastePreferences.fromContext(context) }

    /** 设置域共享 DataStore（Provider/角色绑定/引导/用量，进程内单实例）。 */
    private val settingsDataStore: DataStore<Preferences> by lazy {
        val dir = File(context.filesDir, "datastore").apply { mkdirs() }
        PreferenceDataStoreFactory.create(produceFile = { File(dir, "settings.preferences_pb") })
    }

    /** Provider 配置仓：Key 经主密钥加密（规格 §4.4）。 */
    val providerStore: ProviderStore by lazy {
        ProviderStore(settingsDataStore, CryptoStore(keyProvider))
    }

    val roleBindingStore: RoleBindingStore by lazy { RoleBindingStore(settingsDataStore) }
    val usageMeter: com.zhique.core.ai.UsageMeter by lazy {
        com.zhique.core.ai.UsageMeter(com.zhique.runner.settings.DataStoreUsageStore(settingsDataStore))
    }
    val onboardingPrefs: com.zhique.runner.onboarding.OnboardingPreferences by lazy {
        com.zhique.runner.onboarding.OnboardingPreferences(settingsDataStore)
    }

    // ---- M8 互联（Apilot 双向流转） ----

    /** 桥接审计记录（不含 Key/payload，设置里可清）。 */
    val apilotAudit: com.zhique.core.apilot.ApilotAuditStore by lazy {
        com.zhique.core.apilot.ApilotAuditStore(File(File(context.filesDir, "apilot"), "audit.jsonl"))
    }

    /** 上次接入/推送时间（与设置域共用 DataStore）。 */
    val apilotSync: com.zhique.runner.settings.ApilotSyncStore by lazy {
        com.zhique.runner.settings.ApilotSyncStore(settingsDataStore)
    }

    private val appScope: kotlinx.coroutines.CoroutineScope by lazy {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
    }

    /**
     * Apilot 双向流转控制器（进程级单点）。
     * 大负载 URI 通道走 zqfile FileProvider 的 apilot/ 缓存目录（一次性 content URI，
     * 同步结束后由 tempFileCleanup 兜底删除）。
     */
    val apilotController: com.zhique.runner.settings.ApilotController by lazy {
        val appContext = context
        com.zhique.runner.settings.ApilotController(
            store = providerStore,
            audit = apilotAudit,
            sync = apilotSync,
            checkInstalled = {
                com.zhique.core.apilot.ApilotBridge.isInstalled(appContext)
            },
            signatureProvider = {
                com.zhique.core.apilot.ApilotBridge().ownSignatureSha256(appContext)
            },
            uriProvider = { json -> apilotPayloadUri(appContext, json) },
            tempFileCleanup = { apilotCacheDir(appContext).deleteRecursively() },
            selfPackageName = context.packageName,
            scope = appScope,
        )
    }

    private fun apilotCacheDir(appContext: android.content.Context): File = File(appContext.cacheDir, "apilot")

    /** 把导入 payload 落成一次性只读 content URI（Apilot 10 分钟后删缓存，本方同步结束后亦清理）。 */
    private fun apilotPayloadUri(appContext: android.content.Context, json: String): android.net.Uri {
        val dir = apilotCacheDir(appContext).apply { mkdirs() }
        // 清掉上一轮残留（上次授权的临时文件）
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "payload-${System.currentTimeMillis()}.json").apply { writeText(json) }
        return androidx.core.content.FileProvider.getUriForFile(appContext, "${appContext.packageName}.zqfile", file)
    }

    /** 授权卡状态持有者（PermissionPrompt 的 :app 实现，进程级单实例）。 */
    val permissionPrompt: com.zhique.runner.permission.AppPermissionPrompt by lazy {
        com.zhique.runner.permission.AppPermissionPrompt()
    }

    /** 权限注册表（M5 权限桥：矩阵状态持久化在 project.json）。 */
    val permissionRegistry: com.zhique.core.permission.PermissionRegistry by lazy {
        com.zhique.core.permission.PermissionRegistry(repo, prompt = permissionPrompt)
    }

    // ---- M6 导出与签名 ----

    /** 统一签名密钥库（决策29：一次生成永久复用；口令随机密文存安全区）。 */
    val keystoreManager: com.zhique.core.export.KeystoreManager by lazy {
        com.zhique.core.export.KeystoreManager(File(context.filesDir, "export"), CryptoStore(keyProvider))
    }

    /** 模板底版（assets/templates 解包缓存）。 */
    private val templateProvider: com.zhique.core.export.TemplateProvider by lazy {
        com.zhique.core.export.AssetTemplateProvider(context, File(context.cacheDir, "templates"))
    }

    /** 导出编排（校验 → 包名/版本 → 注入 → 签名 → 记录）。 */
    val exportService: com.zhique.core.export.ExportService by lazy {
        com.zhique.core.export.ExportService(
            repo = repo,
            version = com.zhique.core.export.VersionManager(repo),
            pipeline = com.zhique.core.export.ExportPipeline(
                templates = templateProvider,
                signer = com.zhique.core.export.Signer(),
                workDir = File(context.cacheDir, "exports"),
            ),
            keystore = keystoreManager,
            guard = com.zhique.core.export.SignatureGuard(context, keystoreManager, repo),
        )
    }

    // ---- M7 发布（GitHub 底座 + 状态机 + 自更新） ----

    /** fine-grained PAT 加密存储（CryptoStore，进程级单点）。 */
    val patStore: com.zhique.core.publish.PatStore by lazy {
        com.zhique.core.publish.PatStore(File(root, "publish/pat.enc"), CryptoStore(keyProvider))
    }

    /** 发布偏好（更新通道 stable/beta + 推送偏好，与设置域共用 DataStore）。 */
    val publishPreferences: com.zhique.runner.settings.PublishPreferences by lazy {
        com.zhique.runner.settings.PublishPreferences(settingsDataStore)
    }

    // ---- M9 设置全集 ----

    /** 输出·思考·上下文设置（规格 §7 AI 服务商子节点）。 */
    val aiPreferences: com.zhique.runner.settings.AiPreferences by lazy {
        com.zhique.runner.settings.AiPreferences(settingsDataStore)
    }

    /** Web 设置（桌面 UA/下载行为/eruda 开关）。 */
    val webPreferences: com.zhique.runner.settings.WebPreferences by lazy {
        com.zhique.runner.settings.WebPreferences(settingsDataStore)
    }

    /** 通用设置（外观/运行器/编辑器/通知三事件开关）。 */
    val generalPreferences: com.zhique.runner.settings.GeneralPreferences by lazy {
        com.zhique.runner.settings.GeneralPreferences(settingsDataStore)
    }

    /** 隐私与安全存储（应用锁 PIN 哈希/隐私告知记录）。 */
    val privacyPreferences: com.zhique.runner.settings.PrivacyPreferences by lazy {
        com.zhique.runner.settings.PrivacyPreferences(settingsDataStore)
    }

    /** 应用锁控制器（进程级单点；MainActivity/ZhiqueApp 接 onResume/onStop）。 */
    val appLockController: com.zhique.runner.settings.AppLockController by lazy {
        com.zhique.runner.settings.AppLockController(privacyPreferences, appScope)
    }

    /**
     * 通知三事件触发缝（M9）：先查通用设置的三枚开关，再落系统通知。
     * 通知权限未授予/渠道缺失时内部静默跳过。
     */
    val eventNotifier: com.zhique.runner.notify.EventNotifier by lazy {
        val appContext = context
        val general = generalPreferences
        val generalScope = appScope
        { channel: String, title: String, body: String ->
            generalScope.launch {
                val allowed = when (channel) {
                    com.zhique.runner.notify.ZhiqueNotifications.CHANNEL_EXPORT_DONE ->
                        general.notifyExportDone.first()
                    com.zhique.runner.notify.ZhiqueNotifications.CHANNEL_AGENT_DONE ->
                        general.notifyAgentDone.first()
                    com.zhique.runner.notify.ZhiqueNotifications.CHANNEL_NEW_VERSION ->
                        general.notifyNewVersion.first()
                    else -> false
                }
                if (allowed) {
                    com.zhique.runner.notify.ZhiqueNotifications.notify(
                        appContext, channel, title, body,
                        notificationId = (title.hashCode() to body.hashCode()).hashCode(),
                    )
                }
            }
        }
    }

    /** Git 底座（JGit）与 GitHub REST（OkHttp，PAT 零日志）。 */
    val gitRepo: com.zhique.core.publish.GitRepo by lazy { com.zhique.core.publish.GitRepo() }
    val githubApi: com.zhique.core.publish.GitHubApi by lazy { com.zhique.core.publish.GitHubApi() }

    /** 发布状态机引擎（跨模式唯一：手动向导/Agent 工具/导出中心一键共用）。 */
    val releaseJobEngine: com.zhique.core.publish.ReleaseJobEngine by lazy {
        com.zhique.core.publish.ReleaseJobEngine(repo, gitRepo, githubApi, pats = patStore)
    }

    /** Agent git 工具真实实现（GitTools.bind 进程级单点，仍走 ConfirmGate 批准）。 */
    val publishToolGateway: com.zhique.runner.publish.PublishToolGateway by lazy {
        com.zhique.runner.publish.PublishToolGateway(
            repo = repo,
            git = { gitRepo },
            api = { githubApi },
            pats = { patStore },
            engine = { releaseJobEngine },
            apkResolver = { id -> exportedApk(id) },
        )
    }

    /** 导出管线工作目录（Release 附件按 包名-版本码 定位最新导出 APK）。 */
    val exportWorkDir: File = File(context.cacheDir, "exports")

    /** 调试后端（本机回环 HTTP）+ 其开关（设置→开发者）。 */
    val debugServer = com.zhique.core.telemetry.DebugServer()
    val debugPreferences = com.zhique.runner.settings.DebugPreferences(settingsDataStore)

    /** 服务商模型目录（拉一次全局可用：角色路由/对话的快捷选模型消费）。 */
    val modelCatalog: com.zhique.runner.settings.ProviderModelCatalog by lazy {
        com.zhique.runner.settings.ProviderModelCatalog(File(context.filesDir, "model_catalog.json"))
    }
    val modelListFetcher = com.zhique.core.ai.ModelListFetcher()

    /** 项目最新一次导出的 APK（无导出记录或文件已被系统清理→null）。 */
    fun exportedApk(projectId: String): File? {
        val record = runCatching { repo.meta(projectId) }.getOrNull()?.export ?: return null
        return File(exportWorkDir, "${record.packageName}-${record.versionCode}.apk").takeIf { it.isFile }
    }

    init {
        // M7：装配即绑定——git 工具从 NotReady 占位切到真实实现
        // （gateway 本体惰性创建，绑定引用不触发 JGit/OkHttp 初始化）
        com.zhique.core.agent.tools.GitTools.bind(publishToolGateway)
    }

    fun projectDir(projectId: String): File = File(root, "projects/$projectId")
}

class ZhiqueApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    private var debugServerWired = false

    override fun onCreate() {
        super.onCreate()
        // M9：通知三事件渠道注册（幂等）
        com.zhique.runner.notify.ZhiqueNotifications.ensureChannels(this)
        // 调试中枢初始化（只装内存/文件汇，不碰 DataStore——测试环境会多容器）
        val debuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName ?: "?" }.getOrDefault("?")
        com.zhique.core.telemetry.DebugHub.init(
            appVersion = version,
            device = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (api ${android.os.Build.VERSION.SDK_INT})",
            sinkDir = java.io.File(filesDir, "debug"),
            sinkEnabled = debuggable,
        )
    }

    /**
     * 本机调试后端一次性接线（MainActivity.onCreate 调，Application 级单次）：
     * 开关存 DataStore（AppContainer.debugPreferences），默认跟随构建类型。
     */
    fun startDebugBackendOnce() {
        if (debugServerWired) return
        debugServerWired = true
        val debuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        container.debugServer.onToast = { msg ->
            runCatching { android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show() }
        }
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val on = runCatching {
                container.debugPreferences.serverEnabled(debuggable).first()
            }.getOrDefault(debuggable)
            if (on) container.debugServer.start()
        }
    }
}

/** 前台读剪贴板一次（回前台/显式刷新时调用，不做后台监听）。 */
fun readClipboardText(context: Context): String? = runCatching {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    cm?.primaryClip?.getItemAt(0)?.text?.toString()
}.getOrNull()
