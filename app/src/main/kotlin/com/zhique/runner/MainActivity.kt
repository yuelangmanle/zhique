package com.zhique.runner

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.fragment.app.FragmentActivity
import com.zhique.runner.ui.theme.ZqTheme

/**
 * M2：系统分享目标（ACTION_SEND，text/ 任意子类型）直达智能粘贴预览。
 * launchMode=singleTask → 已打开时走 [onNewIntent]，分享文本经 [sharedText] 状态进入导航。
 * M9：改继承 FragmentActivity——应用锁的生物识别（BiometricPrompt）要求该宿主类型。
 */
class MainActivity : FragmentActivity() {

    /** 其他 App 分享来的文本；消费后置 null。 */
    val sharedText: MutableState<String?> = mutableStateOf(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        // 分享文本已消费即清掉 extras：旋转重建会复用原 intent，不清会重新填充
        // sharedText 把用户弹回预览（消费过一次的分享不应重放）
        intent?.replaceExtras(Bundle())
        val app = application as ZhiqueApplication
        setContent {
            ZqTheme {
                ZhiqueApp(app.container, sharedText)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
        intent.replaceExtras(Bundle())
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val type = intent.type ?: return
        if (!type.startsWith("text/")) return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (!text.isNullOrBlank()) sharedText.value = text
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val list = intent.getStringArrayListExtra(Intent.EXTRA_TEXT)
                val text = list?.firstOrNull { !it.isNullOrBlank() }
                if (!text.isNullOrBlank()) sharedText.value = text
            }
        }
    }
}
