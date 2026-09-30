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

/** 审查修复 #2：geolocation 路由、W3C 两路 recordUse、系统门。 */
class ZqW3CRouterGeoTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeOsGateway(
        private val alreadyGranted: Boolean = false,
        private val requestResult: Map<String, Boolean>? = null,
    ) : com.zhique.core.permission.OsPermissionGateway {
        var requestCount = 0
        override fun granted(permissions: List<String>): Boolean = alreadyGranted
        override suspend fun request(permissions: List<String>): Map<String, Boolean> {
            requestCount++
            return requestResult ?: permissions.associateWith { true }
        }
    }

    @Test
    fun `geolocation_矩阵已授且OS就绪_允许并recordUse`() = runTest {
        val repo = ProjectRepository(tmp.newFolder())
        val pid = repo.create("geo项目", "<p></p>").id
        val reg = PermissionRegistry(repo)
        reg.set(pid, "location", com.zhique.core.permission.PState.GRANTED)
        var allowed: Boolean? = null
        ZqW3CRouter(reg, pid, this, osPermissions = FakeOsGateway(alreadyGranted = true))
            .onGeolocation { allowed = it }
        advanceUntilIdle()
        assertEquals(true, allowed)
        assertEquals(1, reg.usage(pid)["location"], "W3C geolocation 授予即计真实使用")
    }

    @Test
    fun `geolocation_矩阵拒绝_不允许`() = runTest {
        val repo = ProjectRepository(tmp.newFolder())
        val pid = repo.create("geo拒项目", "<p></p>").id
        val reg = PermissionRegistry(repo, prompt = { false })
        var allowed: Boolean? = null
        ZqW3CRouter(reg, pid, this).onGeolocation { allowed = it }
        advanceUntilIdle()
        assertEquals(false, allowed)
        assertEquals(null, reg.usage(pid)["location"], "拒绝不计 usage")
    }

    @Test
    fun `geolocation_OS拒_不允许且矩阵回DENIED`() = runTest {
        val repo = ProjectRepository(tmp.newFolder())
        val pid = repo.create("geo系统拒项目", "<p></p>").id
        val reg = PermissionRegistry(repo, prompt = { true })
        var allowed: Boolean? = null
        val gateway = FakeOsGateway(
            requestResult = mapOf(
                "android.permission.ACCESS_FINE_LOCATION" to false,
                "android.permission.ACCESS_COARSE_LOCATION" to false,
            ),
        )
        ZqW3CRouter(reg, pid, this, osPermissions = gateway).onGeolocation { allowed = it }
        advanceUntilIdle()
        assertEquals(false, allowed)
        assertEquals(com.zhique.core.permission.PState.DENIED, reg.state(pid, "location"), "OS 拒 → 矩阵回 DENIED")
        assertEquals(null, reg.usage(pid)["location"])
    }

    @Test
    fun `getUserMedia授予即recordUse`() = runTest {
        val repo = ProjectRepository(tmp.newFolder())
        val pid = repo.create("gum项目", "<p></p>").id
        val reg = PermissionRegistry(repo)
        reg.set(pid, "camera", com.zhique.core.permission.PState.GRANTED)
        reg.set(pid, "mic", com.zhique.core.permission.PState.GRANTED)
        var granted = false
        ZqW3CRouter(reg, pid, this, osPermissions = FakeOsGateway(alreadyGranted = true)).onRequest(
            listOf(
                android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE,
                android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE,
            ),
            grant = { granted = true },
            deny = {},
        )
        advanceUntilIdle()
        assertTrue(granted)
        assertEquals(1, reg.usage(pid)["camera"], "getUserMedia 授予必须计入 camera 使用")
        assertEquals(1, reg.usage(pid)["mic"], "getUserMedia 授予必须计入 mic 使用")
    }

    @Test
    fun `getUserMedia_OS拒_整体deny不计usage`() = runTest {
        val repo = ProjectRepository(tmp.newFolder())
        val pid = repo.create("gum系统拒项目", "<p></p>").id
        val reg = PermissionRegistry(repo, prompt = { true })
        var denied = false
        ZqW3CRouter(
            reg, pid, this,
            osPermissions = FakeOsGateway(requestResult = mapOf("android.permission.CAMERA" to false, "android.permission.RECORD_AUDIO" to true)),
        ).onRequest(
            listOf(android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE),
            grant = {},
            deny = { denied = true },
        )
        advanceUntilIdle()
        assertTrue(denied)
        assertEquals(null, reg.usage(pid)["camera"])
        assertEquals(com.zhique.core.permission.PState.DENIED, reg.state(pid, "camera"))
    }
}
