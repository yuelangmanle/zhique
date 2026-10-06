package com.zhique.core.ai

import kotlin.test.Test
import kotlin.test.assertEquals

/** 真机循环修复回归：baseUrl 带/不带版本段都能拼出正确的 /models 地址。 */
class ModelsUrlTest {
    @Test
    fun `baseUrl带v1不重复拼接`() {
        assertEquals("https://api.deepseek.com/v1/models", ModelListFetcher.modelsUrl("https://api.deepseek.com/v1", "/v1"))
        assertEquals("https://api.openai.com/v1/models", ModelListFetcher.modelsUrl("https://api.openai.com/v1/", "/v1"))
    }

    @Test
    fun `baseUrl裸域名补版本段`() {
        assertEquals("https://api.deepseek.com/v1/models", ModelListFetcher.modelsUrl("https://api.deepseek.com", "/v1"))
        assertEquals("https://example.com/v1beta/models", ModelListFetcher.modelsUrl("https://example.com", "/v1beta"))
    }

    @Test
    fun `gemini的v1beta路径`() {
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models", ModelListFetcher.modelsUrl("https://generativelanguage.googleapis.com/v1beta", "/v1beta"))
    }

    @Test
    fun `chat端点同规则不重复拼接`() {
        // TV 循环实测：base 带 /v1 时 chat 流拼出 /v1/v1/chat/completions 404
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            ApiUrls.join("https://api.deepseek.com/v1", "/v1", "/chat/completions"),
        )
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            ApiUrls.join("https://api.openai.com", "/v1", "/chat/completions"),
        )
        assertEquals(
            "https://example.com/v1/messages",
            ApiUrls.join("https://example.com", "/v1", "/messages"),
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini:streamGenerateContent?alt=sse",
            ApiUrls.join("https://generativelanguage.googleapis.com", "/v1beta", "/models/gemini:streamGenerateContent?alt=sse"),
        )
    }
}
