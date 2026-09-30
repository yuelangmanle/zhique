package com.zhique.core.permission.zq

import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.util.Base64
import com.zhique.core.permission.Capability
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * zq.screen：MediaProjection 截屏存项目目录 screenshots/（计划 Task 5.2）。
 * 独立授权：能力矩阵走 screen 条目，系统层另需投影弹窗（经
 * [ProjectionGateway] 由 :app 侧 Activity Result 换取 MediaProjection）。
 * 真机验收留 M10。
 */
class ZqScreen : ZqCapability {

    override val ns = "screen"
    override val required = Capability.SCREEN
    override val methods = listOf("capture")

    override fun why(fn: String) = "抓取一帧屏幕画面存入项目目录（系统会单独弹投影授权）"

    override suspend fun call(fn: String, args: JsonObject, env: ZqEnv): JsonElement {
        require(fn == "capture") { "zq.screen 未知方法: $fn" }
        val context = env.appContext ?: throw IllegalStateException("无宿主环境")
        val projection = env.projection?.projection()
            ?: throw IllegalStateException("截屏需系统投影授权（未接入投影网关）")
        val dataUrl = args.zqOptBool("dataUrl")
        val metrics = context.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val dir = File(env.projectDir, "screenshots").apply { mkdirs() }
        val out = File(dir, "zq-${System.currentTimeMillis()}.png")

        withContext(Dispatchers.Main) {
            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() = Unit
            }, android.os.Handler(context.mainLooper))
            val reader = ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2)
            val display = projection.createVirtualDisplay(
                "zhique-zq-screen",
                width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, null,
            )
            val image = try {
                withTimeout(CAPTURE_TIMEOUT_MS) {
                    suspendCancellableCoroutine { cont ->
                        reader.setOnImageAvailableListener({ r ->
                            val img = r.acquireLatestImage()
                            if (img != null && cont.isActive) cont.resume(img)
                        }, android.os.Handler(context.mainLooper))
                    }
                }
            } finally {
                display?.release()
                reader.close()
            }
            try {
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(image.planes[0].buffer)
                withContext(Dispatchers.IO) {
                    out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            } finally {
                image.close()
            }
        }
        return buildJsonObject {
            put("path", out.relativeTo(env.projectDir).path)
            if (dataUrl) {
                put("dataUrl", "data:image/png;base64," + Base64.encodeToString(out.readBytes(), Base64.NO_WRAP))
            }
        }
    }

    companion object {
        const val CAPTURE_TIMEOUT_MS = 5_000L
    }
}
