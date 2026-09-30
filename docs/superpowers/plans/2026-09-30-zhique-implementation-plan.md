# 织雀（Zhique）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建安卓 HTML 运行器 + AI 编程助手「织雀」：粘贴即跑、Agent 自写自调、权限全桥接、端上打包 APK、GitHub 发布，完整实现 `docs/superpowers/specs/2026-09-30-zhique-design.md`。

**Architecture:** 原生 Kotlin + Jetpack Compose + 系统 WebView（独立 `:web` 进程）；Gradle 多模块（`:app` + 9 个 `:core:*` 纯库）；核心逻辑全部下沉纯 JVM 模块保证可测；AI 层三协议适配 + 角色路由；Agent 薄编排层自研（~2k 行）。

**Tech Stack:** Kotlin 2.2 / AGP 8.13 / Compose BOM / kotlinx-serialization + coroutines / OkHttp(SSE) / sora-editor 0.24 / JGit 7 / apksig + zipflinger / androidx.webkit(WebViewAssetLoader) / JUnit4 + kotlin.test + mockwebserver + Robolectric + Turbine。

**执行环境：** 本仓库为 ZCode 工作区。构建/装机/截图用 `android-emulator` 插件（`android_build_and_run` 等）；纯 JVM 模块用 `./gradlew :core:xxx:test`。规格中的全局原则：**不设周期约束，体验/能力/稳定最优**。

**版本目录（M0 首次 sync 时校验并 pin 最新稳定；不一致只改 `gradle/libs.versions.toml` 一处）：**

```toml
[versions]
agp = "8.13.0"
kotlin = "2.2.20"
compose-bom = "2026.09.00"
coroutines = "1.10.2"
serialization = "1.9.0"
okhttp = "5.1.0"
sora-editor = "0.24.0"
jgit = "7.3.0"
apksig = "8.13.0"
zipflinger = "9.13.0"
webkit = "1.14.0"
datastore = "1.1.7"
junit = "4.13.2"
robolectric = "4.15"
turbine = "1.2.1"
mockwebserver = "5.1.0"
biometric = "1.2.0-alpha05"
camerax = "1.4.2"
```

**约定：** 包名 `com.zhique.runner`，core 包 `com.zhique.core.<module>`；commit 用 conventional commits（feat/test/chore/docs）；每任务一 commit；所有纯逻辑先写失败测试再实现（TDD）；`minSdk 31 / targetSdk 36 / compileSdk 36`。

---

## 文件结构总图（模块 → 职责 → 关键文件）

```
settings.gradle.kts / gradle/libs.versions.toml / build.gradle.kts
:app                           # UI 组装、导航、所有 Compose 屏幕
  src/main/kotlin/com/zhique/runner/
    ui/{theme,components}/     # Aurora Glass 体系（M9 前置最小版在 M1 建立）
    home/ paste/ runner/ editor/ agent/ chat/ export/ publish/ settings/ onboarding/
    MainActivity.kz  ZhiqueApp.kt  Nav.kt
  src/androidTest/             # Compose UI 测试
:core:common    # CryptoStore、TokenEstimator、ResultX、时间工具
:core:project   # ProjectRepository、ProjectMeta(project.json)、HistoryStore、ZipIO
:core:paste     # PasteClassifier、Assembler、Cleaner、PromptBridge
:core:web       # RunnerWebHost（Activity 壳）、BridgeJs(assets/zhique-bridge.js)、
                # CollectorBridge、TimelineReducer、AssetServer、CapabilityDetect
:core:ai        # Provider 接口、三协议适配器、SSE 解析、TruncationContinuer、
                # ModelCatalog(modality/maxOutput/contextWindow)、RoleRouter、UsageMeter
:core:agent     # Orchestrator、ToolRegistry、Tools、三层记忆(Memory/ContextAssembler/
                # Compactor)、Budget、SnapshotStore、AuditLog、SessionStore
:core:permission# PermissionRegistry(状态机)、Capability 定义、zq 协议、导出权限建议
:core:export    # TemplateApk(变体)、AssetInjector(zipflinger)、Signer(apksig)、
                # KeystoreManager、VersionManager、Installer、Shortcut
:core:publish   # GitRepo(JGit)、GitHubApi、ReleaseJob(状态机)、UpdateChecker
:core:apilot    # ApilotBridge(Intent V2)、ProfileMapper
:template-min / :template-full  # 导出壳 App 模块（产物 apk 作为 :core:export 资产）
```

---

# M0 地基（脚手架 + 加密存储 + 项目仓库）

### Task 0.1 仓库脚手架与版本目录

**Files:** Create `settings.gradle.kts`、`gradle/libs.versions.toml`、根 `build.gradle.kts`、`gradle.properties`、各模块 `build.gradle.kts` 骨架、`.github/workflows/ci.yml`

- [ ] **Step 1: 写 settings.gradle.kts**

```kotlin
pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositories { google(); mavenCentral(); maven("https://jitpack.io") }
}
rootProject.name = "zhique"
include(":app", ":core:common", ":core:project", ":core:paste", ":core:web",
        ":core:ai", ":core:agent", ":core:permission", ":core:export",
        ":core:publish", ":core:apilot", ":template-min", ":template-full")
```

- [ ] **Step 2: 写版本目录**（用上文 Versions 块；补充 `[libraries]`：`androidx-core-ktx`、`compose-bom`、`androidx-activity-compose`、`androidx-lifecycle-runtime-compose`、`kotlinx-coroutines-core/test`、`kotlinx-serialization-json`、`okhttp`、`okhttp-mockwebserver`、`sora-editor`（`io.github.Rosemoe.sora-editor:editor`、`language-textmate`）、`jgit`（`org.eclipse.jgit:org.eclipse.jgit`）、`apksig`（`com.android.tools.build:apksig`）、`zipflinger`（`com.android:zipflinger`）、`androidx-webkit`、`junit`、`robolectric`、`turbine`）
- [ ] **Step 3: 根 build.gradle.kts** 声明 agp/kotlin/serialization 插件 apply false；`gradle.properties`：`android.useAndroidX=true`、`org.gradle.jvmargs=-Xmx4g`、`kotlin.code.style=official`
- [ ] **Step 4: 各 core 模块骨架**——纯 JVM 模块（common/project/paste）用 `java-library` + kotlin("jvm")；Android 库模块（web/ai/agent/permission/export/publish/apilot）用 `com.android.library` + `kotlin("android")`，`android { namespace=…; compileSdk 36; defaultConfig{minSdk 31} }`，依赖 `core:common`；`:app` 与 `:template-*` 为 application 模块（applicationId 分别 `com.zhique.runner` / `com.zhique.export.min` / `com.zhique.export.full`）
- [ ] **Step 5: CI**——`.github/workflows/ci.yml`：JDK 17 + `./gradlew build`，push/PR 触发
- [ ] **Step 6: Run** `./gradlew projects` → Expected: 13 个模块列出，BUILD SUCCESSFUL
- [ ] **Step 7: Commit** `chore: 多模块脚手架与版本目录`

### Task 0.2 CryptoStore（AES-GCM + Keystore 主密钥）

**Files:** Create `:core:common/.../crypto/CryptoStore.kt`；Test `:core:common/.../crypto/CryptoStoreTest.kt`

- [ ] **Step 1: 失败测试**（KeyProvider 接口抽象 Android Keystore，纯 JVM 可测）

```kotlin
class CryptoStoreTest {
    class FakeKeyProvider(val key: SecretKey) : KeyProvider {
        override fun masterKey(): SecretKey = key
    }
    @Test fun `加密后解密还原`() {
        val kp = FakeKeyProvider(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())
        val store = CryptoStore(kp)
        val ct = store.encrypt("sk-abc-123")
        assertNotEquals("sk-abc-123", ct)
        assertEquals("sk-abc-123", store.decrypt(ct))
    }
    @Test fun `同一明文两次加密密文不同`() {
        val store = CryptoStore(FakeKeyProvider(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()))
        assertNotEquals(store.encrypt("x"), store.encrypt("x"))
    }
}
```

- [ ] **Step 2: Run** `./gradlew :core:common:test` → FAIL（CryptoStore 未定义）
- [ ] **Step 3: 实现**

```kotlin
interface KeyProvider { fun masterKey(): SecretKey }

class AndroidKeystoreProvider(private val alias: String = "zhique-master") : KeyProvider {
    override fun masterKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return gen.generateKey()
    }
}

class CryptoStore(private val kp: KeyProvider) {
    private val c = Cipher.getInstance("AES/GCM/NoPadding")
    fun encrypt(plain: String): String {
        c.init(Cipher.ENCRYPT_MODE, kp.masterKey())
        val iv = c.iv; val ct = c.doFinal(plain.toByteArray())
        return Base64.encodeToString(iv + ct, Base64.NO_WRAP)
    }
    fun decrypt(encoded: String): String {
        val all = Base64.decode(encoded, Base64.NO_WRAP)
        c.init(Cipher.DECRYPT_MODE, kp.masterKey(), GCMParameterSpec(128, all.copyOfRange(0, 12)))
        return String(c.doFinal(all.copyOfRange(12, all.size)))
    }
}
```

- [ ] **Step 4: Run** → PASS；**Step 5: Commit** `feat(core:common): CryptoStore AES-GCM`

### Task 0.3 ProjectMeta 与 ProjectRepository

**Files:** Create `:core:project/.../ProjectMeta.kt`、`ProjectRepository.kt`、`ZipIO.kt`；Test `ProjectRepositoryTest.kt`

- [ ] **Step 1: 数据模型**（kotlinx-serialization；字段对齐规格 §3.5，**完整无缺**）

```kotlin
@Serializable
data class PermissionRecord(val capability: String, val state: String, val lastAsked: Long = 0)
@Serializable
data class ExportRecord(val packageName: String, val versionCode: Int, val versionName: String, val at: Long, val variant: String, val certSha256: String = "")
@Serializable
data class RepoBinding(val owner: String, val repo: String, val branch: String = "main", val lastPushedSha: String? = null)
@Serializable
data class ProjectMeta(
    val id: String,                   // uuid
    var name: String,
    var group: String = "",           // 文件夹分组
    var runnerMode: String = "drawer",// drawer | split | bubble
    val permissions: MutableMap<String, PermissionRecord> = mutableMapOf(), // capability->record
    val permissionUsage: MutableMap<String, Int> = mutableMapOf(),          // 运行期真实使用计数
    var export: ExportRecord? = null,
    var repo: RepoBinding? = null,
    var providerOverride: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = createdAt,
)
```

- [ ] **Step 2: 失败测试**（临时目录；覆盖：create→list、重命名、移动分组、**整项目复制**（id 重生成、name 加"副本"）、删除（history 非空需 confirm 参数）、**exportZip/importZip 往返**）

```kotlin
class ProjectRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var repo: ProjectRepository
    @Before fun setup() { repo = ProjectRepository(tmp.newFolder()) }
    @Test fun `复制项目生成新id并深拷文件`() {
        val a = repo.create("A", "<html><body>hi</body></html>")
        repo.writeFile(a.id, "style.css", "body{}")
        val b = repo.copy(a.id)
        assertNotEquals(a.id, b.id); assertEquals("A 副本", b.name)
        assertEquals("body{}", repo.readFile(b.id, "style.css"))
    }
    @Test fun `zip导出导入往返保真`() {
        val a = repo.create("Z", "<html/>"); repo.writeFile(a.id, "js/app.js", "1")
        val f = repo.exportZip(a.id)
        val imported = repo.importZip(f)
        assertEquals("Z", imported.name.replaceFirst(" 副本$",""))
        assertEquals("1", repo.readFile(imported.id, "js/app.js"))
    }
    @Test fun `删除有history的项目必须显式confirm`() {
        val a = repo.create("H", "<html/>"); repo.appendHistory(a.id, "snap-v1", "...")
        assertFailsWith<IllegalStateException> { repo.delete(a.id, confirm = false) }
        repo.delete(a.id, confirm = true); assertTrue(repo.list().isEmpty())
    }
}
```

- [ ] **Step 3: Run** → FAIL；**Step 4: 实现** `ProjectRepository`（根目录构造；CRUD 直写 `project.json`；zip 用 `java.util.zip`，导出含 `meta+files`，导入解包到新 id 目录并剥离旧 id）
- [ ] **Step 5: Run** → PASS；**Step 6: Commit** `feat(core:project): 项目仓库与zip导入导出`

### Task 0.4 HistoryStore（版本快照 + 审计）

**Files:** Create `:core:project/.../HistoryStore.kt`；Test `HistoryStoreTest.kt`
- [ ] Step1 失败测试：`append(projectId,label,content)` 落 `history/snap-<n>-<label>.html` + index.json；`list/restore`；审计 `appendAudit(projectId, entry: AuditEntry)` 追加 jsonl、`readAudit` 分页。Step2 Run FAIL。Step3 实现（jsonl 逐行 JSON）。Step4 Run PASS。Step5 Commit `feat(core:project): 快照与审计存储`

---

# M1 能跑（WebView 运行时 + 调试采集 + 三模式运行器）

### Task 1.1 织雀桥 JS（采集器 + zq 骨架）

**Files:** Create `:core:web/src/main/assets/zhique-bridge.js`；Test（JS 行为由 1.2/1.3 的 reducer 与真机验收覆盖）

- [ ] **Step 1: 完整实现**（注入时机：`onPageStarted` 注入；要点全列）

```javascript
(function () {
  if (window.__ZHIQUE__) return; const Z = window.__ZHIQUE__ = { events: [], seq: 0 };
  function post(type, payload) {
    const e = { seq: ++Z.seq, t: Date.now(), type, ...payload };
    Z.events.push(e); if (Z.events.length > 500) Z.events.shift();
    if (window.ZhiqueNative && ZhiqueNative.onEvent) ZhiqueNative.onEvent(JSON.stringify(e));
  }
  ['log','info','warn','error','debug'].forEach(level => {
    const orig = console[level].bind(console);
    console[level] = function (...a) {
      post('console', { level, text: a.map(x => { try { return typeof x === 'object' ? JSON.stringify(x) : String(x); } catch { return String(x); } }).join(' ') });
      orig(...a);
    };
  });
  window.addEventListener('error', e => e.error ? post('js_error', { message: e.message, line: e.lineno, col: e.colno, stack: String(e.error.stack || '').slice(0, 2000) }) : post('resource_error', { url: e.target && e.target.src || '' }));
  window.addEventListener('unhandledrejection', e => post('promise_reject', { reason: String(e.reason) }));
  const of = window.fetch;
  window.fetch = function (input, init) {
    return of.call(this, input, init).then(r => { if (!r.ok) post('network_fail', { url: String(input), status: r.status }); return r; })
      .catch(err => { post('network_fail', { url: String(input), error: String(err) }); throw err; });
  };
  const oo = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function (m, u) { this._zu = u; this._zm = m; return oo.apply(this, arguments); };
  const os = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.send = function () {
    this.addEventListener('load', () => { if (this.status >= 400) post('network_fail', { url: this._zu, status: this.status, method: this._zm }); });
    return os.apply(this, arguments);
  };
  window.addEventListener('load', () => setTimeout(() => {
    const empty = !document.body || document.body.children.length === 0 ||
      (document.body.innerText || '').trim().length === 0 && document.querySelectorAll('canvas,img,svg,video').length === 0;
    if (empty) post('white_screen', { url: location.href });
    post('metrics', { domNodes: document.getElementsByTagName('*').length });
  }, 1200));
  // zq.* 骨架（M5 充实实现；此处注册机制先行）
  window.zq = Z.api = new Proxy({}, { get: (_, ns) => new Proxy({}, { get: (__, fn) => (...args) =>
    new Promise((res, rej) => {
      const id = ++Z.seq;
      Z.pending = Z.pending || {}; Z.pending[id] = { res, rej };
      post('zq_call', { id, ns, fn, args: JSON.stringify(args || []) });
    }) }) });
  window.__zqResolve = (id, ok, value) => { const p = Z.pending && Z.pending[id]; if (p) { delete Z.pending[id]; ok ? p.res(JSON.parse(value)) : p.rej(new Error(value)); } };
})();
```

- [ ] **Step 2: Commit** `feat(core:web): 织雀桥JS采集器与zq调用骨架`

### Task 1.2 采集事件模型 + TimelineReducer

**Files:** Create `:core:web/.../DebugEvent.kt`、`TimelineReducer.kt`；Test `TimelineReducerTest.kt`
- [ ] **Step 1: 模型**

```kotlin
@Serializable data class DebugEvent(val seq: Long, val t: Long, val type: String, val level: String? = null,
    val text: String? = null, val message: String? = null, val line: Int? = null, val stack: String? = null,
    val url: String? = null, val status: Int? = null, val id: Long? = null, val ns: String? = null,
    val fn: String? = null, val args: String? = null)
data class TimelineEntry(val event: DebugEvent, val isProblem: Boolean)
```

- [ ] **Step 2: 失败测试**：`error/warn 归问题流；log 归输出流；同 text 5 连发折叠计数；zq_call 路由到 pending 回调分发器`；**Step 3: Run FAIL；Step 4: 实现** reducer（纯函数 `reduce(events: List<DebugEvent>): Timeline`）；**Step 5: PASS；Step 6: Commit** `feat(core:web): 时间线reducer`

### Task 1.3 WebView 宿主（独立进程 + AssetLoader + 崩溃恢复 + 截图）

**Files:** Create `:core:web/.../RunnerWebHost.kt`（Activity，manifest 中 `android:process=":web"`）、`AssetServer.kt`、`WebSnapshot.kt`；`AndroidManifest.xml`（`android:usesCleartextTraffic="false"`、`Renderer Priority`）

- [ ] **Step 1: AssetServer**——`WebViewAssetLoader` + 自定义 `PathHandler` 读项目目录，域名 `https://appassets.androidplatform.net/assets/`，拦截子资源请求（file:// 一律禁止）
- [ ] **Step 2: RunnerWebHost 承载**：settings（JS/domStorage/WebGL、`setWebContentsDebuggingEnabled` 按构建类型）；`WebViewClient.onPageStarted` 注入 `zhique-bridge.js`；`onRenderProcessGone` → 记录崩溃事件 + 自动 reload（最多 3 次）；`WebChromeClient.onConsoleMessage` 兜底采集；`addJavascriptInterface(CollectorBridge, "ZhiqueNative")`（`@JavascriptInterface onEvent(json)` → Flow）
- [ ] **Step 3: 能力检测**：加载 `about:blank` 后 evaluateJavascript 探测 `navigator.gpu`/`WebGL2RenderingContext`/`OffscreenCanvas`，结果入 `CapabilityReport`（WebGPU 缺失时对页面注入 polyfill 提示脚本并在抽屉标注「已降级 WebGL」）
- [ ] **Step 4: 截图**：`PixelCopy.request(webView, bitmap)` 封装 `suspend fun snapshot(): Bitmap`
- [ ] **Step 5: 真机验证**（android-emulator：`android_build_and_run` 装 debug 版）→ 打开内置示例项目，Logcat 无 crash、`zq_call` 事件进入 Flow
- [ ] **Step 6: Commit** `feat(core:web): WebView宿主/采集桥/崩溃恢复/截图`

### Task 1.4 三模式运行器 UI（drawer / split / bubble）

**Files:** Create `:app/.../runner/RunnerScreen.kt`、`RunnerMode.kt`、`DebugDrawer.kt`、`FloatingBubble.kt`；`Motion.kt`（M9 前置最小 spring 常量：`val ZqSpring = spring<Float>(dampingRatio = 0.8f)`）

- [ ] **Step 1: RunnerMode 枚举 + 切换器**（右上角胶囊三态；选中写回 `projectMeta.runnerMode`）
- [ ] **Step 2: DebugDrawer**——`Animatable` 高度拖拽（跟手 velocity → spring 收纳到 3 档：peek 12% / half 50% / full 92%）；三页签（报错/Console/网络）+ 模式切换按钮 + 「交给 Agent」主按钮（M4 前占位 toast）
- [ ] **Step 3: Split 模式**——`Column(Modifier.weight)` 上 WebView 下 AI 面板（占位），分界线可拖（spring 吸附 30/50/70%）
- [ ] **Step 4: FloatingBubble**——可拖拽悬浮球（边缘吸附 spring），点开半透明浮层面板（Console 摘要 + Agent 按钮），`WindowManager` overlay 权限申请流程
- [ ] **Step 5: Compose UI 测试**：三模式切换断言、抽屉三档吸附、悬浮球边缘吸附
- [ ] **Step 6: 真机验证**：拖拽跟手、60fps（开发者选项 GPU 渲染条无红条）
- [ ] **Step 7: Commit** `feat(app): 三模式运行器与调试抽屉`

### Task 1.5 项目列表页（临时首页）+ 示例项目

**Files:** Create `:app/.../home/HomeScreen.kt`；`assets/samples/stars.html`（WebGL 星空 demo，含故意可触发的报错便于验收调试链路）
- [ ] Step1 列表（名称/时间/▶ 运行）、FAB 新建空项目、长按菜单（重命名/移动分组/复制/zip 导出/删除确认）——对齐 X5b。Step2 真机跑通：点 ▶ 进 Runner。Step3 Commit `feat(app): 首页项目列表与管理操作`

---

# M2 会粘（智能粘贴管道 + 分享目标）

### Task 2.1 PasteClassifier（纯 JVM，TDD 全量语料）

**Files:** Create `:core:paste/.../PasteClassifier.kt`；Test `PasteClassifierTest.kt`（语料 ≥20 例）

- [ ] **Step 1: 形态定义**

```kotlin
sealed interface PasteForm {
    data class CompleteHtml(val html: String) : PasteForm
    data class MixedBlocks(val blocks: List<CodeBlock>, val prose: String) : PasteForm
    data class Fragments(val html: String?, val css: List<String>, val js: List<String>) : PasteForm
    data class JsOnly(val js: String) : PasteForm
    data class CssOnly(val css: String) : PasteForm
    data class ApiConfig(val raw: String) : PasteForm          // Apilot/JSON/cURL（→ 转存 Provider 流程）
    data class Unknown(val raw: String) : PasteForm
}
data class Classified(val form: PasteForm, val confidence: Float)
```

- [ ] **Step 2: 失败测试语料（全部写进测试文件）**：①完整 `<!DOCTYPE html>`；②「好的，代码如下：```html …```」混排；③三个代码块分别标 html/css/javascript；④仅 `function(){}`；⑤仅 `body{color:red}`；⑥markdown 带行号前缀 `01 | <div>`；⑦cURL `-H "Authorization: Bearer sk-..."`；⑧Apilot 导出 JSON 含 `apiProfiles`；⑨空串。断言形态+置信度阈值
- [ ] **Step 3: Run FAIL → Step 4 实现**（正则引擎：``` 围栏解析、`<html|<!DOCTYPE` 探测、语言标注识别、代码指纹 `function\s*\(|=>|const ` vs `@media|{[\s\S]*:[\s\S]*;}`、sk-/Bearer/apiProfiles 关键词路由）
- [ ] **Step 5: PASS → Step 6: Commit** `feat(core:paste): 形态分类器`

### Task 2.2 Cleaner + Assembler

**Files:** Create `:core:paste/.../Cleaner.kt`、`Assembler.kt`；Test 同模块
- [ ] Step1 失败测试：剥围栏/剥行号前缀/统一 `<!DOCTYPE html>` 骨架组装（Fragments→标准文档：css 入 `<style>`、js 入 `<script defer>`、title 从 `<title>` 或首行注释取）、**清洗报告**（每项动作+原文摘录，可整体回滚=返回原始输入重跑）。Step2 FAIL→Step3 实现→Step4 PASS→Step5 Commit `feat(core:paste): 清洗与组装`
- [ ] Step6（反向兜底钩子）：`Assembler` 输出后扫描 `navigator.mediaDevices|geolocation|getUserMedia|Notification.` 等标准 API 与未知 `zq.` 调用 → `CompatHint`（提示走提示词桥或内置 AI 翻译；M4/M8 接线）

### Task 2.3 粘贴预览 UI + 分享目标 + 剪贴板卡

**Files:** Create `:app/.../paste/PastePreviewScreen.kt`；`AndroidManifest.xml` 加 `<intent-filter>` SEND/SEND_MULTIPLE text\*；`HomeScreen` 加剪贴板卡（回前台 `ON_RESUME` 读一次 + 显式按钮，不做后台监听）
- [ ] Step1 预览屏：解析结果卡 + 清洗报告（可展开/撤销）+ 命名编辑 +「存为草稿/运行 ▶」；设置项「粘贴后自动运行/停在预览」生效。Step2 分享目标：其他 App 分享文本 → 直达预览屏。Step3 真机：从浏览器复制 HTML → 织雀卡片出现 → 2 触内运行。Step4 Commit `feat(app): 智能粘贴预览与分享目标`

---

# M3 会聊（Provider 三协议 + 输出上限/续写 + 思考流）

### Task 3.1 流事件模型 + OpenAI 兼容适配器

**Files:** Create `:core:ai/.../Provider.kt`、`StreamEvent.kt`、`OpenAiCompatProvider.kt`、`SseParser.kt`；Test `OpenAiCompatProviderTest.kt`（mockwebserver）

- [ ] **Step 1: 契约**

```kotlin
sealed interface StreamEvent {
    data class ThinkingDelta(val text: String) : StreamEvent
    data class ContentDelta(val text: String) : StreamEvent
    data class ToolCallDelta(val index: Int, val id: String?, val name: String?, val argsDelta: String) : StreamEvent
    data class Done(val stopReason: StopReason) : StreamEvent   // STOP / LENGTH / ERROR(msg) / CANCELLED
}
interface Provider {
    val id: String
    suspend fun chatStream(req: ChatRequest): Flow<StreamEvent>
}
@Serializable data class ChatRequest(
    val baseUrl: String, val apiKey: String, val model: String,
    val messages: List<ChatMessage>,           // role: system/user/assistant/tool
    val tools: List<ToolSchema> = emptyList(),
    val maxTokens: Int, val temperature: Double = 0.3,
    val thinkingEnabled: Boolean = true,
)
```

- [ ] **Step 2: SseParser 失败测试**：分帧边界（`data: ` 前缀、多行 data、`[DONE]`、CRLF、半包粘包）；`choices[0].delta.reasoning_content` → ThinkingDelta、`content` → ContentDelta、`finish_reason=="length"` → Done(LENGTH)；`tool_calls` 增量聚合
- [ ] **Step 3: Run FAIL → Step 4 实现**（OkHttp `source().inputStream()` 逐行；`/v1/chat/completions`，`stream:true`；headers `Authorization: Bearer`）
- [ ] **Step 5: PASS → Step 6: Commit** `feat(core:ai): OpenAI兼容适配器与SSE解析`

### Task 3.2 Anthropic / Gemini 适配器

**Files:** Create `AnthropicProvider.kt`、`GeminiProvider.kt`；Test 两套（mockwebserver 脚本对齐官方事件名）
- [ ] Anthropic：`x-api-key`+`anthropic-version`，`/v1/messages` stream：`content_block_delta.thinking_delta`→Thinking、`text_delta`→Content、`input_json_delta`→ToolCall、`message_delta.stop_reason=="max_tokens"`→Done(LENGTH)
- [ ] Gemini：`:streamGenerateContent?alt=sse&key=`；`candidates[0].content.parts[].thought=true`→Thinking、`text`→Content、`finishReason=="MAX_TOKENS"`→LENGTH
- [ ] 每适配器：错误码→`AiError`（401/429/5xx 分类，供退避策略）；两套测试 PASS 后 Commit `feat(core:ai): anthropic与gemini原生适配`

### Task 3.3 ModelCatalog（modality / maxOutput / contextWindow 知识库）

**Files:** Create `:core:ai/.../ModelCatalog.kt`；Test `ModelCatalogTest.kt`
- [ ] 数据：常见模型出厂标注（示例：`gpt-4o*` vision/16384/128k；`gpt-5*` vision/…（按当期官方值维护）；`deepseek-chat` text/8192/64k；`deepseek-reasoner` text(含思考)/8k/64k；`claude-sonnet-4*` vision/64000/200k；`gemini-2.*-pro` vision/8192/1M；未收录默认 `text/16384/32k`）；`resolveMaxOutput(req)`、`resolveContextWindow(model)`；探测函数 `suspend fun probeVision(provider, model): Boolean?`（发一张 8px 图最小请求，成功 true/400 参数错 false/网络异常 null）
- [ ] 测试：覆写优先级 手动 > 知识库 > 默认。PASS → Commit `feat(core:ai): 模型能力目录三层填充`

### Task 3.4 TruncationContinuer（防截断状态机）★核心

**Files:** Create `:core:ai/.../TruncationContinuer.kt`；Test `TruncationContinuerTest.kt`

- [ ] **Step 1: 失败测试（完整覆盖）**：①Done(STOP) 不续写；②Done(LENGTH) 自动续写，第二段 STOP → 拼接一次、badge=1；③连续 LENGTH 两段后 STOP → badge=2；④超过 maxSegments(3) 仍 LENGTH → 返回 `Truncated(limitHit=true)`；⑤拼接去重：前段尾 `\n}\n` 与续段头 `\n}\n` 重叠去一；⑥代码边界：前段止于行中，续段首为空行则去空行衔接；⑦maxSegments=0 → 立即 Truncated；⑧手动 `continueOnce()` 再续一段

```kotlin
class TruncationContinuer(
    private val chat: suspend (ChatRequest) -> Flow<StreamEvent>,
    private val maxSegments: Int = 3,          // 设置可调 0–10
) {
    data class Out(val content: String, val thinking: String, val segments: Int, val limitHit: Boolean)
    data class Truncated(val partial: String, val segments: Int) : Exception()

    suspend fun generate(req: ChatRequest): Out {
        var content = ""; var thinking = ""; var seg = 0
        var r = req
        while (true) {
            var stop = StopReason.STOP
            chat(r).collect { e -> when (e) {
                is StreamEvent.ContentDelta -> content += e.text
                is StreamEvent.ThinkingDelta -> thinking += e.text
                is StreamEvent.Done -> stop = e.stopReason
                else -> {}
            } }
            when (stop) {
                StopReason.STOP -> return Out(content, thinking, seg, limitHit = false)
                StopReason.LENGTH -> {
                    if (seg >= maxSegments) throw Truncated(content, seg)
                    seg++
                    val tail = content.takeLast(4096)            // 前缀窗口防膨胀
                    r = req.copy(messages = req.messages +
                        ChatMessage("assistant", tail) +
                        ChatMessage("user", CONTINUE_PROMPT))     // "从上次中断处原样继续，不要重复已输出内容"
                    content = stitchPreview(content)              // 预去重，最终段后再整体 stitch
                }
                is StopReason.ERROR -> throw e(AiErrorException(stop.msg))
                StopReason.CANCELLED -> throw CancellationException("user cancel")
            }
        }
    }
    suspend fun continueOnce(partial: String, req: ChatRequest): Out { /* 同管线单段 */ }
}
/** 重叠去重：找前段尾与续段头最长公共重叠（≤4 行）并去一 */
internal fun stitch(prev: String, next: String): String { /* 行级双指针尾部/头部匹配 */ }
internal fun stitchPreview(s: String) = s
```

- [ ] **Step 2: Run FAIL → Step 3: 实现（含 stitch 行级算法）→ Step 4: PASS → Step 5: Commit** `feat(core:ai): 防截断自动续写状态机`

### Task 3.5 对话 UI（思考折叠 + 续写徽标 + 用量指示）

**Files:** Create `:app/.../chat/ChatScreen.kt`、`ThinkingBlock.kt`、`MessageList.kt`
- [ ] **ThinkingBlock**（类 ZCode）：默认折叠摘要行 `已思考 12.4s · 842 tokens ▸`；展开 spring 高度动画 + 逐字回放（`remember` 存已展开看过的增量缓冲）；思考与正文两个独立渲染区，**任何情况下思考不插入正文流**；发送历史时思考内容按协议丢弃（不回传给模型）
- [ ] 消息渲染：markdown 基础（代码块高亮 copy 按钮）、`已续写 N 段` 徽标、Truncated → 红色警告条 + 「继续输出」按钮（调 `continueOnce`）
- [ ] 输出上限/上下文用量：顶栏双环（输出 tokens / 上下文 占比）；上下文 ≥80% 琥珀色（M4 接真值）
- [ ] UI 测试：折叠/展开、思考与正文分离断言（文本只出现在各自区块）
- [ ] Commit `feat(app): 对话面板与思考流折叠展示`

### Task 3.6 Provider 设置页 + 角色路由 + Token 统计

**Files:** Create `:app/.../settings/ProvidersScreen.kt`、`RoleRouterScreen.kt`；`:core:ai/.../RoleRouter.kt`（Test `RoleRouterTest.kt`：五槽解析、省钱/均衡/质量预设、视觉槽纯文本→`VisionRoleError` 硬拦、项目覆盖优先级）；UsageMeter（每 Provider/项目累计，DataStore 落盘）
- [ ] 新增/编辑 Provider 表单（协议选择联动 base URL 预填、Key 密文存 CryptoStore、模型名 + 「拉取模型列表」按钮、modality 徽章 + 探测按钮、输出上限输入（0=默认））
- [ ] Commit `feat(app): provider管理与角色路由`

### Task 3.7 首启引导

**Files:** Create `:app/.../onboarding/OnboardingScreen.kt`
- [ ] 2 步：粘贴识别 API（调 `:core:paste` 的 ApiConfig 形态→ 预填表单 + 测连通）/ 或跳过玩示例；隐私告知卡（「代码将发送至你配置的服务商」勾选记录）
- [ ] Commit `feat(app): 首启引导90秒`

---

# M4 会修（Agent 循环 + 三层记忆 + 上下文压缩 + 轻编辑器）

### Task 4.1 工具注册表与工具实现

**Files:** Create `:core:agent/.../Tool.kt`、`ToolRegistry.kt`、`tools/WebTools.kt`、`tools/FileTools.kt`、`tools/GitTools.kt`（git 工具调 `:core:publish` 接口，M7 前返回 NotReady）；Test `ToolRegistryTest.kt`

```kotlin
interface Tool {
    val name: String; val schema: ToolSchema        // JSON Schema（协议三家用）
    val requiresConfirm: Boolean get() = false      // 外发动作 true
    suspend fun invoke(ctx: ToolContext, args: JsonElement): JsonElement
}
data class ToolContext(val projectId: String, val web: WebControl, val repo: ProjectRepository,
                       val history: HistoryStore, val vision: Boolean)   // vision=false → 注册表过滤 screenshot_page
```

- [ ] 实现：`run/stop/reload`（WebControl 接口由 1.3 宿主实现，跨进程直连）、`read_console`（时间线问题流）、`read_dom_snapshot`（evaluateJavascript 序列化 DOM 树摘要：tag/id/class/文本前 80 字/子节点数，≤64KB）、`screenshot_page`（PixelCopy；**注册阶段按 vision 过滤**）、`edit_file`（整文件替换或行区间补丁；产出后**未闭合检测**：括号平衡 + `<script>/<style>/<html>` 闭合标签校验，失败抛 `UnclosedCode` → 由 4.2 循环强制续写）、`list_files/read_file/grep`（正则+行号输出）
- [ ] 测试：注册表按 vision 装配断言；edit_file 未闭合用例；grep 输出格式。PASS → Commit `feat(core:agent): 工具注册表与实现`

### Task 4.2 AgentOrchestrator（循环 + 三道安全带）

**Files:** Create `:core:agent/.../Orchestrator.kt`、`Budget.kt`、`SnapshotPolicy.kt`；Test `OrchestratorTest.kt`（mock Provider 脚本驱动）

```kotlin
class Orchestrator(
    private val llm: suspend (ChatRequest) -> Flow<StreamEvent>,   // 角色路由后的目标模型
    private val continuer: TruncationContinuer,
    private val tools: ToolRegistry, private val budget: Budget,
) {
    data class StepLog(val no: Int, val thought: String, val action: String, val args: JsonElement?,
                       val result: JsonElement?, val ok: Boolean, val snapshotId: String?, val at: Long)
    suspend fun run(goal: String, ctx: AgentContext): Flow<AgentEvent> = channelFlow {
        budget.start(); var round = 0
        try {
            while (true) {
                budget.checkpoint(); round++
                val req = ctx.assembler.build(goal, round)          // 三层记忆组装（4.3）
                val out = continuer.generate(req.withTools(tools.schemas(ctx.vision)))
                send(AgentEvent.Round(round, out.thinking, out.content))
                val calls = parseToolCalls(out)                      // 容错：JSON 解析失败 → 修正提示重试一次
                if (calls.isEmpty()) { send(AgentEvent.Finished(out.content)); break }
                for (c in calls) {
                    if (budget.exhausted()) { send(AgentEvent.BudgetHit); return@channelFlow }
                    val snap = ctx.history.snapshotBefore(ctx.projectId, c)   // 快照先行
                    send(AgentEvent.Step(snap.id, c))
                    val r = runCatching { tools.invoke(ctx, c).also { ctx.audit.append(c, it) } }
                        .recoverCatching { e -> if (e is Tool.UnclosedCode) forceRewrite(c) else throw e }
                    send(AgentEvent.StepResult(round, c.name, r))
                    if (tools[c.name]?.requiresConfirm == true && !ctx.autoApproved)
                        send(AgentEvent.AwaitConfirm(c))              // 暂停等待用户批准
                }
            }
        } finally { ctx.memory.writeBack(ctx.projectId) }            // zhique.md 写回
    }
}
```

- [ ] Budget：轮数（默认 5）/token/时长三闸，`exhausted()` 单一事实源；测试：预算耗尽停在第 N 轮、快照可回滚任意步、audit 完整重放。PASS → Commit `feat(core:agent): 编排循环与安全带`

### Task 4.3 三层记忆 + 上下文压缩（上限可调/自动/手动）★核心

**Files:** Create `:core:agent/.../Memory.kt`、`ContextAssembler.kt`、`Compactor.kt`、`FileMap.kt`；Test `ContextAssemblerTest.kt`、`CompactorTest.kt`

- [ ] **Step 1: ContextAssembler 失败测试**：预算裁剪顺序（系统规范>项目记忆摘要>任务目标>文件地图>报错时间线>近期轮次，超预算从尾部丢）；`FileMap` 生成（结构树 + 每文件符号索引：HTML→id/class、JS→function/const 名，≤2KB/文件）；`read_file/grep` 工具结果不计入长期预算（会话滚动窗口）
- [ ] **Step 2: Compactor 失败测试（新需求全量）**：

```kotlin
@Test fun `用量达80%自动触发且不打断当前轮`() { /* assembler.usage=0.82 → compact() 后台执行，期间 append 不阻塞 */ }
@Test fun `手动压缩立即执行`() { compactor.compactNow(session); /* usage < 阈值, manual=true */ }
@Test fun `不变量保留`() { /* 压缩后 messages 仍含：任务目标原文、关键结论(标记 ⭐ 的轮次)、最近 N=4 轮原文 */ }
@Test fun `摘要卡数据`() { /* kept/dropped 清单 + tokensBefore/After（如 48000→6100）*/ }
@Test fun `上限三层解析`() { /* 手动 > 目录(contextWindow) > 默认；budgetRatio=0.75 */ }
```

- [ ] **Step 3: 实现**：`ContextBudget(contextLimit, workRatio=0.75f, autoThreshold=0.8f)`（全部设置可调）；自动压缩在 `assembler.append` 后检查 usage≥threshold → 协程后台跑 `compactor.compact()`（快循环角色模型，prompt 固定：「压缩以下对话为要点，必须原样保留：任务目标、⭐标记结论、最近4轮」）；手动入口 `compactNow()`；`CompressionReport(kept, dropped, before, after)` 供 UI 摘要卡
- [ ] **Step 4: PASS → Step 5: Commit** `feat(core:agent): 三层记忆与上下文压缩体系`
- [ ] **Step 6: ChatScreen/AgentScreen 接线**：顶栏上下文环真值；琥珀提示；「压缩上下文」按钮 → 摘要卡（保留/丢弃+token 对比动画计数）

### Task 4.4 Agent 会话 UI

**Files:** Create `:app/.../agent/AgentScreen.kt`
- [ ] 元素：目标输入、步骤时间线（每步折叠思考块同 3.5 样式）、diff 卡（红绿行）、预算环（轮/token/时间三弧）、暂停/终止、回滚到任意快照（底部抽屉列快照）、续 5 轮、能力徽章（vision/text）、AwaitConfirm 弹批准卡（外发工具）
- [ ] Commit `feat(app): agent会话界面`

### Task 4.5 轻编辑器

**Files:** Create `:app/.../editor/EditorScreen.kt`（sora-editor `Compose` 互操作 `AndroidView`；TextMate 语法 HTML/CSS/JS）
- [ ] 打开 index.html 及资源文件 Tab；选中浮出「问 AI 这段/修这段/复制」（选中范围作为 ChatRequest 附上下文）；Agent 会话运行中只读横幅
- [ ] Commit `feat(app): 轻编辑器`

### Task 4.6 AI 兜底解析接线（F1 收口）

**Files:** Modify `:core:paste/.../Assembler.kt`（confidence < 0.6 → 走快循环角色「仅解析重组」prompt，输出 JSON 结构化，失败保留原文入库）
- [ ] 测试：mock 低置信输入 → AI 路径调用断言、原文保留。Commit `feat(core:paste): AI兜底解析`

---

# M5 能调系统（权限桥全能力）

### Task 5.1 PermissionRegistry 状态机

**Files:** Create `:core:permission/.../PermissionRegistry.kt`、`Capabilities.kt`；Test `PermissionRegistryTest.kt`

```kotlin
enum class PState { NOT_ASKED, ASKING, GRANTED, DENIED }
class PermissionRegistry(private val repo: ProjectRepository) {
    fun state(projectId: String, capability: String): PState
    suspend fun request(projectId: String, capability: String, why: String): PState
        // NOT_ASKED→ASKING→(用户卡)→GRANTED/DENIED；DENIED 后再调直接返回 DENIED（除非用户在权限中心改）
    fun revoke(projectId: String, capability: String)          // → NOT_ASKED；运行中 zq 调用返回 denied 错误
    fun usage(projectId: String): Map<String, Int> = repo.meta(projectId).permissionUsage
    fun suggestForExport(projectId: String): List<String>      // usage>0 的能力 → manifest 变体映射
}
```

- [ ] 测试：四态迁移全路径、拒绝不重弹、吊销后调用语义、usage 计数、导出建议。PASS → Commit `feat(core:permission): 权限矩阵状态机`

### Task 5.2 zq.* 能力实现（分批）

**Files:** Create `:core:permission/.../zq/ZqDispatcher.kt` + 各能力 `ZqCamera.kt`、`ZqFile.kt`、`ZqMic.kt`、`ZqLocation.kt`、`ZqSensor.kt`、`ZqBluetooth.kt`、`ZqNotify.kt`、`ZqClipboard.kt`、`ZqShare.kt`、`ZqScreen.kt`
- [ ] **ZqDispatcher**：`zq_call` 事件 → `registry.request`（授权卡 UI 在 `:app` 侧弹）→ GRANTED 才执行 native → `__zqResolve(id, ok, json)`；DENIED 返回 `{"code":"denied"}`（网页优雅降级）；usage+1
- [ ] 相机（CameraX 预览 Compose 层 + 拍照到项目目录，返回 data URL 可选）；麦克风（AudioRecord 录制返回 blob 路径）；文件（SAF CreateDocument/OpenDocument + 项目沙盒直读写 `zq.file.read/write/list`）；定位（LocationManager last known + 周期流）；传感器（SensorManager → `zq.sensor.on加速度/陀螺仪/磁力`，EventChannel 订阅模型）；蓝牙（BLE 扫描结果流）；通知（channel+post）；剪贴板（get/set）；分享（ACTION_SEND）；截屏（MediaProjection，独立授权）
- [ ] 每能力 manifest 权限集中声明（App 全量声明 = 集中持权）；W3C 路：`WebChromeClient.onPermissionRequest` 路由 `RESOURCE_VIDEO/AUDIO_CAPTURE` 到同一 registry
- [ ] 真机验收：示例 `camera-test.html` 用 `zq.camera` 拍照→存文件→页面显示；拒绝路径优雅降级。Commit `feat(core:permission): zq全能力桥`

### Task 5.3 权限中心 UI

**Files:** Create `:app/.../settings/PermissionCenterScreen.kt`
- [ ] 项目×能力矩阵（四色态 chip）、点击改状态、运行中项目提醒、全局区（密钥库状态入口 M6 前占位）。Commit `feat(app): 权限中心`

---

# M6 能出包（端上 APK 组装 + 签名 + 导出中心）

### Task 6.1 模板壳（:template-min / :template-full）

**Files:** Create 两个 application 模块（复用 `:core:web` 运行时 + `:core:permission` 桥 + assets 项目加载器；无编辑器/无 AI）
- [ ] `:template-min`：仅 INTERNET + WebGPU 常规声明；`:template-full`：全量桥权限；启动即全屏加载 assets/project/index.html；壳内权限卡与织雀同体验
- [ ] `assembleRelease` 产物拷入 `:core:export/src/main/assets/templates/`（构建任务自动拷贝：`tasks.register("harvestTemplates")`）
- [ ] 真机验证两个壳可运行。Commit `feat(export): 模板壳变体`

### Task 6.2 KeystoreManager + Signer + AssetInjector

**Files:** Create `:core:export/.../KeystoreManager.kt`、`Signer.kt`、`AssetInjector.kt`、`VersionManager.kt`；Test `VersionManagerTest.kt`、`ExportPipelineTest.kt`（Robolectric 或 androidTest）

- [ ] **KeystoreManager**：首启生成 RSA-2048 密钥对 → `KeyStore.getInstance("JKS")` 持久 `keystore/zhique-release.jks`（**口令运行时随机生成**并密文存 CryptoStore；源码/配置/测试零口令字面量）；**签名持久化保证（决策29）**：①`export()` 拷 .jks 到 Downloads + 分享面板/SAF 保存到电脑（首次导出 APK 成功后引导完成一次电脑备份，记录 `lastBackupAt`，导出中心/权限中心常装备份状态与超期提醒）②`import(path, pass)` 恢复——**导入后计算证书 SHA-256 与既有 ExportRecord.certSha256 比对，不一致拒绝生效**；③`SignatureGuard.verifyBeforeExport(projectId)`：PackageManager 读取手机已装 `com.zhique.export.<slug>` 的签名证书与当前密钥库证书比对，**不一致抛 SignatureMismatchException（UI 阻断导出并说明：先卸载旧包或恢复正确密钥库）**——机制上保证每次更新可覆盖安装
- [ ] **AssetInjector**（zipflinger）：打开模板 apk → 删旧 `assets/project/*` → 写入项目文件 → 关闭；**Signer**（apksig）：`ApkSigner.Builder` v2+v3，输出临时 apk → `zipflinger` 对齐（`ZipArchive` alignment 4/16 页规则）→ `apksigner verify` 断言（`ApkVerifier` 编程校验）
- [ ] **VersionManager**：`next(projectId) = (meta.export?.versionCode ?: 0) + 1`；包名 `com.zhique.export.<slug(name)>`（slug 规则 + 冲突检测）；导出前三元组校验（同包名旧装签名=本密钥库 → 允许覆盖；否则 SignatureGuard 已在上一步阻断）
- [ ] 测试：版本自增、slug、签名产物 verify 通过（用测试 keystore）、**证书 SHA-256 写入 ExportRecord、SignatureGuard 不匹配阻断、import 指纹不一致拒绝、备份状态流转（lastBackupAt 记录/超期判定）**（口令一律运行时随机，测试不写真实口令字面量）。PASS → Commit `feat(export): 注入签名版本管线与签名持久化保证`

### Task 6.3 导出向导 + 导出中心 + 快捷方式

**Files:** Create `:app/.../export/ExportWizardScreen.kt`、`ExportCenterScreen.kt`
- [ ] 3 步：①应用信息（名/图标选择/主题色，预览图标）②权限与签名（**运行期真实使用记录**驱动的建议 + min/full 变体选择 + 密钥库备份状态置顶）③打包进度 → 完成（安装[PackageInstaller 全新安装/更新流]/存 Downloads/分享）
- [ ] 桌面快捷方式（`ShortcutManager.requestPinShortcut`，intent 直达该项目全屏运行）作为轻量选项
- [ ] 导出中心 Tab：全部导出物、版本、覆盖升级检测。真机验收：导出→安装→**再次导出 v2 覆盖装数据保留**→两个项目共存。Commit `feat(app): 导出向导与中心`

---

# M7 能发布（GitHub + 状态机 + 自更新）

### Task 7.1 GitRepo（JGit）+ GitHubApi

**Files:** Create `:core:publish/.../GitRepo.kt`、`GitHubApi.kt`、`PatStore.kt`；Test `GitHubApiTest.kt`（mockwebserver）
- [ ] GitRepo：`initRepo(projectDir)`、`status()`（diff 摘要 A/M/D）、`commit(msg, author)`、`push(remote with PAT token、分支)`；凭据 `UsernamePasswordCredentialsProvider("x-access-token", pat)`；**PAT 永不入日志**（logFilter）
- [ ] GitHubApi：`createRepo(name, private, autoInit)`、`listReleases(owner/repo)`、`createRelease(tag, notes, prerelease)`、`uploadAsset(releaseId, apk)`；fine-grained PAT 首次引导页（说明仅 repo 权限 + 创建链接）
- [ ] Commit `feat(publish): git与github底座`

### Task 7.2 ReleaseJob 持久化状态机 ★核心

**Files:** Create `:core:publish/.../ReleaseJob.kt`；Test `ReleaseJobTest.kt`

```kotlin
enum class ReleaseStage { PLANNED, CHECKED, COMMITTED, PUSHED, RELEASED }
@Serializable data class ReleaseJob(
    val id: String, val projectId: String, val stage: ReleaseStage = ReleaseStage.PLANNED,
    val commitSha: String? = null, val remoteUrl: String? = null, val tag: String? = null,
    val message: String? = null, val updatedAt: Long = System.currentTimeMillis())

class ReleaseJobEngine(private val repo: ProjectRepository, private val git: GitRepo, private val api: GitHubApi) {
    /** 每步前幂等核对：远端已有该 sha / tag 则跳过重做 */
    suspend fun advance(job: ReleaseJob): ReleaseJob = when (job.stage) {
        PLANNED -> job.also { persist(it, stage=CHECKED) }            // check: status+远端同步核对
        CHECKED -> { val sha = git.commit(dir, job.message!!); persist(job, COMMITTED, sha) }
        COMMITTED -> { git.push(dir); persist(job, PUSHED) }
        PUSHED -> { if (job.wantRelease) api.createRelease(...); persist(job, RELEASED) }
        RELEASED -> job /* done */
    }
    fun resume(): ReleaseJob?            // 读 project history/release-job.json，非终态即返回
    private fun persist(j, stage, sha=null, tag=null) = repo.saveJob(j.copy(stage=stage, /*evidence*/))
}
```

- [ ] 测试全量：①每步落盘断言（kill 模拟：advance 后进程重启→resume 返回下一 stage）；②幂等（PUSHED 状态重复 advance 不重复 push——push 前查远端 contains sha）；③**跨模式接管**（手动停在 COMMITTED → Agent 引擎 resume 继续）；④用户取消 RESULT 不同语义；⑤RELEASED 为可选步骤。PASS → Commit `feat(publish): 发布状态机`
- [ ] 手动向导 UI（日常 2 步/首次 4 步，参数全预填，AI commit message 可改，失败重试）+ Agent `create_repo/push/read_releases` 工具（`requiresConfirm=true`）+ 绑定记忆。Commit `feat(app): 发布双模式向导`

### Task 7.3 自更新

**Files:** Create `:core:publish/.../UpdateChecker.kt`；`:app/.../settings/AboutScreen.kt`
- [ ] `check(channel)`: GitHub Releases latest（stable=非 prerelease 最新 / beta=含 prerelease）→ semver 比较 → changelog 渲染 → 下载 APK（Downloads，进度通知）→ PackageInstaller 更新流（同签名约束天然满足）
- [ ] 关于页：版本号(build)、检查更新、更新日志列表、开源仓库、Apache-2.0。Commit `feat(app): 自更新与关于页`

---

# M8 互联（Apilot 桥 + 提示词桥）

### Task 8.1 Apilot V2 桥（严格按官方文档常量）

**Files:** Create `:core:apilot/.../ApilotBridge.kt`、`ProfileMapper.kt`；Test `ProfileMapperTest.kt`
- [ ] **读**：`PICK_API_CONFIG`（package `com.example.api_manager` 可配置常量；extras：SOURCE_NAME/REQUEST_ID/SCHEMA_VERSION=2/REQUESTED_SCOPES=[connection,models.default,models.all,secret.api_key]/RETURN_TRANSPORT=auto）→ Activity Result：extra 或 content URI（**立即读、不存 URI**）；`grantedScopes` 无 `secret.api_key` 则 secrets 为空即无 Key 方案
- [ ] **写**：`IMPORT_API_CONFIGS`（payload V2 `apiProfiles`：connection/provider/protocol/models/secrets；>64KiB 走 content URI + FLAG_GRANT_READ_URI_PERMISSION；含 SOURCE_SIGNATURE_SHA256）
- [ ] **Mapper**：`provider.id×protocol.id` → ProviderConfig 同构（deepseek 保持 deepseek；`custom+openai_compatible` 兜底；**不按显示名重猜**）；取消=RESULT_CANCELED 不当失败；应用锁停留不算无响应；审计记录落库
- [ ] 测试：payload 构建断言、scope 过滤映射、V1 兼容解析（apiConfigs）。真机：与 Apilot 双向真打。Commit `feat(apilot): V2双向桥接`
- [ ] UI：服务商页「双向流转」两动作卡 + 上次同步时间 + 记录清除（对齐 ⑪ 屏）

### Task 8.2 提示词桥

**Files:** Create `:core:paste/.../PromptBridge.kt`；Test `PromptBridgeTest.kt`；`:app/.../settings/PromptBridgeScreen.kt`
- [ ] `generate(firstSentence, selectedApis): String` = 用户句 + 「运行环境：安卓 Chromium WebView，WebGL/WebGPU 可用…」+ 勾选 API 的 zq 文档段（模板资产 `assets/zq-docs/*.md`，与实现同步维护）+ 输出要求（单文件完整 HTML、无 markdown 围栏、权限被拒不崩溃）
- [ ] 测试：分段拼接、未勾选不携带。UI：API 多选 chip + 预览 + 复制/系统分享；2.2 的 CompatHint 接入此屏入口。Commit `feat(paste): 提示词桥`

---

# M9 打磨（Aurora Glass 全落地 + 设置全集 + 动效）

### Task 9.1 设计系统

**Files:** Create `:app/.../ui/theme/ZqTheme.kt`、`components/AuroraBackground.kt`、`GlassCard.kt`、`GlowButton.kt`、`Motion.kt`
- [ ] 语义色（靛蓝 #46509F 交互/绿健康/琥珀等待/红错误）双域 Material3 主题；**AuroraBackground**：`Canvas` + `infiniteTransition` 两枚径向光斑漂移（`Brush.radialGradient` 低饱和），域=晨光/深空；**GlassCard**：`Modifier.blur(20.dp).graphicsLayer{saturate}`+边框白 55%+顶部 1px 高光+阴影（API31 RenderEffect 生效断言：`Build.VERSION.SDK_INT>=31`）+ 低端机层数降级（`ActivityManager.isLowRamDevice` → 光斑 1 枚）
- [ ] Motion：spring 常量表（damping .75–.85），抽屉/按压/FAB 形变/进度环/滑入五件套全局替换；`LinearEasing` 全仓 grep 禁用（CI 检查任务）
- [ ] Commit `feat(app): aurora glass设计系统`

### Task 9.2 设置全集 + 通知 + 应用锁

**Files:** Create `:app/.../settings/*`（对齐规格 §7 树**逐项**：AI 服务商[列表/角色路由/Apilot/提示词/输出·思考·上下文/诊断/Token 统计]、权限中心、导出与签名、发布与同步[PAT/推送偏好/更新通道]、通用[外观/运行器三默认+悬浮球记忆+沉浸/粘贴偏好/编辑器/Web 偏好含 eruda 开关/通知]、隐私与安全[应用锁 BiometricPrompt+PIN/审计日志/隐私告知记录]、关于、开发者空壳[调试开关/日志导出/zq 协议文档/意图测试器]）
- [ ] 通知三事件（导出完成/Agent 完成/新版本）渠道注册与触发；应用锁入口门（PIN 设置→BiometricPrompt 验证→`LocalBroadcast` 放行）
- [ ] Commit `feat(app): 设置全集与应用锁`
- [ ] **eruda 接入**：设置开关 → 采集器注入页尾 `eruda.js`（内置资产）+ `eruda.init()`（与自研采集共存互不影响）；真机验证 Elements/Network 可用。Commit `feat(core:web): eruda高级面板开关`

---

# M10 发布 0.1.0

### Task 10.1 验收 Runbook（对照规格 §1.3 成功标准逐条）

- [ ] 全链路真机（Android 17 主力机）：①外部 AI 复制混排 → 2 触运行；②示例 stars.html 触发报错 → 交给 Agent → 自动修复（vision 模型截图自查路径 + 纯文本模型 DOM 路径各验一次）→ 回滚演练；③zq.camera 拍照保存 + 拒绝降级；④导出 v1→覆盖 v2 数据保留→双项目共存→快捷方式；⑤Apilot 双向（读取无 Key 方案 / 带 Key 方案 / 推送）；⑥发布：手动首次 4 步 + 日常 2 步 + Agent 计划批准 + **中途杀进程 → resume 续跑**；⑦自更新演练（beta 通道）；⑧压缩：灌长会话至 80% 自动压缩 + 手动压缩摘要卡；⑨续写：小 maxTokens 触发自动续 3 段 + 触顶警告一键继续；⑩思考流折叠/展开/不混正文
- [ ] 性能：运行器 120Hz 拖拽掉帧 <1%；低端机降级生效；冷启动 <2s
### Task 10.2 开源发布件
- [ ] README（截图/GIF、特性、构建指南、zq.* 协议文档链接）、LICENSE(Apache-2.0)、CI release workflow（tag push → assembleRelease → attach APK + 签名[CI 密钥仅用于 CI 构建公测包，与端上密钥库无关]）、`git tag v0.1.0` → Release
- [ ] Commit `chore: 0.1.0 发布件` → **发布**

---

## Self-Review（已执行）

**Spec coverage**：F1↔M2/M4.6，F2↔M1，F3↔M4.5，F4↔M3（含决策24/25/26 上限/续写/思考流），F5↔M4（决策11/22/27），F6↔M5，F7↔M6，F8↔M7（决策19 状态机），F9↔M8.1，F10↔M8.2，F11↔M7；X1/X2↔M9，X3↔M9.2+M3.6，X4↔M3.7，X5↔M9.2，X5b↔M0.3/M1.5，X6↔M7.3，X7↔M3.5；13 项默认方案分布各任务；决策 12/28 项目管理与 zip↔M0.3。**无缺口**。
**Placeholder scan**：无 TBD/TODO；「按当期官方值维护」为数据维护动作非占位。
**Type consistency**：`StreamEvent/StopReason`（M3）↔Continuer（3.4）↔Orchestrator（4.2）一致；`ToolContext.vision`↔注册表过滤（4.1）↔Capability（1.3/3.3）；`ProjectMeta.permissionUsage`（M0）↔Registry（5.1）↔导出建议（6.3）；`ReleaseJob`（7.2）↔resume 跨模式。
