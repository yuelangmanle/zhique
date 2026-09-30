package com.zhique.core.ai

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow

/**
 * 防截断自动续写状态机（规格 §4.4.1，计划 Task 3.4 ★）。
 *
 * 流式监听截断信号（Done(LENGTH)，由三协议适配器归一）→ 自动携带已输出尾部为前缀
 * 发续写请求 → 行级重叠去重拼接；默认最多续 3 段（设置可调 0–10，0 = 关闭改为提示）。
 * 超限抛 [Truncated]（partial 不丢内容），由 UI 挂警告条 + 「继续输出」按钮走 [continueOnce]，
 * 绝不静默截断。思考内容跨段累计，但永不写进续写请求的消息历史（按协议丢弃）。
 */
class TruncationContinuer(
    private val chat: suspend (ChatRequest) -> Flow<StreamEvent>,
    private val maxSegments: Int = DEFAULT_MAX_SEGMENTS,
) {

    init {
        require(maxSegments in 0..MAX_SEGMENTS_CAP) { "maxSegments 须在 0–$MAX_SEGMENTS_CAP" }
    }

    /** 成功产物：[segments] 为已自动续写段数（UI「已续写 N 段」徽标）。 */
    data class Out(
        val content: String,
        val thinking: String,
        val segments: Int,
        val limitHit: Boolean,
    )

    /** 续写额度用尽仍触顶：partial 携带全部已拼接内容（含触顶段）。 */
    class Truncated(val partial: String, val segments: Int) :
        Exception("已达输出上限：续写 $segments 段后仍被截断")

    /**
     * [onEvent] 供 UI 做流式展示（逐字增量），不影响状态机判定；默认空实现零开销。
     */
    suspend fun generate(
        req: ChatRequest,
        onEvent: (StreamEvent) -> Unit = {},
    ): Out {
        var content = ""
        var thinking = ""
        var seg = 0
        var r = req
        while (true) {
            var stop: StopReason = StopReason.STOP
            val segBuf = StringBuilder()
            chat(r).collect { e ->
                onEvent(e)
                when (e) {
                    is StreamEvent.ContentDelta -> segBuf.append(e.text)
                    is StreamEvent.ThinkingDelta -> thinking += e.text
                    is StreamEvent.Done -> stop = e.stopReason
                    is StreamEvent.ToolCallDelta -> {} // 续写管线只关心文本流
                }
            }
            content = if (seg == 0) segBuf.toString() else stitch(content, segBuf.toString())
            when (val s = stop) {
                StopReason.STOP -> return Out(content, thinking, seg, limitHit = false)
                StopReason.LENGTH -> {
                    if (seg >= maxSegments) throw Truncated(content, seg)
                    seg++
                    val tail = tailCodePoints(content, TAIL_WINDOW) // 前缀窗口防膨胀（代理对安全）
                    r = req.copy(
                        messages = req.messages +
                            ChatMessage(ROLE_ASSISTANT, tail) +
                            ChatMessage(ROLE_USER, CONTINUE_PROMPT),
                    )
                }
                is StopReason.ERROR -> throw AiErrorException(s.msg)
                StopReason.CANCELLED -> throw CancellationException("user cancel")
            }
        }
    }

    /** 手动续一段（Truncated 警告条的「继续输出」按钮）：同管线单段，仍触顶时 limitHit=true。 */
    suspend fun continueOnce(
        partial: String,
        req: ChatRequest,
        onEvent: (StreamEvent) -> Unit = {},
    ): Out {
        var stop: StopReason = StopReason.STOP
        val segBuf = StringBuilder()
        val cont = req.copy(
            messages = req.messages +
                ChatMessage(ROLE_ASSISTANT, tailCodePoints(partial, TAIL_WINDOW)) +
                ChatMessage(ROLE_USER, CONTINUE_PROMPT),
        )
        chat(cont).collect { e ->
            onEvent(e)
            when (e) {
                is StreamEvent.ContentDelta -> segBuf.append(e.text)
                is StreamEvent.Done -> stop = e.stopReason
                else -> {}
            }
        }
        return Out(
            content = stitch(partial, segBuf.toString()),
            thinking = "",
            segments = 1,
            limitHit = stop == StopReason.LENGTH,
        )
    }

    companion object {
        const val DEFAULT_MAX_SEGMENTS = 3
        const val MAX_SEGMENTS_CAP = 10

        /** 续写请求携带的已输出内容尾部窗口（字符）。 */
        const val TAIL_WINDOW = 4096
        const val ROLE_ASSISTANT = "assistant"
        const val ROLE_USER = "user"

        const val CONTINUE_PROMPT = "从上次中断处原样继续，不要重复已输出内容"
    }
}

/**
 * 拼接去重（行级双指针）：找前段尾与续段头最长公共重叠（≤4 行）并去一；
 * 代码边界（前段止于行中、续段首为空行）→ 仅当**前段尾是悬空 token**
 * （运算符/逗号/开括号/`=` 或 return 等后接残缺标识符）才去空行直接衔接，
 * 否则保留换行（避免把两行并行合并）。
 * 换行边界归一：前段尾换行/续段头换行先剥掉再按行匹配，避免空行假重叠。
 */
internal fun stitch(prev: String, next: String): String {
    if (next.isEmpty()) return prev
    if (prev.isEmpty()) return next
    val prevEndsNl = prev.endsWith("\n")
    val nextStartsNl = next.startsWith("\n")
    val prevLines = prev.split('\n').let { if (prevEndsNl && it.size > 1) it.dropLast(1) else it }
    val nextLines = next.split('\n').let { if (nextStartsNl && it.size > 1) it.drop(1) else it }
    if (prevLines.isEmpty()) return next
    if (nextLines.isEmpty()) return prev

    val maxOverlap = minOf(4, prevLines.size, nextLines.size)
    for (k in maxOverlap downTo 1) {
        if (prevLines.takeLast(k) == nextLines.take(k)) {
            return (prevLines + nextLines.drop(k)).joinToString("\n")
        }
    }
    // 无行级重叠：仅前段止于悬空 token 且续段以换行开头 → 去掉续段首个换行直接衔接
    return if (!prevEndsNl && nextStartsNl && danglingTail(prev)) {
        prev + next.removePrefix("\n")
    } else {
        prev + next
    }
}

/** 行尾悬空 token：标点运算符/开括号收尾，或 `=`/return/await/new/case/throw 后接残缺标识符。 */
private val DANGLING_TAIL_KEYWORD = Regex("""(?:=|\breturn|\bawait|\bnew|\bcase|\bthrow)\s*[A-Za-z0-9_]*$""")
private const val DANGLING_PUNCT = "([{'\",;:?!.+-*/%<>=&|~^"

internal fun danglingTail(prev: String): Boolean {
    val last = prev.substringAfterLast('\n').trimEnd()
    if (last.isEmpty()) return false
    if (DANGLING_PUNCT.contains(last.last())) return true
    return DANGLING_TAIL_KEYWORD.containsMatchIn(last)
}

/** 按 code point 安全取尾部 n 字符（不切半代理对，如 emoji）。 */
internal fun tailCodePoints(s: String, n: Int): String {
    if (s.length <= n) return s
    var start = s.length - n
    // 起点落在低代理上（高代理被排除）→ 回退一位把整对保留进来
    if (start > 0 && s[start].isLowSurrogate()) start--
    return s.substring(start)
}
