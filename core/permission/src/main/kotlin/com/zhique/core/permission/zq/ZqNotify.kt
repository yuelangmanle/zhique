package com.zhique.core.permission.zq

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.zhique.core.permission.Capability
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * zq.notification：channel 创建 + post（规格 §4.6）。
 *
 * W3C Notification API 桥接选了实现更简的一条（审查修复 #2）：WebView 不暴露
 * Notification 构造器，织雀桥 JS 在缺失时用 `zq.notification.requestPermission/post`
 * 兜出 `window.Notification`（zhique-bridge.js）；权限查询/申请即本矩阵的
 * notification 条目，走同一张授权卡 + [com.zhique.core.permission.OsGate]。
 * 系统通知开关未开时给出可操作错误（拒绝对网页是优雅信号）。
 */
class ZqNotify : ZqCapability {

    override val ns = "notification"
    override val required = Capability.NOTIFICATION
    override val methods = listOf("post", "requestPermission")

    override fun why(fn: String): String = when (fn) {
        "requestPermission" -> "允许网页发送系统通知（Notification API）"
        else -> "发送一条系统通知"
    }

    override suspend fun call(fn: String, args: JsonObject, env: ZqEnv): JsonElement = when (fn) {
        // 调度层已完成矩阵授权 + 系统权限门；走到这里必为 GRANTED → W3C 语义 "granted"
        "requestPermission" -> buildJsonObject { put("permission", "granted") }
        "post" -> post(args, env)
        else -> throw IllegalArgumentException("zq.notification 未知方法: $fn")
    }

    private suspend fun post(args: JsonObject, env: ZqEnv): JsonElement {
        val context = env.appContext ?: throw IllegalStateException("无宿主环境")
        val title = args.zqText("title")
        val body = args.zqOptText("body") ?: ""
        val id = args.zqOptInt("id", (System.currentTimeMillis() % 100000).toInt(), 1, Int.MAX_VALUE)

        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) {
            throw IllegalStateException("系统通知未开启（可在授权时一并完成；如被永久拒绝请到系统设置开启织雀的通知）")
        }
        val channel = NotificationChannel(CHANNEL_ID, "网页通知", NotificationManager.IMPORTANCE_DEFAULT)
        nm.createNotificationChannel(channel)
        val notification: Notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(id, notification)
        } catch (t: Throwable) {
            throw IllegalStateException(
                if (t is SecurityException) "系统通知权限未授予（可重试授权，或在系统设置中开启）" else "通知发送失败: ${t.message}",
                t,
            )
        }
        return buildJsonObject { put("posted", true); put("id", id) }
    }

    companion object {
        const val CHANNEL_ID = "zhique-zq"
    }
}
