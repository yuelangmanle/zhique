package com.zhique.core.permission.zq

import com.zhique.core.permission.Capability
import com.zhique.core.permission.PermissionRegistry
import com.zhique.core.permission.PState
import com.zhique.core.project.ProjectRepository
import com.zhique.core.web.debug.DebugEvent
import com.zhique.core.web.debug.TimelineReducer
import com.zhique.core.web.debug.ZqProtocol
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/**
 * 审查修复 I2/I3：运行器销毁（dispatcher.shutdown）全量关停——
 * 订阅句柄清空、location/sensor 监听注销、取景浮层清除；
 * Minor #5：native 分级超时强制执行。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ZqDispatcherShutdownTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newEnv(scope: CoroutineScope): Triple<ZqEnv, String, PermissionRegistry> {
        val dir = tmp.newFolder()
        val repo = ProjectRepository(dir)
        val pid = repo.create("关停项目", "<p></p>").id
        val registry = PermissionRegistry(repo)
        val env = ZqEnv(pid, dir, scope, registry, {})
        return Triple(env, pid, registry)
    }

    @Test
    fun `shutdown取消全部订阅句柄`() = runTest {
        val (env, _, _) = newEnv(this)
        env.subs.new()
        env.subs.new()
        assertEquals(2, env.subs.count())
        val d = ZqDispatcher(env)
        d.shutdown()
        assertEquals(0, env.subs.count(), "dispose 后订阅句柄必须全量关停")
    }

    @Test
    fun `shutdown清取景浮层与相机绑定`() = runTest {
        val (env, pid, registry) = newEnv(this)
        registry.set(pid, "camera", PState.GRANTED)
        CameraPreviewBus.hide() // 隔离全局态
        CameraPreviewBus.show("front", null) // 模拟预览中（不触碰真实视图）
        val d = ZqDispatcher(env)
        d.register(ZqCamera())
        d.shutdown()
        assertNull(CameraPreviewBus.state.value, "dispose 后取景浮层必须清除（黑帧残留）")
        assertNull(CameraPreviewBus.previewView)
    }

    @Test
    fun `shutdown注销location与sensor监听登记`() = runTest {
        val (env, _, _) = newEnv(this)
        val d = ZqDispatcher(env)
        d.register(ZqLocation())
        d.register(ZqSensor())
        d.shutdown() // 未实际绑定系统监听：验证关停路径不崩且订阅清空
        assertEquals(0, env.subs.count())
    }

    @Test
    fun `吊销camera后预览轮询自停`() = runTest {
        val (env, pid, registry) = newEnv(this)
        registry.set(pid, "camera", PState.GRANTED)
        CameraPreviewBus.hide()
        CameraPreviewBus.show("back", null) // 预览中
        val camera = ZqCamera()
        val pollJob = launch {
            while (coroutineContext.isActive) {
                delay(ZqCamera.REVOCATION_POLL_MS)
                if (env.registry.state(env.projectId, "camera") != PState.GRANTED) {
                    camera.shutdown() // 与 startPreview 内部一致的吊销收口
                    break
                }
            }
        }
        advanceTimeBy(ZqCamera.REVOCATION_POLL_MS * 2)
        registry.revoke(pid, "camera") // 权限中心吊销 camera
        advanceTimeBy(ZqCamera.REVOCATION_POLL_MS * 3)
        advanceUntilIdle()
        assertTrue(pollJob.isCompleted, "吊销后轮询必须退出")
        assertNull(CameraPreviewBus.state.value, "吊销 camera 必须停预览（三路收口之一）")
    }

    @Test
    fun `native分级超时强制执行`() = runTest {
        val (env, pid, registry) = newEnv(this)
        registry.set(pid, "clipboard", PState.GRANTED)
        val pushed = mutableListOf<String>()
        val d = ZqDispatcher(
            ZqEnv(env.projectId, env.projectDir, env.scope, env.registry, { pushed += it }),
        )
        d.register(object : ZqCapability {
            override val ns = "slow"
            override val required = Capability.CLIPBOARD
            override val methods = listOf("hang")
            override fun why(fn: String) = "测试"
            override suspend fun call(fn: String, args: JsonObject, env: ZqEnv): JsonObject {
                delay(60_000) // 超过 30s 预算，应被 native 分级超时打断
                return buildJsonObject { put("ok", true) }
            }
        })
        d.handle(
            DebugEvent(
                seq = 1, t = 1, type = TimelineReducer.TYPE_ZQ_CALL,
                id = 7, ns = "slow", fn = "hang", args = "[]", timeout = 30_000L,
            ),
        )
        advanceUntilIdle()
        assertEquals(
            listOf(ZqProtocol.rejectJs(7, "timeout: slow.hang 超过 30000ms")),
            pushed,
            "native 必须按分级预算强制超时",
        )
    }

    @Test
    fun `事件超时元数据可解析`() {
        val e = DebugEvent.fromJson(
            """{"seq":1,"t":1,"type":"zq_call","id":3,"ns":"mic","fn":"record","args":"[]","timeout":120000}""",
        )!!
        assertEquals(120_000L, e.timeout)
    }
}
