package com.zhique.core.permission.zq

import android.util.Base64
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import com.zhique.core.permission.Capability
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * zq.camera：CameraX 拍照存项目目录（photos/），可选 dataURL 回传（计划 Task 5.2）。
 * 矩阵授权（camera）之外还要求系统 CAMERA 权限在系统层已授予——未授予时
 * 返回可操作错误，页面优雅降级。真机验收留 M10。
 */
class ZqCamera : ZqCapability {

    override val ns = "camera"
    override val required = Capability.CAMERA
    override val methods = listOf("capture")

    override fun why(fn: String) = "拍照并把照片存入项目目录"

    override suspend fun call(fn: String, args: JsonObject, env: ZqEnv): JsonElement {
        require(fn == "capture") { "zq.camera 未知方法: $fn" }
        val context = env.appContext ?: throw IllegalStateException("无宿主环境")
        val owner = env.activity as? LifecycleOwner
            ?: throw IllegalStateException("宿主不支持相机生命周期")
        if (!SystemPerms.granted(context, android.Manifest.permission.CAMERA)) {
            throw IllegalStateException("系统相机权限未授予（请到系统设置授权织雀的相机）")
        }
        val dataUrl = args.zqOptBool("dataUrl")
        val front = args.zqOptText("facing") == "front"
        val dir = File(env.projectDir, "photos").apply { mkdirs() }
        val out = File(dir, "zq-${System.currentTimeMillis()}.jpg")

        withContext(Dispatchers.Main) {
            val provider = ProcessCameraProvider.getInstance(context).awaitOnMain(context)
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            val selector = if (front) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }
            provider.unbindAll()
            provider.bindToLifecycle(owner, selector, capture)
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
}
