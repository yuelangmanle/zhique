package com.zhique.core.permission.zq

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.zhique.core.permission.Capability
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** zq.clipboard：get/set（前台调用语义，规格 §2.1 不做后台监听）。 */
class ZqClipboard : ZqCapability {

    override val ns = "clipboard"
    override val required = Capability.CLIPBOARD
    override val methods = listOf("get", "set")

    override fun why(fn: String): String = when (fn) {
        "get" -> "读取剪贴板文本"
        else -> "写入剪贴板文本"
    }

    override suspend fun call(fn: String, args: JsonObject, env: ZqEnv): JsonElement {
        val context = env.appContext ?: throw IllegalStateException("无宿主环境")
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: throw IllegalStateException("剪贴板服务不可用")
        return when (fn) {
            "get" -> {
                val text = runCatching {
                    cm.primaryClip?.getItemAt(0)?.text?.toString()
                }.getOrNull()
                buildJsonObject { put("text", text ?: "") }
            }
            "set" -> {
                val text = args.zqText("text")
                cm.setPrimaryClip(ClipData.newPlainText("zhique", text))
                buildJsonObject { put("ok", true) }
            }
            else -> throw IllegalArgumentException("zq.clipboard 未知方法: $fn")
        }
    }
}
