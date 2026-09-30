package com.zhique.core.agent

import com.zhique.core.ai.ChatMessage
import com.zhique.core.ai.ChatRequest
import com.zhique.core.ai.ModelCatalog
import com.zhique.core.ai.StreamEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * 上下文压缩（规格 §4.5 第 4 条，决策 27 ★）：
 * 用快循环角色模型把早期轮次压成摘要；任务目标、⭐标记结论、最近 [KEEP_RECENT] 轮原文
 * 是**压缩不变量**——任何情况不进摘要、不丢弃。
 * 手动 [compactNow] 立即执行；自动 [maybeAuto] 在用量 ≥ 阈值时后台触发（不打断当前轮）。
 * 产物 [CompressionReport] 供 UI 摘要卡（保留/丢弃清单 + token 前后对比）。
 */
class Compactor(
    private val fastChat: suspend (ChatRequest) -> Flow<StreamEvent>,
    private val requestTemplate: ChatRequest,
) {

    data class CompressionReport(
        val kept: List<String>,
        val dropped: List<String>,
        val before: Int,
        val after: Int,
        val manual: Boolean,
    )

    /** 手动/同步压缩：轮次不足返回 null（无可压缩）。 */
    suspend fun compactNow(assembler: MemoryContextAssembler, manual: Boolean = true): CompressionReport? {
        val turns = assembler.turnsSnapshot()
        if (turns.size <= KEEP_RECENT) return null
        val mid = turns.dropLast(KEEP_RECENT)
        val starred = mid.filter { it.starred }
        // 只压缩普通轮次：工具结果走滚动窗口、摘要轮不重复压缩
        val toSummarize = mid.filter { !it.starred && it.kind == Turn.Kind.NORMAL }
        if (toSummarize.isEmpty()) return null

        val before = assembler.estimateTokens()
        val summary = summarize(assembler.goal, starred, toSummarize)
        val dropped = toSummarize.map { "${it.role}: ${it.content.take(40)}…" }
        assembler.applyCompaction(summary)
        return CompressionReport(
            kept = listOf(
                "任务目标（原文）",
                "⭐ 关键结论 ×${starred.size}",
                "最近 $KEEP_RECENT 轮原文",
                "压缩摘要 1 条",
            ),
            dropped = dropped,
            before = before,
            after = assembler.estimateTokens(),
            manual = manual,
        )
    }

    /** 自动触发：用量 ≥ 阈值 → [scope] 后台执行（当前轮不被打断）。返回是否触发。 */
    fun maybeAuto(assembler: MemoryContextAssembler, scope: CoroutineScope): Boolean {
        if (assembler.usage() < assembler.budget.autoThreshold) return false
        scope.launch { compactNow(assembler, manual = false) }
        return true
    }

    private suspend fun summarize(
        goal: String,
        starred: List<Turn>,
        toSummarize: List<Turn>,
    ): String {
        val req = requestTemplate.copy(
            messages = listOf(
                ChatMessage("system", COMPACT_SYSTEM_PROMPT),
                ChatMessage(
                    "user",
                    buildString {
                        appendLine("任务目标（必须原样保留）：$goal")
                        starred.forEach { appendLine("⭐ 关键结论（必须原样保留）：${it.content}") }
                        appendLine("待压缩对话：")
                        toSummarize.forEach { appendLine("${it.role}: ${it.content.take(SUMMARY_INPUT_CLIP)}") }
                    },
                ),
            ),
            // 快循环 4096 上限（M3 遗留接线 a 的生产消费点：摘要任务够用，省钱防拖沓）
            maxTokens = ModelCatalog.FAST_LOOP_MAX_OUTPUT,
            tools = emptyList(),
        )
        var content = ""
        fastChat(req).collect { e ->
            if (e is StreamEvent.ContentDelta) content += e.text
        }
        return content.ifBlank { "（摘要生成失败，早期轮次已并入要点占位）" }
    }

    companion object {
        const val KEEP_RECENT = MemoryContextAssembler.KEEP_RECENT

        /** 固定压缩提示词（规格：必须原样保留任务目标、⭐标记结论、最近 4 轮）。 */
        const val COMPACT_SYSTEM_PROMPT =
            "你是对话压缩器。压缩以下对话为要点，必须原样保留：任务目标、⭐标记结论、最近 4 轮。" +
                "只输出要点文本，不要解释。"

        private const val SUMMARY_INPUT_CLIP = 600
    }
}
