package com.zhique.core.permission.zq

import com.zhique.core.web.debug.DebugEvent
import com.zhique.core.web.debug.TimelineReducer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/** 审查修复 #4：取景浮层总线、facing 归一化、startPreview/stopPreview 路由注册。 */
@OptIn(ExperimentalCoroutinesApi::class)
class ZqCameraPreviewTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun zqCall(id: Long, ns: String, fn: String) = DebugEvent(
        seq = 1, t = 1, type = TimelineReducer.TYPE_ZQ_CALL, id = id, ns = ns, fn = fn, args = "[]",
    )

    @Test
    fun `取景总线show_hide状态流转`() {
        CameraPreviewBus.hide() // 隔离全局态
        assertNull(CameraPreviewBus.state.value)
        CameraPreviewBus.show("front", null)
        assertEquals("front", CameraPreviewBus.state.value?.facing)
        CameraPreviewBus.show("back", null)
        assertEquals("back", CameraPreviewBus.state.value?.facing)
        val before = CameraPreviewBus.state.value
        CameraPreviewBus.show("back", null) // capture 后重绑同 facing
        assertTrue(CameraPreviewBus.state.value != before, "generation 递增：同 facing 重绑也必须重新发射（M5 债务收敛）")
        CameraPreviewBus.hide()
        assertNull(CameraPreviewBus.state.value, "stopPreview 后浮层状态必须清除")
        assertEquals(null, CameraPreviewBus.previewView)
    }

    @Test
    fun `facing归一化_仅front视为前置`() {
        assertEquals("back", ZqCamera.normalizeFacing(null))
        assertEquals("back", ZqCamera.normalizeFacing(""))
        assertEquals("back", ZqCamera.normalizeFacing("xyz"))
        assertEquals("back", ZqCamera.normalizeFacing("back"))
        assertEquals("front", ZqCamera.normalizeFacing("front"))
    }

    @Test
    fun `startPreview_stopPreview注册进宿主路由`() = runTest {
        val repo = com.zhique.core.project.ProjectRepository(tmp.newFolder())
        val pid = repo.create("预览项目", "<p></p>").id
        val registry = com.zhique.core.permission.PermissionRegistry(repo)
        registry.set(pid, "camera", com.zhique.core.permission.PState.GRANTED)
        val router = TimelineReducer.ZqCallRouter()
        val env = ZqEnv(pid, tmp.newFolder(), this, registry, {})
        val d = ZqDispatcher(env)
        d.register(ZqCamera())
        d.attachTo(router)
        assertTrue(router.route(zqCall(1, "camera", "startPreview")), "startPreview 必须注册")
        advanceUntilIdle()
        assertTrue(router.route(zqCall(2, "camera", "stopPreview")), "stopPreview 必须注册")
        assertTrue(ZqCamera().methods.contains("capture"), "capture 仍返回文件")
        assertEquals(setOf("capture", "startPreview", "stopPreview"), ZqCamera().methods.toSet())
    }

    @Test
    fun `路由未注册的camera方法不命中`() {
        val router = TimelineReducer.ZqCallRouter()
        val env = ZqEnv(
            "p", tmp.newFolder(),
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            com.zhique.core.permission.PermissionRegistry(com.zhique.core.project.ProjectRepository(tmp.newFolder())),
            {},
        )
        val d = ZqDispatcher(env)
        d.register(ZqCamera())
        d.attachTo(router)
        assertFalse(router.route(zqCall(1, "camera", "unknownFn")))
    }
}
