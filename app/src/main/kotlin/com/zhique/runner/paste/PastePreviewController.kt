package com.zhique.runner.paste

import com.zhique.core.paste.Assembler
import com.zhique.core.paste.CleanAction
import com.zhique.core.paste.Cleaner
import com.zhique.core.paste.CompatHint
import com.zhique.core.paste.CompatScanner
import com.zhique.core.paste.PasteClassifier
import com.zhique.core.paste.PasteConfidence
import com.zhique.core.paste.PasteForm
import com.zhique.core.project.ProjectMeta
import com.zhique.core.project.ProjectRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 粘贴预览控制器（HomeController 同款模式）：解析→清洗→组装全在 [io] 协程，
 * UI 态以 [state] StateFlow 暴露，主线程零磁盘 IO。
 * 「撤销清洗」语义 = 原始输入重跑（[Cleaner.clean] 的 enabled=false 路径）。
 */
class PastePreviewController(
    private val repo: ProjectRepository,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val autoRunStore: PasteAutoRunStore? = null,
    private val onToast: (String) -> Unit = {},
    private val onRun: (ProjectMeta) -> Unit = {},
) {

    data class UiState(
        val raw: String = "",
        val formLabel: String = "",
        val confidence: Float = 0f,
        val assembledHtml: String = "",
        val actions: List<CleanAction> = emptyList(),
        val hints: List<CompatHint> = emptyList(),
        val cleaningApplied: Boolean = true,
        val name: String = "",
        val autoRun: Boolean = false,
        val busy: Boolean = false,

        /** 置信度低于阈值 → 预览屏提示「AI 兜底解析」（M4 接线）。 */
        val aiFallbackSuggested: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())

    /** 粘贴预览 UI 态。 */
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** 入口：剪贴板卡 / 系统分享 / 手动粘贴，统一从 [raw] 启动管道。 */
    fun start(raw: String) {
        scope.launch(io) {
            _state.value = UiState(raw = raw, busy = true, autoRun = _state.value.autoRun)
            autoRunStore?.let { store ->
                _state.value = _state.value.copy(autoRun = store.autoRun.first())
            }
            recompute()
            // 自动运行：高置信才自动，低置信必须停在预览（规格 §4.1.3）
            val s = _state.value
            if (s.autoRun && !s.aiFallbackSuggested && s.raw.isNotBlank()) {
                saveAndRun()
            }
        }
    }

    fun setName(name: String) {
        _state.value = _state.value.copy(name = name)
    }

    /** 切换清洗开关并重跑（撤销/恢复清洗）。 */
    fun setCleaning(applied: Boolean) {
        scope.launch(io) {
            _state.value = _state.value.copy(cleaningApplied = applied)
            recompute()
        }
    }

    fun setAutoRun(value: Boolean) {
        scope.launch(io) {
            autoRunStore?.setAutoRun(value)
            _state.value = _state.value.copy(autoRun = value)
        }
    }

    fun saveAsDraft() {
        scope.launch(io) {
            val s = _state.value
            if (s.raw.isBlank() || s.busy) return@launch
            repo.create(s.name.ifBlank { Assembler.DEFAULT_TITLE }, s.assembledHtml)
            onToast("已存为草稿「${s.name.ifBlank { Assembler.DEFAULT_TITLE }}」")
        }
    }

    fun saveAndRun() {
        scope.launch(io) {
            val s = _state.value
            if (s.raw.isBlank() || s.busy) return@launch
            val meta = repo.create(s.name.ifBlank { Assembler.DEFAULT_TITLE }, s.assembledHtml)
            onRun(meta)
        }
    }

    // ---- internals ----

    private suspend fun recompute() {
        val s = _state.value
        if (s.raw.isBlank()) {
            _state.value = s.copy(
                formLabel = label(PasteForm.Unknown("")),
                confidence = 1f,
                assembledHtml = "",
                actions = emptyList(),
                hints = emptyList(),
                name = s.name,
                busy = false,
            )
            return
        }
        val classified = PasteClassifier().classify(s.raw)
        val cleaned = Cleaner.clean(s.raw, enabled = s.cleaningApplied)
        val assembled = Assembler.assemble(classified.form, cleaned)
        _state.value = s.copy(
            formLabel = label(classified.form),
            confidence = classified.confidence,
            assembledHtml = assembled.html,
            actions = cleaned.actions,
            hints = CompatScanner.scan(assembled.html),
            name = s.name.ifBlank { assembled.title ?: Assembler.DEFAULT_TITLE },
            aiFallbackSuggested = classified.confidence < PasteConfidence.AI_FALLBACK_THRESHOLD,
            busy = false,
        )
    }

    companion object {
        /** 形态中文名（解析结果卡展示）。 */
        fun label(form: PasteForm): String = when (form) {
            is PasteForm.CompleteHtml -> "完整 HTML"
            is PasteForm.MixedBlocks -> "文字混排代码块"
            is PasteForm.Fragments -> "多代码块片段"
            is PasteForm.JsOnly -> "纯 JS 片段"
            is PasteForm.CssOnly -> "纯 CSS 片段"
            is PasteForm.ApiConfig -> "接口配置"
            is PasteForm.Unknown -> "未识别"
        }
    }
}
