package com.zhique.core.web

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.webkit.WebView
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * PixelCopy 抓 WebView 截图（App 自有内容，无需运行时授权，规格 §4.2）。
 *
 * 平台无 `PixelCopy.request(WebView, …)` 重载，走 `Window + Rect` 重载
 * （API 26 起稳定可用）：把 WebView 在窗口中的矩形拷进位图；
 * 拿不到 Activity 窗口时退回 `draw(Canvas)` 软件绘制。
 */
object WebSnapshot {

    /** 必须在主线程调用（WebView 与 PixelCopy 均只认主线程）。 */
    suspend fun capture(webView: WebView): Bitmap {
        check(Looper.myLooper() == Looper.getMainLooper()) { "snapshot() 必须在主线程调用" }
        val width = webView.width.coerceAtLeast(1)
        val height = webView.height.coerceAtLeast(1)
        val activity = webView.context.findActivity()
        if (activity == null) {
            return softwareDraw(webView, width, height)
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val loc = IntArray(2)
        webView.getLocationInWindow(loc)
        val rect = Rect(loc[0], loc[1], loc[0] + width, loc[1] + height)
        // rect 夹紧到窗口可视范围（WebView 半离屏时防止 PixelCopy 出错）
        val rootLoc = IntArray(2)
        activity.window.decorView.getLocationOnScreen(rootLoc)
        val root = Rect(
            rootLoc[0],
            rootLoc[1],
            rootLoc[0] + activity.window.decorView.width,
            rootLoc[1] + activity.window.decorView.height,
        )
        if (!rect.intersect(root) || rect.isEmpty) {
            return softwareDraw(webView, width, height)
        }
        return suspendCancellableCoroutine { cont ->
            var cancelled = false
            // PixelCopy 不可取消；取消后回调照常到达，resume 到已取消的续体是安全 no-op
            cont.invokeOnCancellation { cancelled = true }
            PixelCopy.request(
                activity.window,
                rect,
                bitmap,
                { result ->
                    if (!cancelled) {
                        if (result == PixelCopy.SUCCESS) {
                            cont.resume(bitmap)
                        } else {
                            cont.resumeWithException(IllegalStateException("PixelCopy failed: $result"))
                        }
                    }
                },
                Handler(Looper.getMainLooper()),
            )
        }
    }

    private fun softwareDraw(webView: WebView, width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { webView.draw(Canvas(it)) }

    private fun android.content.Context.findActivity(): Activity? {
        var ctx: android.content.Context = this
        while (ctx is android.content.ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }
}
