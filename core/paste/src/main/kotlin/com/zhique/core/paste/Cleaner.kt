package com.zhique.core.paste

/** 一条清洗动作：动作类型 + 命中处原文摘录（限长，供预览屏展开查看）。 */
data class CleanAction(val kind: String, val excerpt: String)

/**
 * 清洗严格度（规格 §7「智能粘贴 → 清洗严格度」，M9 补齐）：
 * - [STANDARD]：全量剥离——剥围栏 + 剥行号污染 + 剔说明文字（现行行为，默认）；
 * - [CONSERVATIVE]：保守——只剥结构标记（围栏），保留行号污染与说明文字原样。
 */
enum class CleanStrict { STANDARD, CONSERVATIVE }

/**
 * 清洗结果。[blocks] 是按围栏解析、已剥污染的代码块（含语言标注，供组装引擎路由）；
 * [text] 为 blocks 的拼接视图；[original] 保留原始输入——「整体撤销」= 用它重跑（规格 §4.1.4）。
 */
data class CleanResult(
    val text: String,
    val blocks: List<CodeBlock>,
    val actions: List<CleanAction>,
    val original: String,
)

/**
 * 清洗器：剥 markdown 围栏（经 [FenceParser]）、剥行号前缀污染、剔除围栏外说明文字。
 * 清洗确定性：同一输入重跑结果一致（测试断言 data class 相等）。
 * [strict] 档位见 [CleanStrict]；撤销路径（enabled=false）不受档位影响。
 */
object Cleaner {

    const val ACTION_STRIP_FENCE = "剥离围栏"
    const val ACTION_STRIP_LINE_NO = "剥除行号前缀"
    const val ACTION_TRIM_PROSE = "剔除说明文字"

    /** 报告摘录限长。 */
    const val EXCERPT_MAX = 48

    fun clean(raw: String, enabled: Boolean = true, strict: CleanStrict = CleanStrict.STANDARD): CleanResult {
        if (raw.isBlank()) return CleanResult("", emptyList(), emptyList(), raw)

        val doc = FenceParser.parse(raw)

        // 撤销清洗 = 原始输入重跑：行号污染、说明文字等内容全部保留、不出报告。
        // 围栏是结构标记而非内容，仍按块剥离——否则 ``` 会被塞进 <script>/<style>，
        // 产出不可运行（撤销后同样要能组装出完整文档）。
        if (!enabled) {
            return if (doc.blocks.isEmpty()) {
                CleanResult(raw.trim(), emptyList(), emptyList(), raw)
            } else {
                CleanResult(doc.blocks.joinToString("\n\n") { it.code.trim() }, doc.blocks, emptyList(), raw)
            }
        }

        val actions = mutableListOf<CleanAction>()

        // 无围栏：只做行号污染剥离（保守档保留行号原样）
        if (doc.blocks.isEmpty()) {
            val trimmed = raw.trim()
            if (strict == CleanStrict.CONSERVATIVE) {
                return CleanResult(trimmed, emptyList(), emptyList(), raw)
            }
            val stripped = stripLineNumberPrefixes(trimmed)
            if (stripped != trimmed) {
                actions += CleanAction(ACTION_STRIP_LINE_NO, firstNumberedLine(trimmed))
            }
            return CleanResult(stripped, emptyList(), actions, raw)
        }

        if (strict == CleanStrict.CONSERVATIVE) {
            // 保守：只剥围栏（结构标记），块内行号污染保留
            for (fence in doc.fenceLines) {
                actions += CleanAction(ACTION_STRIP_FENCE, fence.take(EXCERPT_MAX))
            }
            val blocks = doc.blocks.map { CodeBlock(it.lang, it.code.trim()) }
            return CleanResult(blocks.joinToString("\n\n") { it.code }, blocks, actions, raw)
        }

        if (doc.prose.isNotBlank()) {
            actions += CleanAction(ACTION_TRIM_PROSE, doc.prose.take(EXCERPT_MAX))
        }
        for (fence in doc.fenceLines) {
            actions += CleanAction(ACTION_STRIP_FENCE, fence.take(EXCERPT_MAX))
        }
        val blocks = doc.blocks.map { b ->
            val cleaned = stripLineNumberPrefixes(b.code)
            if (cleaned != b.code) {
                actions += CleanAction(ACTION_STRIP_LINE_NO, firstNumberedLine(b.code))
            }
            CodeBlock(b.lang, cleaned.trim())
        }
        return CleanResult(blocks.joinToString("\n\n") { it.code }, blocks, actions, raw)
    }

    private fun firstNumberedLine(code: String): String =
        code.lines().firstOrNull { PasteClassifier.LINE_NUMBERED.containsMatchIn(it) }
            ?.trim()?.take(EXCERPT_MAX) ?: ""
}
