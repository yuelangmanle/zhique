package com.zhique.core.ai

import kotlinx.coroutines.flow.Flow

/** 模型输入模态（徽章全程可见，规格 §4.4）。 */
enum class Modality { TEXT, VISION }

/**
 * 模型能力目录（规格 §4.4.1 输出上限表 + F4 三层填充）。
 *
 * 层级：手动覆盖（Provider 设置，0 = 放弃手动）> 出厂知识库 > 默认。
 * 数据按当期官方值维护；未收录模型回落默认 `text/16384/32k`。
 */
object ModelCatalog {

    data class Entry(
        val pattern: String,
        val modality: Modality,
        val maxOutput: Int,
        val contextWindow: Int,
        val thinkingCapable: Boolean = false,
    )

    /** 出厂知识库：`*` 为通配（可位于中段），先命中先用。 */
    val ENTRIES: List<Entry> = listOf(
        Entry("gpt-4o*", Modality.VISION, 16_384, 128_000),
        Entry("gpt-5*", Modality.VISION, 128_000, 400_000, thinkingCapable = true),
        Entry("deepseek-chat*", Modality.TEXT, 8_192, 65_536),
        Entry("deepseek-reasoner*", Modality.TEXT, 8_192, 65_536, thinkingCapable = true),
        Entry("claude-sonnet-4*", Modality.VISION, 64_000, 200_000, thinkingCapable = true),
        Entry("claude-opus-4*", Modality.VISION, 32_000, 200_000, thinkingCapable = true),
        Entry("gemini-2.*-pro", Modality.VISION, 8_192, 1_048_576, thinkingCapable = true),
        Entry("gemini-2.*-flash", Modality.VISION, 8_192, 1_048_576, thinkingCapable = true),
    )

    /** 全局默认输出上限（对话/生成/Agent 主力）。 */
    const val DEFAULT_MAX_OUTPUT = 16_384

    /** 快循环（摘要/压缩/杂务）默认输出上限。 */
    const val FAST_LOOP_MAX_OUTPUT = 4_096

    /** 自动续写每段输出上限。 */
    const val CONTINUE_SEGMENT_MAX_OUTPUT = 8_192

    /** 未收录模型默认上下文窗口。 */
    const val DEFAULT_CONTEXT_WINDOW = 32_768

    fun find(model: String): Entry? = ENTRIES.firstOrNull { globMatch(it.pattern, model) }

    fun modality(model: String): Modality = find(model)?.modality ?: Modality.TEXT

    fun isThinkingCapable(model: String): Boolean = find(model)?.thinkingCapable ?: false

    /**
     * 解析输出上限：手动（>0）> 知识库 > 默认。
     * [manual] 为 Provider 设置「输出上限」，0 = 放弃手动（用模型上限）。
     */
    fun resolveMaxOutput(model: String, manual: Int?): Int {
        if (manual != null && manual > 0) return manual
        return find(model)?.maxOutput ?: DEFAULT_MAX_OUTPUT
    }

    /** 解析上下文窗口：手动（>0）> 知识库 > 默认（0 = 按模型窗口自动）。 */
    fun resolveContextWindow(model: String, manual: Int?): Int {
        if (manual != null && manual > 0) return manual
        return find(model)?.contextWindow ?: DEFAULT_CONTEXT_WINDOW
    }

    /**
     * 把请求方期望的 max_tokens 压回模型实际上限（防参数错）：
     * 知识库有出厂上限且请求值更高时取出厂值；手动覆盖视为用户明确决策不回压。
     */
    fun capMaxOutput(model: String, requested: Int, manual: Int?): Int {
        if (manual != null && manual > 0) return requested
        val catalogMax = find(model)?.maxOutput ?: return requested
        return minOf(requested, catalogMax)
    }

    /**
     * 视觉能力探测：发一张最小图片请求——
     * 流正常完成 → true；400/422 参数错 → false；网络/鉴权等其它错误 → null（能力未知）。
     * [chat] 为角色路由后的调用通道（测试注入假实现，生产接 Provider.chatStream）。
     */
    suspend fun probeVision(
        chat: suspend (ChatRequest) -> Flow<StreamEvent>,
        base: ChatRequest,
    ): Boolean? {
        val probe = base.copy(
            messages = listOf(
                ChatMessage(
                    role = "user",
                    content = "回复 ok",
                    images = listOf(PROBE_IMAGE_DATA_URL),
                ),
            ),
            maxTokens = 16,
            temperature = 0.0,
        )
        return try {
            chat(probe).collect { }
            true
        } catch (e: AiError.Http) {
            if (e.code == 400 || e.code == 422) false else null
        } catch (e: AiError) {
            null
        }
    }

    /** 探测用最小 PNG（1×1 像素，公开测试图，非凭据）。 */
    const val PROBE_IMAGE_DATA_URL =
        "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
}

/** 简易 glob（仅 `*`）：`gemini-2.*-pro` 这类中段通配。 */
internal fun globMatch(pattern: String, value: String): Boolean {
    if (!pattern.contains('*')) return pattern == value
    val parts = pattern.split('*')
    if (!value.startsWith(parts.first())) return false
    if (!value.endsWith(parts.last())) return false
    var cursor = parts.first().length
    for (mid in parts.subList(1, parts.size - 1)) {
        val at = value.indexOf(mid, cursor)
        if (at < 0) return false
        cursor = at + mid.length
    }
    return cursor <= value.length - parts.last().length
}
