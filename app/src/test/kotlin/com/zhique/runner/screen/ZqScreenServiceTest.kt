package com.zhique.runner.screen

import android.content.ComponentName
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/** 审查修复 #3：MediaProjection 前台服务启动逻辑 + manifest 声明。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ZqScreenServiceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `start发出前台服务intent_指向ZqScreenService`() {
        ZqScreenServiceController.start(context)
        val intent = Shadows.shadowOf(context as android.app.Application).nextStartedService
        assertNotNull(intent)
        assertEquals(
            ComponentName(context, ZqScreenService::class.java),
            intent.component,
            "截屏前必须拉起 mediaProjection 前台服务",
        )
    }

    @Test
    fun `stop停止服务`() {
        ZqScreenServiceController.stop(context)
        val stopped = Shadows.shadowOf(context as android.app.Application).nextStoppedService
        assertNotNull(stopped)
        assertEquals(ComponentName(context, ZqScreenService::class.java), stopped.component)
    }

    @Test
    fun `manifest声明mediaProjection前台服务类型`() {
        val info = context.packageManager.getServiceInfo(
            ComponentName(context, ZqScreenService::class.java),
            0,
        )
        val type = info.foregroundServiceType
        assertTrue(
            (type and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) != 0,
            "foregroundServiceType 必须含 mediaProjection（API34+ 硬性要求），实际: $type",
        )
        assertEquals(false, info.exported)
    }
}
