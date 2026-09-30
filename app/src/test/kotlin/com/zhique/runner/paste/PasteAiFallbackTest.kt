package com.zhique.runner.paste

import com.zhique.core.paste.AiFallback
import com.zhique.core.paste.PasteConfidence
import com.zhique.core.project.ProjectRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/** Task 4.6 接线：低置信输入走 AI 兜底解析；失败保留规则结果（原文不丢）。 */
@OptIn(ExperimentalCoroutinesApi::class)
class PasteAiFallbackTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeParser(val result: AiFallback.Parsed?, val error: Exception? = null) : AiFallback.Parser {
        var asked: String? = null
        override suspend fun parse(raw: String): AiFallback.Parsed? {
            asked = raw
            error?.let { throw it }
            return result
        }
    }

    private fun controller(parser: AiFallback.Parser?): Pair<PastePreviewController, ProjectRepository> {
        val repo = ProjectRepository(tmp.root)
        val d = UnconfinedTestDispatcher()
        val c = PastePreviewController(
            repo = repo,
            scope = CoroutineScope(d),
            io = d,
            aiParser = parser,
        )
        return c to repo
    }

    /** 弱特征输入（含 => 弱特征、无强代码指纹）→ 分类置信度 0.3（< 0.5 阈值）→ 走兜底。 */
    private val lowConfidenceInput = "他说这段逻辑 => 大概长这样，说明文字混着一点弱特征"

    @Test
    fun `低置信走AI兜底_结构化结果替换规则组装`() = runTest {
        val parser = FakeParser(AiFallback.Parsed("星空", "<html><body>AI 重组</body></html>"))
        val (c, _) = controller(parser)
        c.start(lowConfidenceInput)
        val s = c.state.value
        assertTrue(s.confidence < PasteConfidence.AI_FALLBACK_THRESHOLD, "前置：置信度须低于阈值")
        assertEquals(lowConfidenceInput, parser.asked, "原文须送解析")
        assertTrue(s.aiFallbackUsed)
        assertEquals("<html><body>AI 重组</body></html>", s.assembledHtml)
        assertEquals("星空", s.name, "AI 标题优先命名")
    }

    @Test
    fun `AI失败保留规则组装结果`() = runTest {
        val parser = FakeParser(null)
        val (c, _) = controller(parser)
        c.start(lowConfidenceInput)
        val s = c.state.value
        assertFalse(s.aiFallbackUsed, "失败不算已用")
        assertTrue(s.assembledHtml.contains("<!DOCTYPE html>"), "规则兜底组装在场（原文入库语义）")
        assertTrue(s.assembledHtml.contains("他说这段逻辑"), "原文保留在转义 pre 中")
    }

    @Test
    fun `AI抛错同样保留原文`() = runTest {
        val parser = FakeParser(null, error = IllegalStateException("服务过载"))
        val (c, _) = controller(parser)
        c.start(lowConfidenceInput)
        val s = c.state.value
        assertFalse(s.aiFallbackUsed)
        assertTrue(s.assembledHtml.isNotBlank())
    }

    @Test
    fun `高置信不走AI`() = runTest {
        val parser = FakeParser(AiFallback.Parsed("X", "<html></html>"))
        val (c, _) = controller(parser)
        c.start("<!DOCTYPE html><html><body>正常粘贴</body></html>")
        val s = c.state.value
        assertTrue(s.confidence >= PasteConfidence.AI_FALLBACK_THRESHOLD)
        assertEquals(null, parser.asked, "高置信不得调 AI")
        assertFalse(s.aiFallbackUsed)
    }

    @Test
    fun `未配置解析器保持旧行为`() = runTest {
        val (c, _) = controller(parser = null)
        c.start(lowConfidenceInput)
        val s = c.state.value
        assertFalse(s.aiFallbackUsed)
        assertTrue(s.aiFallbackSuggested, "仍提示可兜底")
        assertTrue(s.assembledHtml.isNotBlank())
    }
}
