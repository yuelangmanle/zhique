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

    /** 发布偏好（更新通道 stable/beta，与设置域共用 DataStore）。 */
    val publishPreferences: com.zhique.runner.settings.PublishPreferences by lazy {
        com.zhique.runner.settings.PublishPreferences(settingsDataStore)
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
        )
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
}

/** 前台读剪贴板一次（回前台/显式刷新时调用，不做后台监听）。 */
fun readClipboardText(context: Context): String? = runCatching {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    cm?.primaryClip?.getItemAt(0)?.text?.toString()
}.getOrNull()
