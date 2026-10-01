package com.zhique.runner.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 「输出·思考·上下文」设置（规格 §7 AI 服务商子节点，M9）：
 * 输出上限 16384 · 自动续写段数 3 · 思考默认折叠 · 上下文上限 0=按模型窗口 ·
 * 工作预算占比 75% · 自动压缩阈值 80%。
 * 读写走设置域共享 DataStore；取数经 [AiPrefsSnapshot]（suspend 全量读）。
 */
class AiPreferences(private val store: DataStore<Preferences>) {

    data class Snapshot(
        val maxOutputTokens: Int,
        val continueSegments: Int,
        val thinkingCollapsed: Boolean,
        val contextLimit: Int, // 0 = 按模型窗口
        val budgetPercent: Int, // 工作预算占比 %
        val compactThresholdPercent: Int, // 自动压缩阈值 %
    )

    val maxOutputTokens: Flow<Int> = store.data.map { it[MAX_OUTPUT_TOKENS] ?: DEFAULT_MAX_OUTPUT_TOKENS }
    val continueSegments: Flow<Int> = store.data.map { it[CONTINUE_SEGMENTS] ?: DEFAULT_CONTINUE_SEGMENTS }
    val thinkingCollapsed: Flow<Boolean> = store.data.map { it[THINKING_COLLAPSED] ?: true }
    val contextLimit: Flow<Int> = store.data.map { it[CONTEXT_LIMIT] ?: 0 }
    val budgetPercent: Flow<Int> = store.data.map { it[BUDGET_PERCENT] ?: 75 }
    val compactThresholdPercent: Flow<Int> = store.data.map { it[COMPACT_THRESHOLD] ?: 80 }

    suspend fun snapshot(): Snapshot = Snapshot(
        maxOutputTokens = maxOutputTokens.first(),
        continueSegments = continueSegments.first(),
        thinkingCollapsed = thinkingCollapsed.first(),
        contextLimit = contextLimit.first(),
        budgetPercent = budgetPercent.first(),
        compactThresholdPercent = compactThresholdPercent.first(),
    )

    suspend fun setMaxOutputTokens(v: Int) {
        require(v in RANGE_OUTPUT) { "输出上限须 1024–131072" }
        store.edit { it[MAX_OUTPUT_TOKENS] = v }
    }

    suspend fun setContinueSegments(v: Int) {
        require(v in 0..10) { "续写段数须 0–10（0=关闭改提示）" }
        store.edit { it[CONTINUE_SEGMENTS] = v }
    }

    suspend fun setThinkingCollapsed(v: Boolean) = store.edit { it[THINKING_COLLAPSED] = v }

    /** 0 = 按模型窗口（AiWiring 三层解析的手动覆盖层，0 表示不覆盖）。 */
    suspend fun setContextLimit(v: Int) {
        require(v == 0 || v in 4096..2_000_000) { "上下文上限 0 或 4096–2000000" }
        store.edit { it[CONTEXT_LIMIT] = v }
    }

    suspend fun setBudgetPercent(v: Int) {
        require(v in 10..100) { "工作预算占比须 10–100%" }
        store.edit { it[BUDGET_PERCENT] = v }
    }

    suspend fun setCompactThresholdPercent(v: Int) {
        require(v in 50..95) { "自动压缩阈值须 50–95%" }
        store.edit { it[COMPACT_THRESHOLD] = v }
    }

    companion object {
        const val DEFAULT_MAX_OUTPUT_TOKENS = 16384
        const val DEFAULT_CONTINUE_SEGMENTS = 3
        val RANGE_OUTPUT = 1024..131_072

        val MAX_OUTPUT_TOKENS = intPreferencesKey("ai_max_output_tokens")
        val CONTINUE_SEGMENTS = intPreferencesKey("ai_continue_segments")
        val THINKING_COLLAPSED = booleanPreferencesKey("ai_thinking_collapsed")
        val CONTEXT_LIMIT = intPreferencesKey("ai_context_limit")
        val BUDGET_PERCENT = intPreferencesKey("ai_budget_percent")
        val COMPACT_THRESHOLD = intPreferencesKey("ai_compact_threshold")
    }
}
