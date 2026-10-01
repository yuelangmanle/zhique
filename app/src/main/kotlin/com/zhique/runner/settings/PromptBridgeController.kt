package com.zhique.runner.settings

import android.content.Context
import android.content.Intent
import com.zhique.core.paste.PromptBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 织雀提示词桥控制器（Task 8.2，规格 §4.10）：API 多选 + 生成预览 + 复制/系统分享。
 * 生成纯函数、零 IO；CompatHint 反向兜底入口经 [setIdea] 预填用户原句。
 */
class PromptBridgeController(
    private val bridge: PromptBridge = PromptBridge(),
) {

    data class UiState(
        val idea: String = "",
        val selected: Set<String> = emptySet(),
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun setIdea(value: String) {
        _state.update { it.copy(idea = value) }
    }

    /** 勾选/取消一个 zq 能力（未勾选的 API 不携带其文档段）。 */
    fun toggle(id: String) {
        _state.update { s ->
            s.copy(selected = if (id in s.selected) s.selected - id else s.selected + id)
        }
    }

    /** 生成提示词全文（外部 AI 直接可用）。 */
    fun preview(): String = bridge.generate(_state.value.idea, _state.value.selected)

    /** 复制全文到剪贴板（提示词不含任何密钥，可安全上剪贴板）。 */
    fun copyToClipboard(context: Context): String {
        val text = preview()
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        cm?.setPrimaryClip(android.content.ClipData.newPlainText("织雀提示词", text))
        return text
    }

    /** 系统分享 Intent（用户自选外部 AI App）。 */
    fun shareIntent(): Intent =
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, preview())
                putExtra(Intent.EXTRA_TITLE, "织雀提示词")
            },
            "把织雀提示词分享到外部 AI",
        )
}
