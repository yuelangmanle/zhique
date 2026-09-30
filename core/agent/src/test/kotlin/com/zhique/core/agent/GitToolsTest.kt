package com.zhique.core.agent

import com.zhique.core.agent.tools.GitTools
import com.zhique.core.ai.ToolCall
import com.zhique.core.project.ProjectRepository
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.rules.TemporaryFolder

/** M7 接线：GitTools 绑定 Gateway 后真实执行；未绑定保持 NotReady；requiresConfirm 不变。 */
class GitToolsTest {

    private val tmp = TemporaryFolder()

    private class FakeWeb : WebControl {
        override var running = false
        override suspend fun run() {}
        override suspend fun stop() {}
        override suspend fun reload() {}
        override suspend fun consoleProblems() = emptyList<com.zhique.core.web.debug.TimelineEntry>()
        override suspend fun domSummary(): String? = null
        override suspend fun screenshotDataUrl(): String? = null
    }

    private suspend fun invoke(name: String, args: String = "{}"): JsonObject {
        tmp.create()
        val repo = ProjectRepository(tmp.root)
        val ctx = ToolContext("p1", FakeWeb(), repo, repo.history, vision = false)
        return ToolRegistry(defaultTools()).invoke(ctx, ToolCall("call_1", name, args)) as JsonObject
    }

    private class FakeGateway : GitTools.Gateway {
        var created: Pair<String, Boolean>? = null
        var pushedMessage: String? = null
        override suspend fun createRepo(projectId: String, name: String, isPrivate: Boolean): JsonObject {
            created = name to isPrivate
            return buildJsonObject {
                put("status", "ok")
                put("full_name", "alice/$name")
            }
        }
        override suspend fun push(projectId: String, message: String?): JsonObject {
            pushedMessage = message
            return buildJsonObject {
                put("status", "ok")
                put("stage", "RELEASED")
            }
        }
        override suspend fun readReleases(projectId: String): JsonObject = buildJsonObject {
            put("status", "ok")
            put("count", 2)
        }
    }

    private val meta = ToolRegistry(defaultTools())

    @Test
    fun `未绑定时保持NotReady占位`() = runTest {
        GitTools.bind(null)
        val out = invoke(GitTools.PUSH)
        assertEquals("NotReady", out["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `requiresConfirm外发动作语义在真实实现下不变`() {
        assertTrue(meta["push"]!!.requiresConfirm)
        assertTrue(meta["create_repo"]!!.requiresConfirm)
        assertTrue(meta["read_releases"]!!.requiresConfirm)
    }

    @Test
    fun `绑定后create_repo带默认名与私有语义`() = runTest {
        val gw = FakeGateway()
        GitTools.bind(gw)
        val out = invoke(GitTools.CREATE_REPO, """{"name":"demo"}""")
        assertEquals("ok", out["status"]!!.jsonPrimitive.content)
        assertEquals("demo" to true, gw.created)
        GitTools.bind(null)
    }

    @Test
    fun `绑定后push透传message并返回状态机阶段`() = runTest {
        val gw = FakeGateway()
        GitTools.bind(gw)
        val out = invoke(GitTools.PUSH, """{"message":"更新首页"}""")
        assertEquals("RELEASED", out["stage"]!!.jsonPrimitive.content)
        assertEquals("更新首页", gw.pushedMessage)
        GitTools.bind(null)
    }

    @Test
    fun `gateway抛错折叠为error明细不外泄堆栈`() = runTest {
        GitTools.bind(object : GitTools.Gateway {
            override suspend fun createRepo(projectId: String, name: String, isPrivate: Boolean) =
                throw IllegalStateException("尚未配置 GitHub PAT")
            override suspend fun push(projectId: String, message: String?) =
                throw IllegalStateException("push 失败")
            override suspend fun readReleases(projectId: String) =
                throw IllegalStateException("no repo")
        })
        val out = invoke(GitTools.PUSH)
        assertEquals("error", out["status"]!!.jsonPrimitive.content)
        assertEquals("push 失败", out["detail"]!!.jsonPrimitive.content)
        GitTools.bind(null)
    }
}
