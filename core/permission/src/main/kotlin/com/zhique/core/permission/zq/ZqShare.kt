package com.zhique.core.permission.zq

import android.content.Intent
import androidx.core.content.FileProvider
import com.zhique.core.permission.Capability
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/** zq.share：ACTION_SEND 文本/项目内文件（文件经 FileProvider 授权只读 URI）。 */
class ZqShare : ZqCapability {

    override val ns = "share"
    override val required = Capability.SHARE
    override val methods = listOf("text", "file")

    override fun why(fn: String): String = when (fn) {
        "text" -> "唤起系统分享面板分享一段文本"
        else -> "唤起系统分享面板分享项目内文件"
    }

    override suspend fun call(fn: String, args: JsonObject, env: ZqEnv): JsonElement {
        val activity = env.activity ?: throw IllegalStateException("无宿主界面可发起分享")
        val context = env.appContext ?: throw IllegalStateException("无宿主环境")
        when (fn) {
            "text" -> {
                val text = args.zqText("text")
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                activity.startActivity(Intent.createChooser(send, "分享文本"))
            }
            "file" -> {
                val rel = args.zqText("path")
                val f = ZqPaths.resolveInSandbox(env.projectDir, rel)
                require(f.isFile) { "不是文件: $rel" }
                val mime = args.zqOptText("mime") ?: guessMime(f)
                val uri = FileProvider.getUriForFile(activity, "${context.packageName}.zqfile", f)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                activity.startActivity(Intent.createChooser(send, "分享文件"))
            }
            else -> throw IllegalArgumentException("zq.share 未知方法: $fn")
        }
        return buildJsonObject { put("ok", true) }
    }

    private fun guessMime(f: File): String = when (f.extension.lowercase()) {
        "html", "htm" -> "text/html"
        "js" -> "text/javascript"
        "css" -> "text/css"
        "json" -> "application/json"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "wav" -> "audio/wav"
        "mp3" -> "audio/mpeg"
        "mp4" -> "video/mp4"
        else -> "application/octet-stream"
    }
}
