package com.zhique.runner.agent

/** diff 行：'-' 删除（红）/ '+' 新增（绿）/ ' ' 上下文。 */
data class DiffLine(val type: Char, val text: String)

/** 一步 edit_file 的差异卡数据。 */
data class DiffUi(val path: String, val lines: List<DiffLine>)

/**
 * 轻量行级 diff（M4 diff 卡）：公共前缀/后缀提取，中间段整体 - / +，
 * 前后各带 [context] 行上下文。行数小（HTML 项目），无需真 LCS。
 */
object SimpleDiff {

    fun diff(before: String, after: String, context: Int = 2): List<DiffLine> {
        val b = before.lines()
        val a = after.lines()
        var p = 0
        while (p < b.size && p < a.size && b[p] == a[p]) p++
        var s = 0
        while (s < b.size - p && s < a.size - p && b[b.size - 1 - s] == a[a.size - 1 - s]) s++
        val out = mutableListOf<DiffLine>()
        for (i in (p - context).coerceAtLeast(0) until p) out += DiffLine(' ', b[i])
        for (i in p until b.size - s) out += DiffLine('-', b[i])
        for (i in p until a.size - s) out += DiffLine('+', a[i])
        for (i in b.size - s until (b.size - s + context).coerceAtMost(b.size)) out += DiffLine(' ', b[i])
        return out
    }
}
