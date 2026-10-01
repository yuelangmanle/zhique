package com.zhique.runner.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.zhique.runner.MainActivity

/**
 * 通知三事件（规格 §7 通知子节点，M9）：导出完成 / Agent 完成 / 新版本。
 * Application.onCreate 注册渠道；三处控制器经 [Notifier] 触发，
 * 开关由 GeneralPreferences 的三枚布尔键过滤（触发侧先查开关）。
 */
object ZhiqueNotifications {

    const val CHANNEL_EXPORT_DONE = "export_done"
    const val CHANNEL_AGENT_DONE = "agent_done"
    const val CHANNEL_NEW_VERSION = "new_version"
    const val NOTIFICATION_TAG = "zhique_event"

    /** 渠道注册（幂等，Application.onCreate 调一次）。 */
    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val channels = listOf(
            NotificationChannel(
                CHANNEL_EXPORT_DONE, "导出完成", NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "APK 打包与签名完成提醒" },
            NotificationChannel(
                CHANNEL_AGENT_DONE, "Agent 完成", NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "Agent 会话任务完成提醒" },
            NotificationChannel(
                CHANNEL_NEW_VERSION, "新版本", NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "自更新通道发现新版本" },
        )
        channels.forEach { nm.createNotificationChannel(it) }
    }

    /**
     * 事件通知入口（控制器注入缝）。开关过滤由调用方（AppContainer）完成；
     * 通知权限未授予时静默跳过（M 级权限 Android 13+ 动态申请，拒绝不崩溃）。
     */
    fun notify(context: Context, channel: String, title: String, body: String, notificationId: Int) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        if (nm.getNotificationChannel(channel) == null) return // 渠道未注册（未走 ensureChannels）
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        runCatching { nm.notify(NOTIFICATION_TAG, notificationId, notification) }
    }
}

/** 控制器注入缝签名：channel + title + body。 */
typealias EventNotifier = (channel: String, title: String, body: String) -> Unit
