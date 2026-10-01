package com.zhique.runner.notify

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner
import org.junit.runner.RunWith

/** M9 通知三事件：渠道注册与触发（Robolectric shadow 断言）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ZhiqueNotificationsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `三渠道注册幂等`() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ZhiqueNotifications.ensureChannels(context)
        assertNotNull(nm.getNotificationChannel(ZhiqueNotifications.CHANNEL_EXPORT_DONE))
        assertNotNull(nm.getNotificationChannel(ZhiqueNotifications.CHANNEL_AGENT_DONE))
        assertNotNull(nm.getNotificationChannel(ZhiqueNotifications.CHANNEL_NEW_VERSION))
        // 幂等：重复注册不崩
        ZhiqueNotifications.ensureChannels(context)
    }

    @Test
    fun `notify落系统通知并可按id区分`() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val shadow = shadowOf(nm)
        // Android 13+ POST_NOTIFICATIONS 动态权限：测试里显式授予
        org.robolectric.Shadows.shadowOf(context as android.app.Application)
            .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        ZhiqueNotifications.ensureChannels(context)
        ZhiqueNotifications.notify(
            context, ZhiqueNotifications.CHANNEL_EXPORT_DONE, "导出完成", "星空 v1 已打包", 1001,
        )
        ZhiqueNotifications.notify(
            context, ZhiqueNotifications.CHANNEL_NEW_VERSION, "织雀新版本", "发现 v0.2.0", 1002,
        )
        assertEquals(2, shadow.allNotifications.size)
        val all = shadow.allNotifications
        assertTrue(all.any { it.extras.getString(android.app.Notification.EXTRA_TITLE) == "导出完成" })
        assertTrue(all.any { it.extras.getString(android.app.Notification.EXTRA_TITLE) == "织雀新版本" })
    }

    @Test
    fun `权限未授予静默跳过不崩溃`() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val shadow = shadowOf(nm)
        ZhiqueNotifications.ensureChannels(context)
        // Robolectric 默认未授予 POST_NOTIFICATIONS → notify 内部直接返回
        ZhiqueNotifications.notify(
            context, ZhiqueNotifications.CHANNEL_AGENT_DONE, "Agent 完成", "任务完成", 1003,
        )
        assertEquals(0, shadow.allNotifications.size, "权限未授予时静默跳过")
    }

    @Test
    fun `渠道缺失静默跳过不崩溃`() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val shadow = shadowOf(nm)
        assertNull(nm.getNotificationChannel("unknown_channel"))
        org.robolectric.Shadows.shadowOf(context as android.app.Application)
            .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        ZhiqueNotifications.notify(context, "unknown_channel", "t", "b", 1004)
        assertEquals(0, shadow.allNotifications.size, "渠道未注册时静默跳过")
    }
}
