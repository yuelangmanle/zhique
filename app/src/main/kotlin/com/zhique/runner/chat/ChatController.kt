package com.zhique.runner.chat

import com.zhique.core.ai.AiError
import com.zhique.core.ai.AiErrorException
import com.zhique.core.ai.ChatMessage
import com.zhique.core.ai.ChatRequest
import com.zhique.core.ai.ModelCatalog
import com.zhique.core.ai.StreamEvent
import com.zhique.core.ai.TruncationContinuer
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 一条已完成的对话轮；assistant 轮携带思考与续写元数据（思考永不回传历史）。 */
data class ChatTurn(
    val role: String,
    val content: String,
    val thinking: String = "",
    val segments: Int = 0,
    val truncated: Boolean = false,
    val thinkingSeconds: Double = 0.0,
    val thinkingTokens: Int = 0,
)

data class ChatUiState(
    val turns: List<ChatTurn> = emptyList(),
    val input: String = "",
    val busy: Boolean = false,
    val streaming: Boolean = false,
    val liveContent: String = "",
    val liveThinking: String = "",
    val truncated: Boolean = false,
    val error: String? = null,
) {
    /** 顶栏「输出」环：本轮会话累计输出 tokens / 全局输出预算（规格 §4.4.1）。 */
    val outputTokens: Int
        get() = turns.filter { it.role == "assistant" }.sumOf { estimateTokens(it.content) }

    /** 顶栏「上下文」环：历史+当前草稿的估算占比（M4 接真值，现为估算）。 */
    fun contextFraction(contextWindow: Int): Float =
        if (contextWindow <= 0) 0f
        else (turns.sumOf { estimateTokens(it.content) + estimateTokens(it.thinking) }.toFloat() / contextWindow)
            .coerceIn(0f, 1.2f)
}

/** 流式缓冲：StringBuilder 复用 + 超上限仅保留尾部（防长思考/长正文撑爆内存与 O(n²) 拼接）。 */
internal class TailBuffer(private val cap: Int) {
    private val sb = StringBuilder()

    fun append(text: String): TailBuffer {
        sb.append(text)
        if (sb.length > cap) sb.delete(0, sb.length - cap)
        return this
    }

    fun value(): String = sb.toString()
    fun length(): Int = sb.length
    fun reset() = sb.setLength(0)
}

/**
 * 对话面板控制器（HomeController 模式：IO 协程 + StateFlow + update{}）。
 *
 * 管线：newRequest(历史) → TruncationContinuer.generate → 流式 UI 态 + 完成落轮。
 * 发送历史只含 role/content——思考内容按协议丢弃（X7：思考永不回传给模型）。
 */
class ChatController(
    private val chat: suspend (ChatRequest) -> Flow<StreamEvent>,
    private val newRequest: (List<ChatMessage>) -> ChatRequest,
    val contextWindow: Int = ModelCatalog.DEFAULT_CONTEXT_WINDOW, // M4 接目录真值
    maxSegments: Int = TruncationContinuer.DEFAULT_MAX_SEGMENTS,
    private val recordUsage: (suspend (tokens: Int) -> Unit)? = null, // M4：UsageMeter.record 挂点
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val continuer = TruncationContinuer(chat, maxSegments)
    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    /** 发送历史（仅 role/content；思考不进历史）。 */
    private val history = mutableListOf<ChatMessage>()
    private var lastRequest: ChatRequest? = null
    private var thinkingStartMs = 0L
    private val liveThinkingBuf = TailBuffer(LIVE_BUFFER_CAP)
    private val liveContentBuf = TailBuffer(LIVE_BUFFER_CAP)

    fun setInput(text: String) {
        _state.update { it.copy(input = text) }
    }

    fun send(text: String) {
        val body = text.trim()
        if (body.isEmpty() || _state.value.busy) return
        history += ChatMessage("user", body)
        liveThinkingBuf.reset()
        liveContentBuf.reset()
        _state.update {
            it.copy(
                turns = it.turns + ChatTurn(role = "user", content = body),
                input = "",
                busy = true,
                streaming = true,
                liveContent = "",
                liveThinking = "",
                truncated = false,
                error = null,
            )
        }
        scope.launch(io) {
            thinkingStartMs = 0L
            try {
                val req = newRequest(history.toList())
                lastRequest = req
                val out = continuer.generate(req, ::onStreamEvent)
                finishTurn(out.content, out.segments, truncated = false)
            } catch (e: TruncationContinuer.Truncated) {
                finishTurn(e.partial, e.segments, truncated = true)
            } catch (e: AiErrorException) {
                fail(e.message ?: "流内错误")
            } catch (e: AiError) {
                fail("${e::class.simpleName}：${e.message}")
            } catch (e: CancellationException) {
                fail("已取消")
                throw e
            }
        }
    }

    /** Truncated 警告条的「继续输出」：同管线手动续一段。 */
    fun continueOutput() {
        val s = _state.value
        if (!s.truncated || s.busy) return
        val req = lastRequest ?: return
        val partial = s.turns.lastOrNull { it.role == "assistant" }?.content ?: return
        scope.launch(io) {
            _state.update { it.copy(busy = true, streaming = true) }
            try {
                val out = continuer.continueOnce(partial, req, ::onStreamEvent)
                replaceLastAssistant(out.content, out.segments, out.limitHit)
                runCatching { recordUsage?.invoke(estimateTokens(out.content)) }
            } catch (e: TruncationContinuer.Truncated) {
                replaceLastAssistant(e.partial, e.segments + partialSegments(), limitHit = true)
            } catch (e: CancellationException) {
                fail("已取消")
                throw e
            } catch (e: Exception) {
                fail(e.message ?: "续写失败")
            }
        }
    }

    private fun partialSegments(): Int =
        _state.value.turns.lastOrNull { it.role == "assistant" }?.segments ?: 0

    private fun onStreamEvent(e: StreamEvent) {
        when (e) {
            is StreamEvent.ThinkingDelta -> {
                if (thinkingStartMs == 0L) thinkingStartMs = System.currentTimeMillis()
                liveThinkingBuf.append(e.text)
                _state.update { it.copy(liveThinking = liveThinkingBuf.value()) }
            }
            is StreamEvent.ContentDelta -> {
                liveContentBuf.append(e.text)
                _state.update { it.copy(liveContent = liveContentBuf.value()) }
            }
            else -> {}
        }
    }

    private fun thinkingSeconds(): Double {
        if (thinkingStartMs == 0L) return 0.0
        return (System.currentTimeMillis() - thinkingStartMs) / 1000.0
    }

    private suspend fun finishTurn(content: String, segments: Int, truncated: Boolean) {
        val thinking = liveThinkingBuf.value()
        val turn = ChatTurn(
            role = "assistant",
            content = content,
            thinking = thinking,
            segments = segments,
            truncated = truncated,
            thinkingSeconds = thinkingSeconds(),
            thinkingTokens = estimateTokens(thinking),
        )
        history += ChatMessage("assistant", content)
        _state.update {
            it.copy(
                turns = it.turns + turn,
                busy = false,
                streaming = false,
                liveContent = "",
                liveThinking = "",
                truncated = truncated,
            )
        }
        // 用量累计（UsageMeter.record 挂点；输出 tokens 估算口径与顶栏一致）
        runCatching { recordUsage?.invoke(estimateTokens(content)) }
    }

    private fun replaceLastAssistant(stitched: String, segments: Int, limitHit: Boolean) {
        val thinking = liveThinkingBuf.value()
        val idx = _state.value.turns.indexOfLast { it.role == "assistant" }
        _state.update { s ->
            val turns = s.turns.toMutableList()
            if (idx >= 0) {
                val t = turns[idx]
                turns[idx] = t.copy(
                    content = stitched,
                    segments = segments,
                    truncated = limitHit,
                    thinkingTokens = estimateTokens(thinking.ifEmpty { t.thinking }),
                )
            }
            s.copy(turns = turns, busy = false, streaming = false, truncated = limitHit)
        }
        val lastIdx = history.indexOfLast { it.role == "assistant" }
        if (lastIdx >= 0) history[lastIdx] = ChatMessage("assistant", stitched) else history += ChatMessage("assistant", stitched)
    }

    private fun fail(msg: String) {
        _state.update { it.copy(busy = false, streaming = false, error = msg) }
    }

    companion object {
        /** 流式缓冲上限：超出仅保留尾部（思考流展示与落轮都用尾部）。 */
        const val LIVE_BUFFER_CAP = 64 * 1024
    }
}
