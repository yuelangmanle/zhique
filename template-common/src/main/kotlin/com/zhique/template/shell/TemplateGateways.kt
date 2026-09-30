package com.zhique.template.shell

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.zhique.core.permission.Capability
import com.zhique.core.permission.PState
import com.zhique.core.permission.PermissionRegistry
import com.zhique.core.permission.zq.ProjectionGateway
import com.zhique.core.permission.zq.ProjectionSession
import com.zhique.core.permission.zq.SafGateway
import com.zhique.core.permission.zq.ZqEnv
import com.zhique.core.web.WebViewHost
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 模板壳 zq 桥接线（M6 Task 6.1，:app ZqWiring 的壳内联版——不依赖 :app）：
 * 把 W3C/OS 网关挂到壳宿主（授权卡授予后立即发起系统申请）并返回 [ZqEnv]，
 * 壳内权限体验与织雀本体一致。能力注册由壳 Activity 拿 [ZqEnv] 自行完成。
 */
object TemplateZqWiring {

    fun install(
        host: WebViewHost,
        projectId: String,
        activity: androidx.activity.ComponentActivity,
        scope: CoroutineScope,
        registry: PermissionRegistry,
        projection: ProjectionGateway?,
        saf: SafGateway?,
    ): com.zhique.core.permission.zq.ZqEnv {
        val osGateway = TemplateOsPermissionGateway(activity)
        val env = com.zhique.core.permission.zq.ZqEnv(
            projectId = projectId,
            projectDir = projectDir(activity, projectId),
            scope = scope,
            registry = registry,
            evaluateJs = host::evaluate,
            appContext = activity.applicationContext,
            activity = activity,
            saf = saf,
            projection = projection,
            osPermissions = osGateway,
        )
        val w3c = com.zhique.core.permission.zq.ZqW3CRouter(registry, projectId, scope, osPermissions = osGateway)
        host.permissionGateway = com.zhique.core.web.PermissionGateway { request ->
            val resources = request.resources ?: emptyArray()
            w3c.onRequest(
                resources.toList(),
                grant = { request.grant(request.resources ?: resources) },
                deny = { request.deny() },
            )
        }
        host.geolocationGateway = com.zhique.core.web.GeolocationGateway { _, allow -> w3c.onGeolocation(allow) }
        return env
    }

    /** 壳的项目目录（assets/project/ 每次启动解包到这里，权限矩阵随 project.json 保留）。 */
    fun projectDir(activity: android.content.Context, projectId: String): File =
        File(activity.filesDir, "shell-root/projects/$projectId")
}

/** OS 运行时权限网关（:app ActivityOsPermissionGateway 的壳内联版）。 */
class TemplateOsPermissionGateway(private val activity: android.app.Activity) :
    com.zhique.core.permission.OsPermissionGateway {

    private val seq = java.util.concurrent.atomic.AtomicInteger()

    override fun granted(permissions: List<String>): Boolean = permissions.all {
        androidx.core.content.ContextCompat.checkSelfPermission(activity, it) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    override suspend fun request(permissions: List<String>): Map<String, Boolean> =
        suspendCancellableCoroutine { cont ->
            val launcher = (activity as androidx.activity.ComponentActivity).activityResultRegistry.register(
                "zq-os-perm-${seq.incrementAndGet()}",
                androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
            ) { result -> cont.resume(result) }
            cont.invokeOnCancellation { launcher.unregister() }
            launcher.launch(permissions.toTypedArray())
        }
}

/** SAF pick/save 网关（:app ActivitySafGateway 的壳内联版）。 */
class TemplateSafGateway(private val activity: androidx.activity.ComponentActivity) : SafGateway {

    private val seq = java.util.concurrent.atomic.AtomicInteger()

    private suspend fun <I, O> launch(
        contract: androidx.activity.result.contract.ActivityResultContract<I, O>,
        input: I,
        key: String,
    ): O? = suspendCancellableCoroutine { cont ->
        val launcher = activity.activityResultRegistry.register(key, contract) { result ->
            cont.resume(result)
        }
        cont.invokeOnCancellation { launcher.unregister() }
        launcher.launch(input)
    }

    override suspend fun pick(mime: String?): JsonElement {
        val uri = launch(
            androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
            arrayOf(mime ?: "*/*"),
            "zq-saf-pick-${seq.incrementAndGet()}",
        )
        if (uri == null) return buildJsonObject { put("code", "canceled") }
        val content = runCatching {
            activity.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        return buildJsonObject { put("path", uri.toString()); put("content", content ?: "") }
    }

    override suspend fun save(name: String, mime: String?, content: String): JsonElement {
        val uri = launch(
            androidx.activity.result.contract.ActivityResultContracts.CreateDocument(mime ?: "text/plain"),
            name,
            "zq-saf-save-${seq.incrementAndGet()}",
        )
        if (uri == null) return buildJsonObject { put("code", "canceled") }
        runCatching {
            activity.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
        }
        return buildJsonObject { put("path", uri.toString()); put("bytes", content.toByteArray().size) }
    }
}

/** MediaProjection 网关（:app ActivityProjectionGateway 的壳内联版）。 */
class TemplateProjectionGateway(private val activity: androidx.activity.ComponentActivity) : ProjectionGateway {

    private val seq = java.util.concurrent.atomic.AtomicInteger()

    override suspend fun session(): ProjectionSession? {
        val mpm = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
            as? android.media.projection.MediaProjectionManager ?: return null
        val result = suspendCancellableCoroutine<Pair<Int, android.content.Intent?>?> { cont ->
            val contract = object : androidx.activity.result.contract.ActivityResultContract<
                Unit, Pair<Int, android.content.Intent?>?>() {
                override fun createIntent(context: Context, input: Unit): android.content.Intent =
                    mpm.createScreenCaptureIntent()

                override fun parseResult(resultCode: Int, intent: android.content.Intent?) =
                    resultCode to intent
            }
            val launcher = activity.activityResultRegistry.register(
                "zq-projection-${seq.incrementAndGet()}", contract,
            ) { r -> cont.resume(r) }
            cont.invokeOnCancellation { launcher.unregister() }
            launcher.launch(Unit)
        } ?: return null
        val (code, data) = result
        if (code != android.app.Activity.RESULT_OK || data == null) return null
        TemplateScreenServiceController.start(activity)
        val projection = mpm.getMediaProjection(code, data) ?: return null
        return TemplateProjectionSession(activity, projection)
    }
}

/** 投影会话（:app MediaProjectionSession 的壳内联版；stop 幂等）。 */
private class TemplateProjectionSession(
    private val activity: android.app.Activity,
    private val projection: android.media.projection.MediaProjection,
) : ProjectionSession {

    private val handler = android.os.Handler(activity.mainLooper)
    private var reader: android.media.ImageReader? = null
    private var display: android.hardware.display.VirtualDisplay? = null
    private var stopped = false

    private val callback = object : android.media.projection.MediaProjection.Callback() {
        override fun onStop() = TemplateScreenServiceController.stop(activity)
    }

    init {
        projection.registerCallback(callback, handler)
    }

    override suspend fun grabFrame(timeoutMs: Long): android.graphics.Bitmap? {
        val metrics = activity.resources.displayMetrics
        val newReader = android.media.ImageReader.newInstance(
            metrics.widthPixels, metrics.heightPixels, android.graphics.PixelFormat.RGBA_8888, 2,
        )
        reader = newReader
        val newDisplay = projection.createVirtualDisplay(
            "zhique-zq-screen",
            metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            newReader.surface, null, null,
        )
        display = newDisplay
        val image = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<android.media.Image> { cont ->
                newReader.setOnImageAvailableListener({ r ->
                    val img = r.acquireLatestImage()
                    if (img != null && cont.isActive) cont.resume(img)
                }, handler)
            }
        } ?: return null
        return try {
            val bitmap = android.graphics.Bitmap.createBitmap(
                metrics.widthPixels, metrics.heightPixels, android.graphics.Bitmap.Config.ARGB_8888,
            )
            bitmap.copyPixelsFromBuffer(image.planes[0].buffer)
            bitmap
        } finally {
            image.close()
        }
    }

    override fun stop() {
        if (stopped) return
        stopped = true
        runCatching { display?.release() }
        runCatching { reader?.close() }
        runCatching { projection.unregisterCallback(callback) }
        runCatching { projection.stop() }
        TemplateScreenServiceController.stop(activity)
    }
}

/**
 * 截屏专用前台服务（:app ZqScreenService 的壳内联版）：API34+ 起 MediaProjection
 * 必须持有 foregroundServiceType="mediaProjection" 的前台服务。
 */
class TemplateScreenService : android.app.Service() {

    override fun onBind(intent: android.content.Intent?): android.os.IBinder? = null

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(android.app.NotificationManager::class.java)
        nm?.createNotificationChannel(
            android.app.NotificationChannel(CHANNEL_ID, "屏幕投影", android.app.NotificationManager.IMPORTANCE_LOW),
        )
        val notification: android.app.Notification = android.app.Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("屏幕投影进行中")
            .setContentText("网页正在截取屏幕画面，结束后自动停止")
            .setOngoing(true)
            .build()
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    companion object {
        const val CHANNEL_ID = "zhique-screen"
        const val NOTIFICATION_ID = 42
    }
}

/** 前台服务启停。 */
object TemplateScreenServiceController {
    fun start(context: Context) {
        context.startForegroundService(android.content.Intent(context, TemplateScreenService::class.java))
    }

    fun stop(context: Context) {
        context.stopService(android.content.Intent(context, TemplateScreenService::class.java))
    }
}

/**
 * 壳内授权卡（原生 View 版，视觉语义与织雀 Compose 卡一致：哪个项目、要什么、
 * 干什么 + 授予/拒绝）。挂在壳根布局最上层，点按经 prompt.answer 结算。
 */
object TemplatePermissionCard {

    private const val INDIGO = 0xFF46509F.toInt()
    private const val RED = 0xFFC0392B.toInt()
    private const val SURFACE = 0xFF1C1D26.toInt()
    private const val ON_SURFACE = 0xFFF2F3F7.toInt()

    /** 显示授权卡；返回挂上的卡片视图（宿主负责移除）。 */
    fun show(root: FrameLayout, prompt: TemplatePermissionPrompt, pending: TemplatePermissionPrompt.Pending): View {
        val ctx = root.context
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(SURFACE)
            setPadding(dp(ctx, 20), dp(ctx, 18), dp(ctx, 20), dp(ctx, 14))
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 16).toFloat()
                setColor(SURFACE)
            }
            elevation = dp(ctx, 12).toFloat()
            addView(text(ctx, "权限请求", 18f, true, ON_SURFACE))
            addView(
                text(
                    ctx,
                    "「${pending.ask.projectName}」想要使用「" +
                        (Capability.fromId(pending.ask.capability)?.title ?: pending.ask.capability) +
                        "」",
                    15f, true, ON_SURFACE,
                ).apply { setPadding(0, dp(ctx, 10), 0, 0) },
            )
            addView(
                text(ctx, pending.ask.why, 13f, false, 0xFFA8ADB8.toInt())
                    .apply { setPadding(0, dp(ctx, 4), 0, 0) },
            )
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(0, dp(ctx, 14), 0, 0)
            }
            row.addView(
                button(ctx, "拒绝", RED) {
                    detach(root, this@apply)
                    prompt.answer(false)
                },
            )
            row.addView(
                button(ctx, "授予", INDIGO) {
                    detach(root, this@apply)
                    prompt.answer(true)
                }.apply {
                    (layoutParams as LinearLayout.LayoutParams).marginStart = dp(ctx, 10)
                },
            )
            addView(row)
        }
        val wrap = FrameLayout(ctx).apply {
            setBackgroundColor(0x66000000)
            addView(
                card,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    gravity = Gravity.BOTTOM
                    setMargins(dp(ctx, 14), 0, dp(ctx, 14), dp(ctx, 18))
                },
            )
        }
        root.addView(
            wrap,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
        return wrap
    }

    /** 矩阵状态的小圆点标签（权限中心同款四色态，壳内暂用于调试信息）。 */
    fun stateLabel(state: PState): String = when (state) {
        PState.GRANTED -> "授予"
        PState.ASKING -> "询问"
        PState.DENIED -> "拒绝"
        PState.NOT_ASKED -> "未申请"
    }

    private fun detach(root: FrameLayout, card: View) {
        (card.parent as? FrameLayout)?.let { root.removeView(it) }
    }

    private fun text(ctx: Context, s: String, sizeSp: Float, bold: Boolean, color: Int): TextView =
        TextView(ctx).apply {
            text = s
            setTextColor(color)
            textSize = sizeSp
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun button(ctx: Context, label: String, color: Int, onClick: () -> Unit): Button =
        Button(ctx).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 14f
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 22).toFloat()
                setColor(color)
            }
            setPadding(dp(ctx, 22), dp(ctx, 8), dp(ctx, 22), dp(ctx, 8))
            stateListAnimator = null
            setOnClickListener { onClick() }
        }

    internal fun dp(ctx: Context, v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()
}
