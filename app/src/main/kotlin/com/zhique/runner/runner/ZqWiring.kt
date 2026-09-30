package com.zhique.runner.runner

import android.app.Activity
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.zhique.core.permission.OsPermissionGateway
import com.zhique.core.permission.PermissionRegistry
import com.zhique.core.permission.zq.ProjectionGateway
import com.zhique.core.permission.zq.ProjectionSession
import com.zhique.core.permission.zq.SafGateway
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
import com.zhique.core.permission.zq.ZqW3CRouter
import com.zhique.core.project.ProjectMeta
import com.zhique.core.web.GeolocationGateway
import com.zhique.core.web.PermissionGateway
import com.zhique.core.web.WebViewHost
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * zq 全能力桥接线（M5）：把 [ZqDispatcher] 与十个能力挂到运行器宿主，
 * 并注入 W3C 权限网关（getUserMedia + geolocation → 同一注册表矩阵）与
 * OS 运行时权限网关（授权卡授予后立即发起系统申请）。
 */
object ZqWiring {

    fun install(
        project: ProjectMeta,
        projectDir: File,
        context: Context,
        host: WebViewHost,
        scope: CoroutineScope,
        registry: PermissionRegistry,
    ): ZqDispatcher {
        val activity = context as? ComponentActivity
        val osGateway = activity?.let { ActivityOsPermissionGateway(it) }
        val env = ZqEnv(
            projectId = project.id,
            projectDir = projectDir,
            scope = scope,
            registry = registry,
            evaluateJs = host::evaluate,
            appContext = context.applicationContext,
            activity = context as? Activity,
            saf = activity?.let { ActivitySafGateway(it) },
            projection = activity?.let { ActivityProjectionGateway(it) },
            osPermissions = osGateway,
        )
        val dispatcher = ZqDispatcher(env)
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
        dispatcher.attachTo(host.zqRouter)
        val w3c = ZqW3CRouter(registry, project.id, scope, osPermissions = osGateway)
        host.permissionGateway = PermissionGateway { request ->
            val resources = request.resources ?: emptyArray()
            w3c.onRequest(
                resources.toList(),
                grant = { request.grant(request.resources ?: resources) },
                deny = { request.deny() },
            )
        }
        // W3C geolocation → 同一矩阵 + 系统门 + 使用计数（审查修复 #2）
        host.geolocationGateway = com.zhique.core.web.GeolocationGateway { _, allow ->
            w3c.onGeolocation(allow)
        }
        return dispatcher
    }

    /**
     * 相机取景浮层（审查修复 #4）：zq.camera.startPreview 绑定 Preview 后，
     * Compose 层据此挂取景视图；stopPreview 清除。
     */
    @androidx.compose.runtime.Composable
    fun ZqCameraPreviewOverlay(modifier: Modifier = Modifier) {
        val state by com.zhique.core.permission.zq.CameraPreviewBus.state.collectAsState()
        val preview = state ?: return
        val view = com.zhique.core.permission.zq.CameraPreviewBus.previewView ?: return
        Box(modifier.fillMaxSize()) {
            AndroidView(
                factory = { view },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .width(110.dp)
                    .aspectRatio(3f / 4f)
                    .clip(RoundedCornerShape(12.dp))
                    .testTag("zq-camera-preview"),
            )
            Text(
                "取景：${preview.facing}",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 132.dp, end = 12.dp)
                    .testTag("zq-camera-preview-label"),
            )
        }
    }
}

/** SAF pick/save 网关：Activity Result Registry 直挂（运行中页面必然 STARTED）。 */
class ActivitySafGateway(private val activity: ComponentActivity) : SafGateway {

    private val seq = java.util.concurrent.atomic.AtomicInteger()

    private suspend fun <I, O> launch(
        contract: ActivityResultContract<I, O>,
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
            ActivityResultContracts.OpenDocument(),
            arrayOf(mime ?: "*/*"),
            "zq-saf-pick-${seq.incrementAndGet()}",
        )
        if (uri == null) return buildJsonObject { put("code", "canceled") }
        // 立即读、不存 URI（规格 §4.4 同款纪律）
        val content = runCatching {
            activity.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        return buildJsonObject {
            put("path", uri.toString())
            put("content", content ?: "")
        }
    }

    override suspend fun save(name: String, mime: String?, content: String): JsonElement {
        val uri = launch(
            ActivityResultContracts.CreateDocument(mime ?: "text/plain"),
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

/** MediaProjection 网关：createScreenCaptureIntent 弹系统投影授权（独立授权）。 */
class ActivityProjectionGateway(private val activity: ComponentActivity) : ProjectionGateway {

    private val seq = java.util.concurrent.atomic.AtomicInteger()

    override suspend fun session(): ProjectionSession? {
        val mpm = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
            as? android.media.projection.MediaProjectionManager ?: return null
        val result = suspendCancellableCoroutine<Pair<Int, android.content.Intent?>?> { cont ->
            val contract = object : ActivityResultContract<Unit, Pair<Int, android.content.Intent?>?>() {
                override fun createIntent(context: Context, input: Unit): android.content.Intent =
                    mpm.createScreenCaptureIntent()

                override fun parseResult(resultCode: Int, intent: android.content.Intent?): Pair<Int, android.content.Intent?>? =
                    resultCode to intent
            }
            val launcher = activity.activityResultRegistry.register(
                "zq-projection-${seq.incrementAndGet()}",
                contract,
            ) { result -> cont.resume(result) }
            cont.invokeOnCancellation { launcher.unregister() }
            launcher.launch(Unit)
        } ?: return null
        val (code, data) = result
        if (code != android.app.Activity.RESULT_OK || data == null) return null
        // API34+：getMediaProjection/createVirtualDisplay 前必须先起 mediaProjection 前台服务
        com.zhique.runner.screen.ZqScreenServiceController.start(activity)
        val projection = mpm.getMediaProjection(code, data) ?: return null
        return MediaProjectionSession(activity, projection)
    }
}

/**
 * MediaProjection → [ProjectionSession] 适配（审查修复 I1）：[stop] 反注册
 * 回调 + 释放 VirtualDisplay/ImageReader + projection.stop()，幂等；
 * 投影系统级停止时自动收掉前台服务。
 */
private class MediaProjectionSession(
    private val activity: ComponentActivity,
    private val projection: android.media.projection.MediaProjection,
) : ProjectionSession {

    private val handler = android.os.Handler(activity.mainLooper)
    private var reader: android.media.ImageReader? = null
    private var display: android.hardware.display.VirtualDisplay? = null
    private var stopped = false

    private val callback = object : android.media.projection.MediaProjection.Callback() {
        override fun onStop() {
            com.zhique.runner.screen.ZqScreenServiceController.stop(activity)
        }
    }

    init {
        projection.registerCallback(callback, handler)
    }

    override suspend fun grabFrame(timeoutMs: Long): android.graphics.Bitmap? {
        val metrics = activity.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val newReader = android.media.ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2)
        reader = newReader
        val newDisplay = projection.createVirtualDisplay(
            "zhique-zq-screen",
            width, height, metrics.densityDpi,
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
            val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
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
        com.zhique.runner.screen.ZqScreenServiceController.stop(activity)
    }
}

/**
 * OS 运行时权限网关（审查修复 #1）：RequestMultiplePermissions 经
 * Activity Result Registry 直挂——授权卡点「授予」后由 OsGate 立即调用，
 * 系统确认框在授权流程内弹出。
 */
class ActivityOsPermissionGateway(private val activity: ComponentActivity) : OsPermissionGateway {

    private val seq = java.util.concurrent.atomic.AtomicInteger()

    override fun granted(permissions: List<String>): Boolean = permissions.all {
        androidx.core.content.ContextCompat.checkSelfPermission(activity, it) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    override suspend fun request(permissions: List<String>): Map<String, Boolean> =
        suspendCancellableCoroutine { cont ->
            val launcher = activity.activityResultRegistry.register(
                "zq-os-perm-${seq.incrementAndGet()}",
                ActivityResultContracts.RequestMultiplePermissions(),
            ) { result -> cont.resume(result) }
            cont.invokeOnCancellation { launcher.unregister() }
            launcher.launch(permissions.toTypedArray())
        }
}
