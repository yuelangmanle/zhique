package com.zhique.runner.onboarding

import com.zhique.core.ai.Protocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 粘贴识别 → 预填（X4 步骤 1）：JSON / cURL / 裸 Key / 协议猜测 / URL 规范化。 */
class ApiConfigParserTest {

    @Test
    fun `apiProfiles_JSON抽取baseUrl与Key与model`() {
        val raw = """
            {"apiProfiles":[{"provider":"custom","baseUrl":"https://api.example.com/v1",
            "secret":{"api_key":"sk-test-abcdef123456"},"models":{"default":"deepseek-chat"}}]}
        """.trimIndent()
        val d = ApiConfigParser.parse(raw)
        assertEquals("https://api.example.com", d.baseUrl, "剥 /v1 尾巴")
        assertEquals("sk-test-abcdef123456", d.apiKey)
        assertEquals("deepseek-chat", d.model)
    }

    @Test
    fun `单配置JSON直接抽取`() {
        val d = ApiConfigParser.parse("""{"baseUrl":"https://x.invalid","apiKey":"test-key-00112233","model":"gpt-4o"}""")
        assertEquals("https://x.invalid", d.baseUrl)
        assertEquals("test-key-00112233", d.apiKey)
        assertEquals("gpt-4o", d.model)
    }

    @Test
    fun `cURL命令抽取`() {
        val raw = """curl https://api.example.com/v1/chat/completions -H "Authorization: Bearer testkey12345" -d '{"model":"gpt-4o"}'"""
        val d = ApiConfigParser.parse(raw)
        assertEquals("https://api.example.com", d.baseUrl)
        assertEquals("testkey12345", d.apiKey)
        assertEquals("gpt-4o", d.model)
        assertEquals(Protocol.OPENAI_COMPATIBLE, d.protocol)
    }

    @Test
    fun `裸Key与URL文本`() {
        val d = ApiConfigParser.parse("我的 key 是 sk-abcdef123456789，服务在 https://api.hello.invalid/v1")
        assertEquals("https://api.hello.invalid", d.baseUrl)
        assertEquals("sk-abcdef123456789", d.apiKey)
    }

    @Test
    fun `协议猜测_by域名`() {
        assertEquals(Protocol.ANTHROPIC_MESSAGES, ApiConfigParser.guessProtocol("https://api.anthropic.com"))
        assertEquals(Protocol.GOOGLE_GENAI, ApiConfigParser.guessProtocol("https://generativelanguage.googleapis.com"))
        assertEquals(Protocol.OPENAI_COMPATIBLE, ApiConfigParser.guessProtocol("https://api.example.com"))
        assertNull(ApiConfigParser.guessProtocol(null))
    }

    @Test
    fun `URL规范化去尾巴与查询`() {
        assertEquals("https://a.b", ApiConfigParser.normalizeBase("https://a.b/v1/chat/completions?x=1"))
        assertEquals("https://a.b", ApiConfigParser.normalizeBase("https://a.b/v1beta/"))
        assertEquals("https://a.b", ApiConfigParser.normalizeBase("https://a.b/v1"))
        assertEquals("https://a.b/keep", ApiConfigParser.normalizeBase("https://a.b/keep"))
    }

    @Test
    fun `无法识别返回全null`() {
        val d = ApiConfigParser.parse("今天天气不错")
        assertTrue(!d.hasSomething)
        assertNull(d.protocol)
        assertEquals(ApiDraft(null, null, null, null), ApiConfigParser.parse(""))
    }
}
