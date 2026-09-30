package com.zhique.core.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 模型能力目录：modality/maxOutput/contextWindow 三层填充，
 * 覆写优先级 手动 > 知识库 > 默认（Task 3.3）。
 */
class ModelCatalogTest {

    @Test
    fun `知识库命中_gpt4o系列为vision与128k窗口`() {
        assertEquals(Modality.VISION, ModelCatalog.modality("gpt-4o"))
        assertEquals(Modality.VISION, ModelCatalog.modality("gpt-4o-mini-2024-07-18"))
        assertEquals(16384, ModelCatalog.resolveMaxOutput("gpt-4o", null))
        assertEquals(128_000, ModelCatalog.resolveContextWindow("gpt-4o", null))
    }

    @Test
    fun `知识库命中_deepseek为text且8192上限`() {
        assertEquals(Modality.TEXT, ModelCatalog.modality("deepseek-chat"))
        assertEquals(8192, ModelCatalog.resolveMaxOutput("deepseek-chat", null))
        assertEquals(65_536, ModelCatalog.resolveContextWindow("deepseek-chat", null))
        assertTrue(ModelCatalog.isThinkingCapable("deepseek-reasoner"), "reasoner 原生带思考")
        assertFalse(ModelCatalog.isThinkingCapable("deepseek-chat"))
    }

    @Test
    fun `glob中间通配_gemini2xpro命中`() {
        assertEquals(Modality.VISION, ModelCatalog.modality("gemini-2.5-pro"))
        assertEquals(Modality.VISION, ModelCatalog.modality("gemini-2.0-pro"))
        assertEquals(8192, ModelCatalog.resolveMaxOutput("gemini-2.5-pro", null))
        assertEquals(1_048_576, ModelCatalog.resolveContextWindow("gemini-2.5-pro", null))
    }

    @Test
    fun `未收录走默认_text与16384与32k`() {
        assertEquals(Modality.TEXT, ModelCatalog.modality("totally-unknown-model"))
        assertEquals(ModelCatalog.DEFAULT_MAX_OUTPUT, ModelCatalog.resolveMaxOutput("totally-unknown-model", null))
        assertEquals(ModelCatalog.DEFAULT_CONTEXT_WINDOW, ModelCatalog.resolveContextWindow("totally-unknown-model", null))
    }

    @Test
    fun `覆写优先级_手动大于知识库`() {
        assertEquals(4096, ModelCatalog.resolveMaxOutput("deepseek-chat", manual = 4096))
        assertEquals(200_000, ModelCatalog.resolveContextWindow("gpt-4o", manual = 200_000))
    }

    @Test
    fun `覆写为0表示放弃手动按知识库`() {
        assertEquals(8192, ModelCatalog.resolveMaxOutput("deepseek-chat", manual = 0))
        assertEquals(16384, ModelCatalog.resolveMaxOutput("gpt-4o", manual = 0))
    }

    @Test
    fun `知识库压过请求值且不高于模型上限`() {
        // 请求方要 40960，但 deepseek-chat 出厂上限 8192 → 取 8192
        assertEquals(8192, ModelCatalog.capMaxOutput("deepseek-chat", requested = 40_960, manual = null))
        // 手动覆盖视为用户明确决策，不回压
        assertEquals(40_960, ModelCatalog.capMaxOutput("deepseek-chat", requested = 40_960, manual = 40_960))
    }

    @Test
    fun `probeVision_流正常完成返回true`() = runTest {
        val result = ModelCatalog.probeVision(fakeChat { flowOf(StreamEvent.Done(StopReason.STOP)) }, baseReq())
        assertEquals(true, result)
    }

    @Test
    fun `probeVision_400参数错返回false`() = runTest {
        val result = ModelCatalog.probeVision(
            fakeChat { throw AiError.Http(400, "image not supported") },
            baseReq(),
        )
        assertEquals(false, result)
    }

    @Test
    fun `probeVision_网络异常返回null`() = runTest {
        val result = ModelCatalog.probeVision(
            fakeChat { throw AiError.Network("timeout") },
            baseReq(),
        )
        assertNull(result)
    }

    @Test
    fun `probeVision_鉴权错误不算能力答案返回null`() = runTest {
        val result = ModelCatalog.probeVision(fakeChat { throw AiError.Auth("bad key") }, baseReq())
        assertNull(result)
    }

    @Test
    fun `探测请求必须携带图片与最小maxTokens`() = runTest {
        var captured: ChatRequest? = null
        ModelCatalog.probeVision(
            fakeChat {
                captured = it
                flowOf(StreamEvent.Done(StopReason.STOP))
            },
            baseReq(),
        )
        val req = captured!!
        assertTrue(req.messages.single().images.isNotEmpty(), "探测请求必须带图")
        assertTrue(req.messages.single().images.single().startsWith("data:image/"), "图片为 data URL")
        assertTrue(req.maxTokens in 1..64, "最小请求 maxTokens 收敛")
    }

    private fun baseReq() = ChatRequest(
        baseUrl = "https://example.invalid",
        apiKey = "test-key-" + "x".repeat(8),
        model = "test-model",
        messages = listOf(ChatMessage("user", "hi")),
        maxTokens = 4096,
    )

    private fun fakeChat(behavior: (ChatRequest) -> Flow<StreamEvent>): suspend (ChatRequest) -> Flow<StreamEvent> = { req ->
        behavior(req)
    }
}
