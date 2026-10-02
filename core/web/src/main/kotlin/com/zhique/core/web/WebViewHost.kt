package com.zhique.core.web

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import com.zhique.core.web.debug.DebugEvent
import com.zhique.core.web.debug.TimelineReducer
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 启动时能力检测结果（about:blank 探测，规格 §4.2）。 */
data class CapabilityReport(
    val webGPU: Boolean,
    val webGL2: Boolean,
    val offscreenCanvas: Boolean,
) {
    /** WebGPU 缺失即降级 WebGL，抽屉需标注「已降级 WebGL」。 */
    val degraded: Boolean get() = !webGPU
}

/**
 * W3C 权限请求网关（M5）：宿主注入后，`onPermissionRequest` 路由到
 * :core:permission 的同一注册表矩阵（规格 §4.6「两条接入路，同一个注册表」）；
 * 未注入时一律 deny（不弹系统裸权限框）。
 */
fun interface PermissionGateway {
    fun onRequest(request: android.webkit.PermissionRequest)
}

/**
 * W3C 地理位置授权网关（审查修复 #2）：`onGeolocationPermissionsShowPrompt`
 * 转到同一注册表矩阵；未注入时一律拒绝。
 */
fun interface GeolocationGateway {
    fun onGeolocationPermissionShow(origin: String, allow: (Boolean) -> Unit)
}

/**
 * WebView 运行时引擎：AssetLoader 域名加载、织雀桥注入、调试采集、
 * 能力检测、渲染进程崩溃恢复（≤3 次）、PixelCopy 截图。
 *
 * 由独立进程壳 [RunnerWebHost]、:app 三模式运行器界面与 M6 导出模板壳共用。
 */
class WebViewHost(private val context: Context, source: ProjectSource) {

    /** 目录版便捷构造：织雀本体从磁盘项目目录加载（运行器/独立壳路径）。 */
    constructor(context: Context, projectDir: File) : this(context, AssetServer(projectDir))

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bridge = CollectorBridge()
    private val source: ProjectSource = source
    private val nativeEvents = MutableSharedFlow<DebugEvent>(extraBufferCapacity = 128)

    /** 桥事件 + native 事件（崩溃等）合并流。 */
    val events: Flow<DebugEvent> = merge(
        bridge.raw.mapNotNull { DebugEvent.fromJson(it) },
        nativeEvents.asSharedFlow(),
    )

    private val _capability = MutableStateFlow<CapabilityReport?>(null)

    /** 能力检测结果（null = 尚未检测完成）。 */
    val capability: StateFlow<CapabilityReport?> = _capability

    /** 能力检测结果变化回调（界面据此标注「已降级 WebGL」）。 */
    var onCapabilityDetected: ((CapabilityReport) -> Unit)? = null

    /** 渲染进程崩溃后重建 WebView 回调（界面据此重新挂载视图）。 */
    var onWebViewRecreated: (() -> Unit)? = null

    /** 连续崩溃超过 [MAX_CRASH_RECOVERY] 次放弃自动恢复回调。 */
    var onCrashGiveUp: (() -> Unit)? = null

    /** zq_call 分发器（M5 注册真实能力实现，机制先行）。 */
    val zqRouter = TimelineReducer.ZqCallRouter()

    /**
     * eruda 高级面板开关（M9）：true 时项目页加载完成后注入内置 eruda.js 并 init
     * （页尾注入，与自研采集共存互不影响）。运行器在进入前从设置读取。
     */
    @Volatile
    var erudaEnabled: Boolean = false

    /** W3C 权限请求网关（:app 侧注入 ZqW3CRouter 适配；null=一律 deny）。 */
    var permissionGateway: PermissionGateway? = null

    /** W3C geolocation 网关（:app 侧注入；null=一律拒绝）。 */
    var geolocationGateway: GeolocationGateway? = null

    /** 文件选择注入点（:app 侧 ActivityResult 实现；null 时走系统 GET_CONTENT 兜底）。 */
    var fileChooserLauncher:
        ((acceptTypes: Array<String>, callback: android.webkit.ValueCallback<Array<android.net.Uri>>) -> Unit)? = null

    // 桥 JS 与 eruda 源必须在 webView 属性初始化（buildWebView→registerDocumentStartScript）
    // 之前声明——Kotlin 属性按声明序初始化，lazy 委托声明晚于使用点会在构造期 NPE
    // （真机质量修复 B1：首启进运行器 NoSuchMethodError 后紧跟的第二次崩溃）
    private val bridgeJs: String by lazy {
        appContext.assets.open(BRIDGE_ASSET).bufferedReader().use { it.readText() }
    }

    /** 内置 eruda 源（页尾注入用；缺失返回空串 → pageEndScript 返回 null 不注入）。 */
    private val erudaSource: String by lazy {
        runCatching {
            appContext.assets.open(ErudaInjector.ERUDA_ASSET).bufferedReader().use { it.readText() }
        }.getOrDefault("")
    }

    var webView: WebView = buildWebView()
        private set

    private var crashCount = 0
    private var capabilityDetected = false
    private var hostCapabilityRechecked = false

    /** JS 桥存活标记：桥事件到达后，WebChromeClient 兜底采集静默，避免双份。 */
    @Volatile
    private var jsBridgeAlive = false

    private var nativeSeq = -1L

    init {
        bridge.onEventArrived = { jsBridgeAlive = true }
        // 规格 §4.2：about:blank 先行 → 能力检测 → 再进项目页
        webView.loadUrl("about:blank")
    }

    fun loadIndex() {
        mainHandler.post { webView.loadUrl(source.indexUrl()) }
    }

    fun reload() {
        mainHandler.post { webView.reload() }
    }

    fun evaluate(js: String) {
        mainHandler.post { webView.evaluateJavascript(js, null) }
    }

    /**
     * 挂起求值：等待 evaluateJavascript 回调并返回结果字符串（Agent DOM 摘要用）。
     * WebView 不可用/已销毁时返回 null，不抛错。
     */
    suspend fun evaluateJs(js: String): String? = withContext(Dispatchers.Main) {
        runCatching {
            suspendCancellableCoroutine { cont ->
                webView.evaluateJavascript(js) { result -> cont.resume(result) }
            }
        }.getOrNull()
    }

    fun registerZq(ns: String, fn: String, handler: TimelineReducer.ZqCallRouter.Handler) {
        zqRouter.register(ns, fn, handler)
    }

    suspend fun snapshot(): Bitmap = WebSnapshot.capture(webView)

    fun resume() = webView.onResume()

    fun pause() = webView.onPause()

    fun destroy() = webView.destroy()

    // ---- internals ----

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun buildWebView(): WebView {
        val debuggable = appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        WebView.setWebContentsDebuggingEnabled(debuggable)

        val wv = WebView(appContext)
        wv.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        with(wv.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false // file:// 一律禁止（规格 §4.2）
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = true
            // W3C geolocation 提示要到达 onGeolocationPermissionsShowPrompt 必须开启（审查修复 #2）
            setGeolocationEnabled(true)
        }
        // 渲染进程优先级：前台重要、不随后台回收（规格 §4.2 Renderer Priority）
        wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)

        // 织雀桥走 document-start 注入：早于页面任何脚本执行，
        // 解决 onPageStarted 注入漏采早期 console/error 的问题
        registerDocumentStartScript(wv, includeCaps = false)

        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? = source.intercept(request.url)

            // 外链导航拦截（PM 审计：此前点网页里的 http 链接会替换掉项目页且无法返回）
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                val url = request.url.toString()
                val inApp = url.startsWith("about:") || url.startsWith("data:") ||
                    url.startsWith("blob:") || url.startsWith("javascript:") ||
                    url.contains(ASSET_DOMAIN)
                if (inApp) return false
                // http/https 外链 → 系统浏览器；其余 scheme 尝试外部应用，失败则吞掉
                return runCatching {
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, request.url)
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    appContext.startActivity(intent)
                    true
                }.getOrDefault(true)
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (url == "about:blank" && !capabilityDetected) {
                    capabilityDetected = true
                    detectCapabilities(view)
                } else if (url != "about:blank") {
                    // 项目页成功加载完成 → 连续崩溃计数归零（「连续崩溃≤3」语义）
                    crashCount = 0
                    // 真机循环修复：WebGPU 需要安全上下文，about:blank（不透明源）上
                    // navigator.gpu 恒为 undefined → 假阴性误降级。项目页（https 自定义
                    // scheme）加载后重测一次，结果覆盖 about:blank 的初判。
                    if (!hostCapabilityRechecked) {
                        hostCapabilityRechecked = true
                        detectCapabilities(view)
                    }
                    // M9：eruda 开关打开 → 页尾注入内置资产（与自研采集共存）
                    if (erudaEnabled) {
                        ErudaInjector.pageEndScript(erudaSource)?.let { js ->
                            view.evaluateJavascript(js, null)
                        }
                    }
                }
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                handleRenderCrash(view)
                return true
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                if (!jsBridgeAlive) {
                    // 兜底采集：JS 桥未存活时从 chrome client 收 console
                    nativeEvents.tryEmit(
                        DebugEvent(
                            seq = nativeSeq--,
                            t = System.currentTimeMillis(),
                            type = TimelineReducer.TYPE_CONSOLE,
                            level = consoleLevel(message.messageLevel()),
                            text = message.message(),
                            url = message.sourceId(),
                            line = message.lineNumber(),
                        ),
                    )
                }
                return false
            }

            // W3C 标准路：getUserMedia 等系统权限回调 → 同一注册表矩阵（规格 §4.6）
            override fun onPermissionRequest(request: android.webkit.PermissionRequest) {
                val gateway = permissionGateway
                if (gateway == null) {
                    request.deny()
                    return
                }
                gateway.onRequest(request)
            }

            // W3C geolocation → 同一注册表（审查修复 #2）
            override fun onGeolocationPermissionsShowPrompt(
                origin: String,
                callback: android.webkit.GeolocationPermissions.Callback,
            ) {
                val gateway = geolocationGateway
                if (gateway == null) {
                    callback.invoke(origin, false, false)
                    return
                }
                gateway.onGeolocationPermissionShow(origin) { granted ->
                    callback.invoke(origin, granted, granted)
                }
            }

            // 文件选择（PM 审计：<input type=file> 此前无任何反应）。宿主注入
            // launcher（:app 的 ActivityResult）；未注入时尝试以系统文件选择器兜底。
            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: android.webkit.ValueCallback<Array<android.net.Uri>>,
                fileChooserParams: FileChooserParams,
            ): Boolean {
                val launcher = fileChooserLauncher
                if (launcher != null) {
                    launcher(fileChooserParams.acceptTypes ?: arrayOf("*/*"), filePathCallback)
                    return true
                }
                return runCatching {
                    val intent = (context as? android.app.Activity)?.let { act ->
                        android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).apply {
                            addCategory(android.content.Intent.CATEGORY_OPENABLE)
                            type = fileChooserParams.acceptTypes?.firstOrNull() ?: "*/*"
                            putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, fileChooserParams.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
                        }.let { i -> act.startActivityForResult(android.content.Intent.createChooser(i, "选择文件"), FILE_CHOOSER_REQUEST) }
                    }
                    intent != null
                }.getOrDefault(false)
            }

            // window.open / target=_blank（PM 审计：此前点击无反应）→ 同一 WebView 承载
            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message,
            ): Boolean {
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                transport.webView = view
                resultMsg.sendToTarget()
                return true
            }
        }
        // 网页触发的下载 → 系统下载器/浏览器（PM 审计：此前无反应）
        wv.setDownloadListener { url, _, _, _, _ ->
            runCatching {
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                appContext.startActivity(intent)
            }
        }
        wv.addJavascriptInterface(bridge, "ZhiqueNative")
        return wv
    }

    private fun detectCapabilities(view: WebView) {
        val script = "({gpu: !!navigator.gpu, " +
            "gl2: typeof WebGL2RenderingContext !== 'undefined', " +
            "offscreen: typeof OffscreenCanvas !== 'undefined'})"
        view.evaluateJavascript(script) { result ->
            val report = runCatching {
                val o = JSONObject(result)
                CapabilityReport(
                    webGPU = o.optBoolean("gpu"),
                    webGL2 = o.optBoolean("gl2"),
                    offscreenCanvas = o.optBoolean("offscreen"),
                )
            }.getOrDefault(CapabilityReport(webGPU = false, webGL2 = false, offscreenCanvas = false))
            _capability.value = report
            onCapabilityDetected?.invoke(report)
            // 能力已知 → 重新注册 document-start 脚本，把降级标记一并带上：
            // 后续任何导航（reload/内部跳转）标记都不会丢
            registerDocumentStartScript(view, includeCaps = true, report = report)
            loadIndex()
        }
    }

    private fun handleRenderCrash(view: WebView) {
        crashCount++
        nativeEvents.tryEmit(
            DebugEvent(
                seq = nativeSeq--,
                t = System.currentTimeMillis(),
                type = TimelineReducer.TYPE_WEB_CRASH,
                text = "渲染进程崩溃 #$crashCount",
            ),
        )
        (view.parent as? ViewGroup)?.removeView(view)
        view.destroy()
        if (crashCount <= MAX_CRASH_RECOVERY) {
            webView = buildWebView()
            capabilityDetected = false
            _capability.value = null
            onWebViewRecreated?.invoke()
            webView.loadUrl("about:blank")
        } else {
            onCrashGiveUp?.invoke()
        }
    }

    private fun consoleLevel(level: ConsoleMessage.MessageLevel?): String = when (level) {
        ConsoleMessage.MessageLevel.ERROR -> "error"
        ConsoleMessage.MessageLevel.WARNING -> "warn"
        ConsoleMessage.MessageLevel.TIP -> "info"
        ConsoleMessage.MessageLevel.DEBUG -> "debug"
        else -> "log"
    }

    /** document-start 注入织雀桥（能力已知后追加降级/能力标记，导航不丢）。 */
    private fun registerDocumentStartScript(
        webView: WebView,
        includeCaps: Boolean,
        report: CapabilityReport? = null,
    ) {
        val caps = if (includeCaps && report != null) {
            ";window.__ZHIQUE_NO_WEBGPU__ = ${report.degraded};" +
                "window.__ZHIQUE_CAPS__ = {gpu:${report.webGPU},gl2:${report.webGL2}," +
                "offscreen:${report.offscreenCanvas}};"
        } else {
            ""
        }
        WebViewCompat.addDocumentStartJavaScript(
            webView,
            bridgeJs + caps,
            setOf("https://$ASSET_DOMAIN"),
        )
    }

    private companion object {
        const val BRIDGE_ASSET = "zhique-bridge.js"
        const val FILE_CHOOSER_REQUEST = 0x2A1
        const val ASSET_DOMAIN = "appassets.androidplatform.net"
        const val MAX_CRASH_RECOVERY = 3
    }
}
