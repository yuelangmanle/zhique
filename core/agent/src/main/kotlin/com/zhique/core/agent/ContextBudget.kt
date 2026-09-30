package com.zhique.core.agent

import com.zhique.core.ai.ModelCatalog

/**
 * 上下文预算（规格 §4.5 第 4 条，决策 27）：
 * 上下文上限三层解析（手动覆盖 > 模型目录窗口 > 默认；0 = 按模型窗口自动），
 * 工作预算默认 = 窗口 75%（可调），自动压缩阈值默认 80%（可调）。
 */
data class ContextBudget(
    val contextLimit: Int,
    val workRatio: Float = DEFAULT_WORK_RATIO,
    val autoThreshold: Float = DEFAULT_AUTO_THRESHOLD,
) {
    init {
        require(contextLimit > 0) { "contextLimit 须 > 0" }
        require(workRatio in 0.1f..1f) { "workRatio 须在 0.1–1" }
        require(autoThreshold in 0.1f..1f) { "autoThreshold 须在 0.1–1" }
    }

    /** 工作预算（tokens）：组装与用量考核的口径。 */
    val workLimit: Int get() = (contextLimit * workRatio).toInt().coerceAtLeast(1)

    fun usage(tokens: Int): Float = tokens.toFloat() / workLimit

    companion object {
        const val DEFAULT_WORK_RATIO = 0.75f
        const val DEFAULT_AUTO_THRESHOLD = 0.8f

        /** 上限三层解析：手动（>0）> 模型目录（contextWindow）> 默认。 */
        fun resolve(model: String, manual: Int?): ContextBudget =
            ContextBudget(contextLimit = ModelCatalog.resolveContextWindow(model, manual))
    }
}
