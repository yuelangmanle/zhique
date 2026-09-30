package com.zhique.core.permission.zq

import android.util.Base64
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import com.zhique.core.permission.Capability
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 相机取景浮层总线（审查修复 #4）：能力侧 [ZqCamera.startPreview] 绑定
 * CameraX Preview 后在此登记状态与 [PreviewView]，:app 的 Compose 层
 * （ZqCameraPreviewOverlay）据此挂取景浮层；stopPreview 清除。
 * 测试只触碰状态流与 facing 归一化（不触碰 Android 视图）。
 */
object CameraPreviewBus {

    data class PreviewState(val facing: String)

    private val _state = MutableStateFlow<PreviewState?>(null)
    val state: StateFlow<PreviewState?> = _state

    /** 绑定中的预览视图（PreviewView，以 View 类型外露避免传递依赖；主线程访问）。 */
    var previewView: android.view.View? = null
        private set

    internal fun show(facing: String, view: PreviewView?) {
        previewView = view
        _state.value = PreviewState(facing)
    }

    internal fun hide() {
        previewView = null
        _state.value = null
    }
}

/**
 * zq.camera：CameraX 拍照存项目目录（photos/，可选 dataURL）+ 取景浮层
 * `startPreview/stopPreview`（审查修复 #4：网页可要求取景，capture 仍返回文件）。
 * 矩阵授权（camera）+ 系统权限门之外，执行期自查系统 CAMERA 权限兜底。
 * 真机验收留 M10。
 */
class ZqCamera : ZqCapability {

    override val ns = "camera"
    override val required = Capability.CAMERA
    override val methods = listOf("capture", "startPreview", "stopPreview")

    override fun why(fn: String): String = when (fn) {
        "startPreview" -> "打开相机取景浮层（仅预览，不拍摄）"
        "stopPreview" -> "关闭相机取景浮层"
        else -> "拍照并把照片存入项目目录"
    }

    override suspend fun call(fn: String, args: JsonObject, env: ZqEnv): JsonElement {
        val context = env.appContext ?: throw IllegalStateException("无宿主环境")
        val owner = env.activity as? LifecycleOwner
            ?: throw IllegalStateException("宿主不支持相机生命周期")
        if (!SystemPerms.granted(context, android.Manifest.permission.CAMERA)) {
            throw IllegalStateException("系统相机权限未授予（可重新发起授权，或在系统设置中开启织雀的相机）")
        }
        return when (fn) {
            "capture" -> capture(args, env, context, owner)
            "startPreview" -> startPreview(args, env, context, owner)
            "stopPreview" -> stopPreview(context)
            else -> throw IllegalArgumentException("zq.camera 未知方法: $fn")
        }
    }

    private suspend fun capture(
        args: JsonObject,
        env: ZqEnv,
        context: android.content.Context,
        owner: LifecycleOwner,
    ): JsonElement {
        val dataUrl = args.zqOptBool("dataUrl")
        val dir = File(env.projectDir, "photos").apply { mkdirs() }
        val out = File(dir, "zq-${System.currentTimeMillis()}.jpg")
        val face = selectorFor(normalizeFacing(args.zqOptText("facing")))

        withContext(Dispatchers.Main) {
            val provider = ProcessCameraProvider.getInstance(context).awaitOnMain(context)
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            provider.unbindAll()
            provider.bindToLifecycle(owner, face, capture)
            val opts = ImageCapture.OutputFileOptions.Builder(out).build()
            suspendCancellableCoroutine { cont ->
                capture.takePicture(
                    opts,
                    androidx.core.content.ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                            cont.resume(Unit)
                        }

                        override fun onError(exception: ImageCaptureException) {
                            cont.resumeWithException(exception)
                        }
                    },
                )
            }
            provider.unbindAll()
        }
        return buildJsonObject {
            put("path", out.relativeTo(env.projectDir).path)
            if (dataUrl) {
                put("dataUrl", "data:image/jpeg;base64," + Base64.encodeToString(out.readBytes(), Base64.NO_WRAP))
            }
        }
    }

    /** 取景：绑定 CameraX Preview + 登记总线（:app Compose 层挂浮层）。 */
    private suspend fun startPreview(
        args: JsonObject,
        env: ZqEnv,
        context: android.content.Context,
        owner: LifecycleOwner,
    ): JsonElement {
        val facing = normalizeFacing(args.zqOptText("facing"))
        withContext(Dispatchers.Main) {
            val provider = ProcessCameraProvider.getInstance(context).awaitOnMain(context)
            val preview = Preview.Builder().build()
            val view = PreviewView(context)
            preview.setSurfaceProvider(view.surfaceProvider)
            provider.unbindAll()
            provider.bindToLifecycle(owner, selectorFor(facing), preview)
            CameraPreviewBus.show(facing, view)
        }
        return buildJsonObject {
            put("preview", true)
            put("facing", facing)
        }
    }

    private suspend fun stopPreview(context: android.content.Context): JsonElement {
        withContext(Dispatchers.Main) {
            runCatching {
                ProcessCameraProvider.getInstance(context).get().unbindAll()
            }
            CameraPreviewBus.hide()
        }
        return buildJsonObject { put("ok", true) }
    }

    /** ListenableFuture 挂起等待（不引 coroutines-guava，手写 await）。 */
    private suspend fun ListenableFuture<ProcessCameraProvider>.awaitOnMain(
        context: android.content.Context,
    ): ProcessCameraProvider = suspendCancellableCoroutine { cont ->
        addListener(
            {
                try {
                    cont.resume(get())
                } catch (t: Throwable) {
                    cont.resumeWithException(t)
                }
            },
            androidx.core.content.ContextCompat.getMainExecutor(context),
        )
    }

    companion object {
        /** facing 归一化：仅 "front" 视为前置，其余（含缺省/未知）一律后置。 */
        fun normalizeFacing(raw: String?): String = if (raw == "front") "front" else "back"

        fun selectorFor(facing: String): CameraSelector =
            if (facing == "front") CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
    }
}
