package com.zhique.runner.paste

import com.zhique.core.paste.AiFallback
import com.zhique.core.paste.Assembled
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 粘贴预览控制器（HomeController 同款模式）：解析→清洗→组装全在 [io] 协程，
 * UI 态以 [state] StateFlow 暴露，主线程零磁盘 IO。
 * 「撤销清洗」语义 = 原始输入重跑（[Cleaner.clean] 的 enabled=false 路径）。
 *
 * 并发纪律：所有状态变更一律走 `MutableStateFlow.update {}`（CAS 原子，IO 协程里的
 * 重算结果不会覆盖并发进来的 setName/setCleaning）；保存类操作以 [UiState.busy]
 * 作重入闸（双击/连点不重复建项目）。
 */
class PastePreviewController(
    private val repo: ProjectRepository,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val autoRunStore: PasteAutoRunStore? = null,
    private val aiParser: AiFallback.Parser? = null,
    private val onToast: (String) -> Unit = {},
    private val onRun: (ProjectMeta) -> Unit = {},
) {

    data class UiState(
        val raw: String = "",
        val form: PasteForm? = null,
        val formLabel: String = "",
        val confidence: Float = 0f,
        val assembledHtml: String = "",
        val actions: List<CleanAction> = emptyList(),
        val hints: List<CompatHint> = emptyList(),
        val cleaningApplied: Boolean = true,
        val name: String = "",
        val autoRun: Boolean = false,
        val busy: Boolean = false,

        /** 置信度低于阈值 → 预览屏提示「AI 兜底解析」。 */
        val aiFallbackSuggested: Boolean = false,

        /** AI 兜底解析已应用（结构化结果替换规则组装；失败则保持规则结果）。 */
        val aiFallbackUsed: Boolean = false,

        /** 保存/落盘失败的用户可读提示（下次成功保存或重开管道时清除）。 */
        val error: String? = null,
    )

    private val _state = MutableStateFlow(UiState())

    /** 粘贴预览 UI 态。 */
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** AI 兜底解析器（异步接线后可注入；构造时未配 Provider 的场景）。 */
    @Volatile
    private var activeParser: AiFallback.Parser? = aiParser

    fun setAiParser(parser: AiFallback.Parser?) {
        activeParser = parser
    }

    /** 入口：剪贴板卡 / 系统分享 / 手动粘贴，统一从 [raw] 启动管道。 */
    fun start(raw: String) {
        scope.launch(io) {
            _state.update { UiState(raw = raw, busy = true, autoRun = it.autoRun) }
            autoRunStore?.let { store ->
                val stored = store.autoRun.first()
                _state.update { it.copy(autoRun = stored) }
            }
            recompute()
            // 自动运行：高置信且非 Unknown/ApiConfig 形态才自动；低置信必须停在预览（规格 §4.1.3），
            // ApiConfig 属 M3 转存流程，同样不自动运行
            val s = _state.value
            val form = s.form
            val autoEligible = form != null &&
                form !is PasteForm.Unknown &&
                form !is PasteForm.ApiConfig
            if (s.autoRun && autoEligible && !s.aiFallbackSuggested && s.raw.isNotBlank()) {
                saveAndRun()
            }
        }
    }

    fun setName(name: String) {
        _state.update { it.copy(name = name) }
    }

    /** 切换清洗开关并重跑（撤销/恢复清洗）。 */
    fun setCleaning(applied: Boolean) {
        scope.launch(io) {
            _state.update { it.copy(cleaningApplied = applied) }
            recompute()
        }
    }

    fun setAutoRun(value: Boolean) {
        scope.launch(io) {
            autoRunStore?.setAutoRun(value)
            _state.update { it.copy(autoRun = value) }
        }
    }

    fun saveAsDraft() {
        // 同步抢占闸（M2 债务收敛）：闸判定留在调用线程，主线程连点在协程派发前即被挡下
        // （闸放协程里时，两次点击的 launch 可都在 IO 排队后才判定 → 双建项目）
        val s = tryBeginSave() ?: return
        scope.launch(io) {
            val name = s.name.ifBlank { Assembler.DEFAULT_TITLE }
            runCatching { repo.create(name, s.assembledHtml) }
                .onSuccess {
                    _state.update { it.copy(error = null, busy = false) }
                    onToast("已存为草稿「$name」")
                }
                .onFailure { failSave(it) }
        }
    }

    fun saveAndRun() {
        val s = tryBeginSave() ?: return
        scope.launch(io) {
            val name = s.name.ifBlank { Assembler.DEFAULT_TITLE }
            val meta = runCatching { repo.create(name, s.assembledHtml) }
                .getOrElse { failSave(it); return@launch }
            _state.update { it.copy(error = null, busy = false) }
            onRun(meta)
        }
    }

    // ---- internals ----

    /** 原子抢占保存闸：raw 为空或已在保存中返回 null（双击/连点防重入）。 */
    private fun tryBeginSave(): UiState? {
        var captured: UiState? = null
        _state.update { s ->
            captured = null
            if (s.raw.isBlank() || s.busy) {
                s
            } else {
                captured = s
                s.copy(busy = true)
            }
        }
        return captured
    }

    private fun failSave(t: Throwable) {
        val msg = "保存失败：${t.message ?: t::class.simpleName}"
        _state.update { it.copy(error = msg, busy = false) }
        onToast(msg)
    }

    private suspend fun recompute() {
        val snapshot = _state.value
        if (snapshot.raw.isBlank()) {
            _state.update { s ->
                s.copy(
                    form = null,
                    formLabel = label(PasteForm.Unknown("")),
                    confidence = 1f,
                    assembledHtml = "",
                    actions = emptyList(),
                    hints = emptyList(),
                    aiFallbackSuggested = false,
                    error = null,
                    busy = false,
                )
            }
            return
        }
        // 重活只依赖 raw/cleaningApplied 快照；结果合并走 update，保住并发进来的 name 等编辑
        val classified = PasteClassifier().classify(snapshot.raw)
        val cleaned = Cleaner.clean(snapshot.raw, enabled = snapshot.cleaningApplied)
        var assembled = Assembler.assemble(classified.form, cleaned)
        val hints = CompatScanner.scan(assembled.html)

        // AI 兜底（规格 §4.1.3）：置信度 < 阈值时走快循环角色「仅解析重组」；
        // 失败/异常保留规则组装结果（原文入库语义不变，永不丢数据）
        val fallbackUsed = classified.confidence < PasteConfidence.AI_FALLBACK_THRESHOLD &&
            activeParser != null &&
            classified.form is PasteForm.Unknown
        var fallbackTitle: String? = null
        var fallbackApplied = false
        if (fallbackUsed) {
            val aiResult = runCatching { activeParser?.parse(snapshot.raw) }.getOrNull()
            if (aiResult != null && aiResult.html.isNotBlank()) {
                assembled = Assembled(aiResult.html, aiResult.title)
                fallbackTitle = aiResult.title
                fallbackApplied = true
            }
        }

        _state.update { s ->
            s.copy(
                form = classified.form,
                formLabel = label(classified.form),
                confidence = classified.confidence,
                assembledHtml = assembled.html,
                actions = cleaned.actions,
                hints = hints,
                name = s.name.ifBlank { fallbackTitle ?: assembled.title ?: Assembler.DEFAULT_TITLE },
                aiFallbackSuggested = classified.confidence < PasteConfidence.AI_FALLBACK_THRESHOLD,
                aiFallbackUsed = fallbackApplied,
                error = null,
                busy = false,
            )
        }
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
