package com.zhique.core.permission.zq

import com.zhique.core.permission.Capability
import com.zhique.core.permission.PermissionRegistry
import com.zhique.core.project.ProjectRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/**
 * W3C 标准路路由（规格 §4.6「两条接入路，同一个注册表」）：
 * onPermissionRequest 资源映射 + 同一授权卡矩阵。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ZqW3CRouterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newRegistry(): Pair<PermissionRegistry, String> {
        val repo = ProjectRepository(tmp.newFolder())
        val pid = repo.create("W3C测试项目", "<p></p>").id
        return PermissionRegistry(repo) to pid
    }

    private fun router(
        registry: PermissionRegistry,
        pid: String,
        scope: kotlinx.coroutines.CoroutineScope,
    ) = ZqW3CRouter(registry, pid, scope)

    @Test
    fun `资源映射_video到camera_audio到mic`() {
        val (reg, pid) = newRegistry()
        val r = router(reg, pid, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
        assertEquals(
            listOf(Capability.CAMERA),
            r.capabilitiesFor(listOf(android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE)),
        )
        assertEquals(
            listOf(Capability.MIC),
            r.capabilitiesFor(listOf(android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE)),
        )
        assertEquals(
            listOf(Capability.CAMERA, Capability.MIC),
            r.capabilitiesFor(
                listOf(
                    android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE,
                    android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE,
                ),
            ),
        )
        assertEquals(0, r.capabilitiesFor(listOf("resource:unknown", "resource:protectedMediaId")).size)
    }

    @Test
    fun `未知资源不弹卡直接deny`() = runTest {
        val (reg, pid) = newRegistry()
        var granted = false
        var denied = false
        router(reg, pid, this).onRequest(
            listOf("resource:unknown"),
            grant = { granted = true },
            deny = { denied = true },
        )
        advanceUntilIdle()
        assertTrue(denied && !granted)
    }

    @Test
    fun `矩阵已授予则W3C路径直接grant`() = runTest {
        val (reg, pid) = newRegistry()
        reg.set(pid, "camera", com.zhique.core.permission.PState.GRANTED)
        reg.set(pid, "mic", com.zhique.core.permission.PState.GRANTED)
        var granted = false
        var denied = false
        router(reg, pid, this).onRequest(
            listOf(
                android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE,
                android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE,
            ),
            grant = { granted = true },
            deny = { denied = true },
        )
        advanceUntilIdle()
        assertTrue(granted && !denied)
    }

    @Test
    fun `授权卡拒绝则W3C路径deny`() = runTest {
        var answer = false
        val repo = ProjectRepository(tmp.newFolder())
        val pid = repo.create("W3C拒绝项目", "<p></p>").id
        val reg = PermissionRegistry(repo, prompt = { answer })
        var granted = false
        var denied = false
        router(reg, pid, this).onRequest(
            listOf(android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE),
            grant = { granted = true },
            deny = { denied = true },
        )
        advanceUntilIdle()
        assertTrue(denied && !granted)
    }

    @Test
    fun `两者都要_其一拒绝则整体deny且另一能力不再重复弹`() = runTest {
        val asked = mutableListOf<String>()
        var flip = true
        val repo = ProjectRepository(tmp.newFolder())
        val pid = repo.create("W3C双卡项目", "<p></p>").id
        val reg = PermissionRegistry(repo, prompt = { ask ->
            asked += ask.capability
            flip.also { flip = false } // 第一张授、第二张拒
        })
        var granted = false
        var denied = false
        router(reg, pid, this).onRequest(
            listOf(
                android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE,
                android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE,
            ),
            grant = { granted = true },
            deny = { denied = true },
        )
        advanceUntilIdle()
        assertTrue(denied && !granted)
        assertEquals(listOf("camera", "mic"), asked, "两个能力各过一张卡")
    }
}
