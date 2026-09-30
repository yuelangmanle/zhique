package com.zhique.core.permission.zq

import com.zhique.core.permission.Capability
import com.zhique.core.permission.PermissionRegistry
import com.zhique.core.permission.PState
import com.zhique.core.web.debug.DebugEvent
import com.zhique.core.web.debug.TimelineReducer
import com.zhique.core.web.debug.ZqProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/** 调度与参数校验（计划 Task 5.2）：GRANTED 才执行、denied 优雅降级、usage 计数、回写协议。 */
@OptIn(ExperimentalCoroutinesApi::class)
class ZqDispatcherTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 桩能力：不触碰 Android API，验证调度语义。 */
    private class FakeCapability(
        override val required: Capability = Capability.CLIPBOARD,
    ) : ZqCapability {
        override val ns = "fake"
        override val methods = listOf("hello", "boom", "need", "push")
        var executed = 0
        var lastArgs: JsonObject? = null

        override fun why(fn: String) = "测试理由($fn)"

        override suspend fun call(fn: String, args: JsonObject, env: ZqEnv): kotlinx.serialization.json.JsonElement =
            when (fn) {
                "hello" -> {
                    executed++
                    lastArgs = args
                    buildJsonObject { put("ok", true) }
                }
                "boom" -> throw IllegalStateException("硬件坏了")
                "need" -> buildJsonObject { put("path", args.zqText("path")) }
                "push" -> {
                    env.evaluateJs(ZqEvents.pushJs("s1", "{\"v\":1}"))
                    buildJsonObject { put("sub", "s1") }
                }
                else -> throw IllegalArgumentException("未知方法 $fn")
            }
    }

    private fun zqCall(id: Long, ns: String, fn: String, args: String = "[]") = DebugEvent(
        seq = 1, t = 1, type = TimelineReducer.TYPE_ZQ_CALL, id = id, ns = ns, fn = fn, args = args,
    )

    private class Harness {
        val pushed = mutableListOf<String>()
        var cap: FakeCapability = FakeCapability()
        lateinit var dispatcher: ZqDispatcher
        lateinit var registry: PermissionRegistry
        lateinit var pid: String
        var asks = 0
        var answer: Boolean? = null

        fun start(
            scope: kotlinx.coroutines.CoroutineScope,
            projectDir: java.io.File,
            initialAnswer: Boolean? = null,
            osGateway: com.zhique.core.permission.OsPermissionGateway? = null,
            capability: FakeCapability = FakeCapability(),
        ) {
            answer = initialAnswer
            cap = capability
            val repo = com.zhique.core.project.ProjectRepository(projectDir)
            pid = repo.create("调度测试项目", "<p></p>").id
            registry = PermissionRegistry(
                repo,
                prompt = { _ ->
                    asks++
                    answer ?: error("不预期弹卡")
                },
            )
            val env = ZqEnv(
                projectId = pid,
                projectDir = projectDir,
                scope = scope,
                registry = registry,
                evaluateJs = { pushed += it },
                osPermissions = osGateway,
            )
            dispatcher = ZqDispatcher(env)
            dispatcher.register(cap)
        }
    }

    // ---- GRANTED 路径 ----

    @Test
    fun `GRANTED才执行native并回写resolve`() = runTest {
        val h = Harness()
        h.start(this, tmp.newFolder())
        h.registry.set(h.pid, "clipboard", PState.GRANTED)
        h.dispatcher.handle(zqCall(7, "fake", "hello"))
        assertEquals(1, h.cap.executed)
        assertEquals(listOf(ZqProtocol.resolveJs(7, true, "{\"ok\":true}")), h.pushed)
    }

    @Test
    fun `首调过授权卡授予后执行`() = runTest {
        val h = Harness()
        h.start(this, tmp.newFolder(), initialAnswer = true)
        h.dispatcher.handle(zqCall(7, "fake", "hello"))
        assertEquals(1, h.asks)
        assertEquals(1, h.cap.executed)
        assertTrue(h.pushed.single().contains("__zqResolve(7, true"))
    }

    @Test
    fun `执行成功后usage加一`() = runTest {
        val h = Harness()
        h.start(this, tmp.newFolder(), initialAnswer = true)
        h.dispatcher.handle(zqCall(1, "fake", "hello"))
        h.dispatcher.handle(zqCall(2, "fake", "hello"))
        assertEquals(mapOf("clipboard" to 2), h.registry.usage(h.pid))
    }

    // ---- DENIED 路径 ----

    @Test
    fun `DENIED返回deniedJSON且native不执行`() = runTest {
        val h = Harness()
        h.start(this, tmp.newFolder())
        h.registry.set(h.pid, "clipboard", PState.DENIED)
        h.dispatcher.handle(zqCall(9, "fake", "hello"))
        assertEquals(0, h.cap.executed, "拒绝后不得执行 native")
        assertEquals(listOf(ZqProtocol.resolveJs(9, true, ZqDispatcher.DENIED_JSON)), h.pushed)
    }

    @Test
    fun `授权卡拒绝也走denied且不计usage`() = runTest {
        val h = Harness()
        h.start(this, tmp.newFolder(), initialAnswer = false)
        h.dispatcher.handle(zqCall(9, "fake", "hello"))
        assertEquals(1, h.asks)
        assertEquals(0, h.cap.executed)
        assertEquals(h.pushed.single(), ZqProtocol.resolveJs(9, true, ZqDispatcher.DENIED_JSON))
        assertTrue(h.registry.usage(h.pid).isEmpty(), "拒绝不得计 usage")
    }

    @Test
    fun `吊销后新的调用重新过卡且拒绝拿denied`() = runTest {
        val h = Harness()
        h.start(this, tmp.newFolder(), initialAnswer = true)
        h.dispatcher.handle(zqCall(1, "fake", "hello"))
        assertEquals(1, h.cap.executed)
        h.registry.revoke(h.pid, "clipboard")
        h.answer = false // 吊销后页面再要：重新弹卡，这次用户拒绝
        h.dispatcher.handle(zqCall(2, "fake", "hello"))
        assertEquals(1, h.cap.executed, "吊销+拒绝后不得再执行 native（仍只有第 1 次的执行）")
        assertEquals(ZqProtocol.resolveJs(2, true, ZqDispatcher.DENIED_JSON), h.pushed[1], "吊销后新调用返回 denied")
        assertEquals(2, h.asks, "吊销后必须重新过授权卡")
    }

    // ---- 未注册与坏参数 ----

    @Test
    fun `未注册能力回rejected`() = runTest {
        val h = Harness()
        h.start(this, tmp.newFolder())
        h.dispatcher.handle(zqCall(3, "nope", "hello"))
        assertEquals(listOf(ZqProtocol.rejectJs(3, "未注册能力: nope.hello")), h.pushed)
    }

    @Test
    fun `缺少必填参数回rejected不崩`() = runTest {
        val h = Harness()
        h.start(this, tmp.newFolder())
        h.registry.set(h.pid, "clipboard", PState.GRANTED)
        h.dispatcher.handle(zqCall(4, "fake", "need", args = "[{}]"))
        assertTrue(h.pushed.single().contains("缺少参数: path"), "实际: ${h.pushed.single()}")
        assertTrue(h.pushed.single().contains("__zqResolve(4, false"))
    }

    @Test
    fun `参数对象正确取到首个对象`() = runTest {
        val h = Harness()
        h.start(this, tmp.newFolder())
        h.registry.set(h.pid, "clipboard", PState.GRANTED)
        h.dispatcher.handle(zqCall(5, "fake", "hello", args = "[{\"a\":1},2]"))
        assertEquals(1, h.cap.lastArgs?.get("a")?.toString()?.toInt())
    }

    @Test
    fun `坏JSON参数回rejected`() = runTest {
        val h = Harness()
        h.start(this, tmp.newFolder())
        h.registry.set(h.pid, "clipboard", PState.GRANTED)
        h.dispatcher.handle(zqCall(6, "fake", "hello", args = "{broken"))
        assertTrue(h.pushed.single().contains("__zqResolve(6, false"), "实际: ${h.pushed.single()}")
    }

    @Test
    fun `能力抛错回rejected并带原因`() = runTest {
        val h = Harness()
        h.start(this, tmp.newFolder())
        h.registry.set(h.pid, "clipboard", PState.GRANTED)
        h.dispatcher.handle(zqCall(8, "fake", "boom"))
        assertEquals(listOf(ZqProtocol.rejectJs(8, "硬件坏了")), h.pushed)
    }

    @Test
    fun `缺id的坏调用静默忽略`() = runTest {
        val h = Harness()
        h.start(this, tmp.newFolder())
        h.dispatcher.handle(DebugEvent(seq = 1, t = 1, type = TimelineReducer.TYPE_ZQ_CALL, ns = "fake", fn = "hello"))
        assertTrue(h.pushed.isEmpty())
    }

    // ---- 订阅事件推送与路由挂载 ----

    @Test
    fun `事件推送走__zqEvent协议`() = runTest {
        val h = Harness()
        h.start(this, tmp.newFolder())
        h.registry.set(h.pid, "clipboard", PState.GRANTED)
        h.dispatcher.handle(zqCall(10, "fake", "push"))
        assertEquals(
            listOf(ZqEvents.pushJs("s1", "{\"v\":1}"), ZqProtocol.resolveJs(10, true, "{\"sub\":\"s1\"}")),
            h.pushed,
        )
    }

    @Test
    fun `attachTo后宿主路由命中并异步执行`() = runTest {
        val h = Harness()
        h.start(this, tmp.newFolder(), initialAnswer = true)
        val router = TimelineReducer.ZqCallRouter()
        h.dispatcher.attachTo(router)
        assertTrue(router.route(zqCall(11, "fake", "hello")))
        advanceUntilIdle()
        assertEquals(1, h.cap.executed)
    }

    @Test
    fun `attachTo未注册的fn不命中`() {
        val h = Harness()
        h.start(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), tmp.newFolder())
        val router = TimelineReducer.ZqCallRouter()
        h.dispatcher.attachTo(router)
        assertEquals(false, router.route(zqCall(1, "fake", "unknown")))
        assertEquals(false, router.route(zqCall(1, "other", "hello")))
    }

    @Test
    fun `注册表覆盖十个能力命名空间`() {
        val h = Harness()
        h.start(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), tmp.newFolder())
        val router = TimelineReducer.ZqCallRouter()
        // 十能力齐装（命名空间与方法完整注册）
        val env = ZqEnv("p1", tmp.newFolder(), kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), h.registry, {})
        val full = ZqDispatcher(env)
        full.register(ZqCamera())
        full.register(ZqMic())
        full.register(ZqFile())
        full.register(ZqLocation())
        full.register(ZqSensor())
        full.register(ZqBluetooth())
        full.register(ZqNotify())
        full.register(ZqClipboard())
        full.register(ZqShare())
        full.register(ZqScreen())
        assertEquals(
            listOf("camera", "mic", "file", "location", "sensor", "bluetooth", "notification", "clipboard", "share", "screen"),
            full.registered(),
        )
        full.attachTo(router)
        assertTrue(router.route(zqCall(1, "camera", "capture")))
        assertTrue(router.route(zqCall(2, "file", "read")))
        assertTrue(router.route(zqCall(3, "sensor", "watch")))
    }
}

/** 审查修复 #1：OS 运行时权限门（网关假实现的授/拒两路）。 */
class OsGateDispatcherTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 系统权限假网关：granted 首查结果 + request 应答脚本。 */
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

    private fun zqCall(id: Long, ns: String, fn: String, args: String = "[]") = DebugEvent(
        seq = 1, t = 1, type = TimelineReducer.TYPE_ZQ_CALL, id = id, ns = ns, fn = fn, args = args,
    )

    @Test
    fun `矩阵授予但OS拒_矩阵回DENIED且native不执行`() = runTest {
        val dir = tmp.newFolder()
        val repo = com.zhique.core.project.ProjectRepository(dir)
        val pid = repo.create("OS拒项目", "<p></p>").id
        val registry = PermissionRegistry(repo, prompt = { true })
        val pushed = mutableListOf<String>()
        val gateway = FakeOsGateway(alreadyGranted = false, requestResult = mapOf("android.permission.CAMERA" to false))
        val env = ZqEnv(pid, dir, this, registry, { pushed += it }, osPermissions = gateway)
        val d = ZqDispatcher(env)
        d.register(FakeCapabilityFor(required = Capability.CAMERA))
        d.handle(zqCall(1, "fake", "hello"))
        assertEquals(
            listOf(ZqProtocol.resolveJs(1, true, ZqDispatcher.SYSTEM_DENIED_JSON)),
            pushed,
            "OS 拒绝必须返回 system 原因",
        )
        assertEquals(PState.DENIED, registry.state(pid, "camera"), "OS 拒绝后矩阵必须回 DENIED")
        assertEquals(1, gateway.requestCount, "矩阵授予后必须立即发起系统申请")
        assertEquals(null, registry.usage(pid)["camera"], "未执行不得计 usage")
    }

    @Test
    fun `OS申请全部授予则native执行`() = runTest {
        val dir = tmp.newFolder()
        val repo = com.zhique.core.project.ProjectRepository(dir)
        val pid = repo.create("OS授项目", "<p></p>").id
        val registry = PermissionRegistry(repo, prompt = { true })
        val pushed = mutableListOf<String>()
        val gateway = FakeOsGateway(alreadyGranted = false, requestResult = mapOf("android.permission.CAMERA" to true))
        val env = ZqEnv(pid, dir, this, registry, { pushed += it }, osPermissions = gateway)
        val d = ZqDispatcher(env)
        val cap = FakeCapabilityFor(required = Capability.CAMERA)
        d.register(cap)
        d.handle(zqCall(1, "fake", "hello"))
        assertEquals(listOf(ZqProtocol.resolveJs(1, true, "{\"ok\":true}")), pushed)
        assertEquals(1, registry.usage(pid)["camera"], "真实执行才计数")
        assertEquals(PState.GRANTED, registry.state(pid, "camera"))
    }

    @Test
    fun `OS已授予不重复申请`() = runTest {
        val dir = tmp.newFolder()
        val repo = com.zhique.core.project.ProjectRepository(dir)
        val pid = repo.create("已授项目", "<p></p>").id
        val registry = PermissionRegistry(repo)
        registry.set(pid, "camera", PState.GRANTED)
        val pushed = mutableListOf<String>()
        val gateway = FakeOsGateway(alreadyGranted = true)
        val env = ZqEnv(pid, dir, this, registry, { pushed += it }, osPermissions = gateway)
        val d = ZqDispatcher(env)
        d.register(FakeCapabilityFor(required = Capability.CAMERA))
        d.handle(zqCall(1, "fake", "hello"))
        assertEquals(0, gateway.requestCount, "已授予不得重复弹系统申请")
        assertEquals(1, pushed.size)
    }

    @Test
    fun `网关未接由能力自查兜底不阻断`() = runTest {
        val dir = tmp.newFolder()
        val repo = com.zhique.core.project.ProjectRepository(dir)
        val pid = repo.create("无网关项目", "<p></p>").id
        val registry = PermissionRegistry(repo)
        registry.set(pid, "camera", PState.GRANTED)
        val pushed = mutableListOf<String>()
        val env = ZqEnv(pid, dir, this, registry, { pushed += it }, osPermissions = null)
        val d = ZqDispatcher(env)
        d.register(FakeCapabilityFor(required = Capability.CAMERA))
        d.handle(zqCall(1, "fake", "hello"))
        assertEquals(listOf(ZqProtocol.resolveJs(1, true, "{\"ok\":true}")), pushed, "网关缺失走兜底，不回 denied")
    }

    @Test
    fun `无manifest权限能力不过系统门`() = runTest {
        val dir = tmp.newFolder()
        val repo = com.zhique.core.project.ProjectRepository(dir)
        val pid = repo.create("免门项目", "<p></p>").id
        val registry = PermissionRegistry(repo)
        registry.set(pid, "clipboard", PState.GRANTED)
        val pushed = mutableListOf<String>()
        val gateway = FakeOsGateway(alreadyGranted = false, requestResult = emptyMap())
        val env = ZqEnv(pid, dir, this, registry, { pushed += it }, osPermissions = gateway)
        val d = ZqDispatcher(env)
        d.register(FakeCapabilityFor(required = Capability.CLIPBOARD))
        d.handle(zqCall(1, "fake", "hello"))
        assertEquals(0, gateway.requestCount, "无系统权限的能力不得发起申请")
        assertEquals(1, pushed.size)
    }

    @Test
    fun `notification_requestPermission走矩阵返回granted`() = runTest {
        val dir = tmp.newFolder()
        val repo = com.zhique.core.project.ProjectRepository(dir)
        val pid = repo.create("通知项目", "<p></p>").id
        val registry = PermissionRegistry(repo)
        registry.set(pid, "notification", PState.GRANTED)
        val pushed = mutableListOf<String>()
        val env = ZqEnv(pid, dir, this, registry, { pushed += it })
        val d = ZqDispatcher(env)
        d.register(ZqNotify())
        d.handle(zqCall(9, "notification", "requestPermission"))
        assertEquals(
            listOf(ZqProtocol.resolveJs(9, true, "{\"permission\":\"granted\"}")),
            pushed,
            "W3C Notification 桥接：GRANTED → \"granted\"",
        )
        assertEquals(1, registry.usage(pid)["notification"], "requestPermission 也计真实使用")
    }
}

/** required 可配置的桩能力（OsGate 测试用）。 */
private class FakeCapabilityFor(
    override val required: Capability,
) : ZqCapability {
    override val ns = "fake"
    override val methods = listOf("hello")
    var executed = 0
    override fun why(fn: String) = "测试理由"
    override suspend fun call(fn: String, args: JsonObject, env: ZqEnv): kotlinx.serialization.json.JsonElement {
        executed++
        return buildJsonObject { put("ok", true) }
    }
}
