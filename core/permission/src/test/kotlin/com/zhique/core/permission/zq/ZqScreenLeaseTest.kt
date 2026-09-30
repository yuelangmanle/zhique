package com.zhique.core.permission.zq

import android.graphics.Bitmap
import com.zhique.core.permission.PermissionRegistry
import com.zhique.core.permission.PState
import com.zhique.core.project.ProjectRepository
import com.zhique.core.web.debug.DebugEvent
import com.zhique.core.web.debug.TimelineReducer
import com.zhique.core.web.debug.ZqProtocol
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 审查修复 I1：截屏会话在成功/异常路径都必须 stop（防投影指示灯/通知滞留泄漏）。 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ZqScreenLeaseTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 假会话：记录 stop 次数，按配置返回帧或抛错。 */
    private class FakeSession(
        private val frame: Bitmap?,
        private val fail: Boolean = false,
    ) : ProjectionSession {
        var stopCount = 0
        override suspend fun grabFrame(timeoutMs: Long): Bitmap? {
            if (fail) throw IllegalStateException("抓帧失败")
            return frame
        }

        override fun stop() {
            stopCount++
        }
    }

    private fun newEnv(session: ProjectionSession?, dir: File): Pair<ZqEnv, String> {
        val repo = ProjectRepository(dir)
        val pid = repo.create("截屏项目", "<p></p>").id
        val env = ZqEnv(
            projectId = pid,
            projectDir = dir,
            scope = CoroutineScope(Dispatchers.Unconfined),
            registry = PermissionRegistry(repo),
            evaluateJs = {},
            projection = session?.let { s -> ({ s }) },
        )
        return env to pid
    }

    private fun zqCall(id: Long) = DebugEvent(
        seq = 1, t = 1, type = TimelineReducer.TYPE_ZQ_CALL,
        id = id, ns = "screen", fn = "capture", args = "[]",
    )

    @Test
    fun `截屏成功后projection已停且产物落盘`() { // runBlocking：真实 IO 切换不能被虚拟时间快进
        kotlinx.coroutines.runBlocking {
        val dir = tmp.newFolder()
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888) // Robolectric 阴影位图
        val session = FakeSession(bitmap)
        val (env, pid) = newEnv(session, dir)
        env.registry.set(pid, "screen", PState.GRANTED)
        val pushed = mutableListOf<String>()
        val d = ZqDispatcher(
            ZqEnv(env.projectId, env.projectDir, env.scope, env.registry, { pushed += it }, projection = { session }),
        )
        d.register(ZqScreen())
        d.handle(zqCall(1))
        assertEquals(1, session.stopCount, "成功路径也必须 stop（finally）")
        assertTrue(File(dir, "screenshots").listFiles()!!.isNotEmpty(), "截屏产物必须落盘")
        assertTrue(pushed.single().contains("__zqResolve(1, true"))
        }
    }

    @Test
    fun `抓帧异常路径同样stop`() = runTest {
        val dir = tmp.newFolder()
        val session = FakeSession(frame = null, fail = true)
        val (env, pid) = newEnv(session, dir)
        env.registry.set(pid, "screen", PState.GRANTED)
        val pushed = mutableListOf<String>()
        val d = ZqDispatcher(
            ZqEnv(env.projectId, env.projectDir, env.scope, env.registry, { pushed += it }, projection = { session }),
        )
        d.register(ZqScreen())
        d.handle(zqCall(2))
        advanceUntilIdle()
        assertEquals(1, session.stopCount, "异常路径经 finally 必须停投影")
        assertEquals(ZqProtocol.rejectJs(2, "抓帧失败"), pushed.single())
        assertTrue(File(dir, "screenshots").listFiles().isNullOrEmpty(), "失败不得产出文件")
    }

    @Test
    fun `无帧路径也stop且回rejected`() = runTest {
        val dir = tmp.newFolder()
        val session = FakeSession(frame = null)
        val (env, pid) = newEnv(session, dir)
        env.registry.set(pid, "screen", PState.GRANTED)
        val pushed = mutableListOf<String>()
        val d = ZqDispatcher(
            ZqEnv(env.projectId, env.projectDir, env.scope, env.registry, { pushed += it }, projection = { session }),
        )
        d.register(ZqScreen())
        d.handle(zqCall(3))
        advanceUntilIdle()
        assertEquals(1, session.stopCount, "无帧路径同样必须停投影")
        assertTrue(pushed.single().contains("无可用帧"))
    }

    @Test
    fun `未接投影网关直接rejected不触碰session`() = runTest {
        val dir = tmp.newFolder()
        val (base, pid) = newEnv(null, dir)
        base.registry.set(pid, "screen", PState.GRANTED)
        val pushed = mutableListOf<String>()
        val env = ZqEnv(base.projectId, base.projectDir, base.scope, base.registry, { pushed += it })
        val d = ZqDispatcher(env)
        d.register(ZqScreen())
        d.handle(zqCall(4))
        advanceUntilIdle()
        println("PROBE pushed=$pushed")
        assertEquals(ZqProtocol.rejectJs(4, "截屏需系统投影授权（未接入投影网关）"), pushed.single())
        assertNull(File(dir, "screenshots").listFiles()?.firstOrNull())
    }
}
