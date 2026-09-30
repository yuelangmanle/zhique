package com.zhique.runner.screen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * 截屏专用前台服务（审查修复 #3）：API34+ 起使用 MediaProjection 必须持有
 * `foregroundServiceType="mediaProjection"` 的前台服务，否则
 * createVirtualDisplay 抛 SecurityException。投影使用期间常驻通知。
 */
class ZqScreenService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "屏幕投影", NotificationManager.IMPORTANCE_LOW),
        )
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("屏幕投影进行中")
            .setContentText("网页正在截取屏幕画面，结束后自动停止")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val CHANNEL_ID = "zhique-screen"
        const val NOTIFICATION_ID = 42
    }
}

/** 前台服务启动/停止（Robolectric 可测的启动逻辑；截屏前先 start）。 */
object ZqScreenServiceController {

    /** API34+ 约束：投影使用前必须先把 mediaProjection 前台服务拉起。 */
    fun start(context: Context) {
        context.startForegroundService(Intent(context, ZqScreenService::class.java))
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, ZqScreenService::class.java))
    }
}
