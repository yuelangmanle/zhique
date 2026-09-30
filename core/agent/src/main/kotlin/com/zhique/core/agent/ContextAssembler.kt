package com.zhique.core.agent

import com.zhique.core.ai.ChatMessage
import com.zhique.core.ai.ChatRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** 会话轮次（近期轮次层）。[kind] 区分普通轮 / 工具结果（滚动窗口）/ 压缩摘要。 */
data class Turn(
    val role: String,
    val content: String,
    val starred: Boolean = false,
    val kind: Kind = Kind.NORMAL,
) {
    enum class Kind { NORMAL, TOOL_RESULT, SUMMARY }
}

/**
 * 三层记忆的会话上下文组装器（规格 §4.5，计划 Task 4.3 ★）。
 *
 * 组装序（预算裁剪序）：系统规范 > 项目记忆摘要 > 任务目标 > 文件地图 > 报错时间线 > 近期轮次；
 * 超出 [ContextBudget.workLimit] 从尾部丢（近期轮次最旧先丢 → 报错 → 文件地图），
 * 任务目标 / ⭐标记结论 / 最近 [KEEP_RECENT] 轮原文为不变量，任何裁剪不掉。
 *
 * read_file/grep 等工具结果走**滚动窗口**：不计入长期预算（[usage] 不统计），
 * 组装时只带最近 [TOOL_WINDOW] 条、单条截断 [TOOL_RESULT_CLIP] 字符。
 *
 * 用量 ≥ [ContextBudget.autoThreshold] 时经 [autoScope] 后台触发 [autoCompact]
 * （不打断当前轮；压缩管线由 Compactor 提供，此处只挂钩）。
 */
class MemoryContextAssembler(
    val budget: ContextBudget,
    private val requestTemplate: ChatRequest,
    private val memory: Memory? = null,
    private val projectId: String? = null,
    private val fileMapProvider: () -> String = { "" },
    private val autoCompact: (suspend (MemoryContextAssembler) -> Unit)? = null,
    private val autoScope: CoroutineScope? = null,
) : ContextAssembler {

    /** 任务目标原文（不变量）。 */
    var goal: String = ""
        private set

    private val turnsInternal = mutableListOf<Turn>()
    private val errorLinesInternal = ArrayDeque<String>()
    private val compacting = AtomicBoolean(false)

    /** 已完成轮次快照（Compactor 消费）。 */
    fun turnsSnapshot(): List<Turn> = synchronized(turnsInternal) { turnsInternal.toList() }

    /** 报错时间线快照（UI/调试用）。 */
    fun errorsSnapshot(): List<String> = synchronized(errorLinesInternal) { errorLinesInternal.toList() }

    fun appendTurn(role: String, content: String, starred: Boolean = false, kind: Turn.Kind = Turn.Kind.NORMAL) {
        synchronized(turnsInternal) { turnsInternal += Turn(role, content, starred, kind) }
        if (starred) memory?.addSessionNote("⭐ $content") // 关键结论同步沉淀进项目记忆
        maybeAutoCompact()
    }

    override fun appendTurn(role: String, content: String, starred: Boolean) =
        appendTurn(role, content, starred, Turn.Kind.NORMAL)

    override fun appendError(line: String) {
        synchronized(errorLinesInternal) {
            errorLinesInternal.addLast(line.take(ERROR_LINE_CAP))
            while (errorLinesInternal.size > ERROR_TIMELINE_CAP) errorLinesInternal.removeFirst()
        }
    }

    /** 长期预算用量（工具结果滚动窗口不计入）。 */
    override fun usage(): Float = budget.usage(estimateTokens())

    /** 长期记忆件 tokens（摘要卡 before/after 与用量共用口径）。 */
    fun estimateTokens(): Int =
        longTermPieces().sumOf { estimateTokens(it) } + turnsSnapshot()
            .filter { it.kind != Turn.Kind.TOOL_RESULT }
            .sumOf { estimateTokens(it.content) }

    /** 压缩落位：星标轮保留原位语义，非星标早期轮次替换为一条摘要轮。 */
    fun applyCompaction(summary: String) {
        synchronized(turnsInternal) {
            if (turnsInternal.size <= KEEP_RECENT) return
            val keep = turnsInternal.takeLast(KEEP_RECENT)
            val mid = turnsInternal.dropLast(KEEP_RECENT)
            val starredMid = mid.filter { it.starred }
            val summaryTurn = Turn(
                role = "assistant",
                content = "[已压缩上下文]\n$summary",
                kind = Turn.Kind.SUMMARY,
            )
            turnsInternal.clear()
            turnsInternal.addAll(starredMid + summaryTurn + keep)
        }
    }

    override fun build(goal: String, round: Int): ChatRequest {
        this.goal = goal
        val memorySummary = memorySummary()
        var fileMap = runCatching { fileMapProvider() }.getOrDefault("")
        var kept = turnsSnapshot()
        var errors = errorsSnapshot()

        fun overBudget(): Boolean {
            val tokens = listOf(systemSpec, memorySummary, goal).sumOf { estimateTokens(it) } +
                estimateTokens(fileMap) + errors.sumOf { estimateTokens(it) } +
                kept.sumOf { estimateTokens(it.content) }
            return tokens > budget.workLimit
        }

        // 1) 丢最旧的可丢轮次（星标与最近 KEEP_RECENT 轮受保护）
        while (overBudget()) {
            val idx = kept.indexOfFirst { t ->
                !t.starred && kept.indexOf(t) < kept.size - KEEP_RECENT
            }
            if (idx < 0) break
            kept = kept.toMutableList().also { it.removeAt(idx) }
        }
        // 2) 丢最旧报错
        while (overBudget() && errors.isNotEmpty()) errors = errors.drop(1)
        // 3) 丢文件地图
        if (overBudget()) fileMap = ""

        val system = buildSystem(memorySummary, fileMap, errors)
        val messages = buildList {
            add(ChatMessage("system", system))
            add(ChatMessage("user", "任务目标：$goal"))
            // 工具结果滚动窗口：只带最近 TOOL_WINDOW 条、单条截断
            val toolSlots = kept.withIndex().filter { it.value.kind == Turn.Kind.TOOL_RESULT }
                .map { it.index }.takeLast(TOOL_WINDOW)
            kept.forEachIndexed { idx, t ->
                when {
                    t.kind == Turn.Kind.TOOL_RESULT && idx !in toolSlots -> Unit
                    t.kind == Turn.Kind.TOOL_RESULT -> add(
                        ChatMessage("tool", t.content.take(TOOL_RESULT_CLIP)),
                    )
                    else -> add(ChatMessage(t.role, t.content))
                }
            }
        }
        return requestTemplate.copy(messages = messages)
    }

    // ---- internals ----

    private fun longTermPieces(): List<String> =
        listOf(systemSpec, memorySummary(), goal, runCatching { fileMapProvider() }.getOrDefault("")) +
            errorsSnapshot()

    private fun memorySummary(): String {
        val m = memory ?: return ""
        val id = projectId ?: return ""
        val global = m.globalText().trim()
        val project = m.projectText(id).trim()
        return (listOf(global, project).filter { it.isNotEmpty() }.joinToString("\n"))
            .take(MEMORY_SUMMARY_CLIP)
    }

    private fun buildSystem(memorySummary: String, fileMap: String, errors: List<String>): String =
        buildString {
            append(systemSpec)
            if (memorySummary.isNotBlank()) {
                append("\n\n## 项目记忆（全局 + zhique.md）\n").append(memorySummary)
            }
            if (fileMap.isNotBlank()) {
                append("\n\n## 文件地图（结构 + 符号索引；全文用 read_file/grep 按需取）\n").append(fileMap)
            }
            if (errors.isNotEmpty()) {
                append("\n\n## 近期报错时间线\n")
                errors.forEach { append("- ").append(it).append('\n') }
            }
        }

    private fun maybeAutoCompact() {
        val scope = autoScope ?: return
        val hook = autoCompact ?: return
        if (!compacting.compareAndSet(false, true)) return
        if (usage() < budget.autoThreshold) {
            compacting.set(false)
            return
        }
        scope.launch {
            try {
                hook(this@MemoryContextAssembler)
            } finally {
                compacting.set(false)
            }
        }
    }

    companion object {
        /** 系统规范（组装首位，永不裁剪）。 */
        const val SYSTEM_SPEC =
            "你是织雀 Agent（安卓端 HTML 项目自动调试）。系统规范：\n" +
                "- 遵循用户任务目标，小步修改、跑一次验一次\n" +
                "- 修改文件必须用 edit_file，改后必须 reload 验证\n" +
                "- 纯文本模型不要描述画面，用 read_dom_snapshot 观察\n" +
                "- 项目形态为静态 HTML（index.html + 资源），禁止引入构建链\n"

        const val KEEP_RECENT = 4
        const val TOOL_WINDOW = 4
        const val TOOL_RESULT_CLIP = 2000
        const val MEMORY_SUMMARY_CLIP = 4096
        const val ERROR_TIMELINE_CAP = 20
        const val ERROR_LINE_CAP = 300

        val systemSpec: String get() = SYSTEM_SPEC
    }
}

/** token 粗估（中英混合按 2 字符 ≈ 1 token，与 Chat 侧 estimateTokens 同口径）。 */
internal fun estimateTokens(text: String): Int = (text.length + 1) / 2
