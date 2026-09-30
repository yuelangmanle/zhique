package com.zhique.runner

import android.content.Intent
import kotlin.test.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 系统分享目标（M2）：onCreate 消费分享文本后必须清掉 intent extras——
 * 旋转重建会复用原 intent，不清会重新填充 sharedText 把用户弹回预览。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityShareIntentTest {

    @Test
    fun `分享文本消费后清空extras_旋转重建不重填`() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "分享的文本")
        }
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        assertNull(
            controller.get().intent.getStringExtra(Intent.EXTRA_TEXT),
            "onCreate 消费后应清空 extras",
        )
        // 旋转重建 = 复用原 intent 重走 onCreate；extras 未清则会重新填充 sharedText 弹回预览
        controller.recreate()
        assertNull(
            controller.get().sharedText.value,
            "旋转重建后不得重放分享文本",
        )
    }

    @Test
    fun `非文本分享不填充`() {
        val intent = Intent(Intent.ACTION_SEND).apply { type = "image/png" }
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        assertNull(controller.get().sharedText.value)
    }
}
