package com.zhique.core.permission.zq

import android.graphics.Bitmap
import android.util.Base64
import com.zhique.core.permission.Capability
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * zq.screen：MediaProjection 截屏存项目目录 screenshots/（计划 Task 5.2）。
 * 独立授权：能力矩阵走 screen 条目，系统层另需投影弹窗（经 [ProjectionGateway]
 * 由 :app 侧换取会话，前台服务保障也在 :app 适配层）。
 *
 * 审查修复 I1：**finally 里必调 [ProjectionSession.stop]**（含异常路径）——
 * 反注册回调并停投影，杜绝投影指示灯/常驻通知滞留与多次累积泄漏。
 * 真机验收留 M10。
 */
class ZqScreen : ZqCapability {

    override val ns = "screen"
    override val required = Capability.SCREEN
    override val methods = listOf("capture")

    override fun why(fn: String) = "抓取一帧屏幕画面存入项目目录（系统会单独弹投影授权）"

    override suspend fun call(fn: String, args: JsonObject, env: ZqEnv): JsonElement {
        require(fn == "capture") { "zq.screen 未知方法: $fn" }
        val session = env.projection?.session()
            ?: throw IllegalStateException("截屏需系统投影授权（未接入投影网关）")
        val dataUrl = args.zqOptBool("dataUrl")
        try {
            val bitmap = session.grabFrame(CAPTURE_TIMEOUT_MS)
                ?: throw IllegalStateException("截屏失败（无可用帧）")
            val dir = File(env.projectDir, "screenshots").apply { mkdirs() }
            val out = File(dir, "zq-${System.currentTimeMillis()}.png")
            withContext(Dispatchers.IO) {
                out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            return buildJsonObject {
                put("path", out.relativeTo(env.projectDir).path)
                if (dataUrl) {
                    require(out.length() <= ZqLimits.MAX_INLINE_BYTES) { "too large" }
                    put("dataUrl", "data:image/png;base64," + Base64.encodeToString(out.readBytes(), Base64.NO_WRAP))
                }
            }
        } finally {
            // 成功/失败一律结束会话：反注册回调 + 停投影（stop 幂等）
            runCatching { session.stop() }
        }
    }

    companion object {
        const val CAPTURE_TIMEOUT_MS = 5_000L
    }
}
