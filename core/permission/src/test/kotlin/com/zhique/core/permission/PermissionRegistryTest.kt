package com.zhique.core.permission

import com.zhique.core.project.ProjectRepository
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/**
 * 权限注册表状态机（计划 Task 5.1）：四态迁移全路径、拒绝不重弹、
 * 吊销后调用语义、usage 计数、导出建议。纯 JVM（ProjectRepository 为纯 Kotlin）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PermissionRegistryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var repo: ProjectRepository
    private val asks = mutableListOf<PermissionAsk>()

    private fun newRepo(): ProjectRepository = ProjectRepository(tmp.newFolder())

    /** 记录 ask 载荷并按脚本顺序回答（null=不弹卡）。 */
    private fun registry(script: ArrayDeque<Boolean> = ArrayDeque(), repo: ProjectRepository? = null) =
        PermissionRegistry(
            repo ?: this.repo,
            prompt = { ask ->
                asks += ask
                if (script.isEmpty()) false else script.removeFirst()
            },
        )

    private fun projectId(): String = repo.create("演示", "<html></html>").id

    private fun persisted(projectId: String, capability: String): String? =
        repo.meta(projectId).permissions[capability]?.state

    // ---- 初始态 ----

    @Test
    fun `初始态为NOT_ASKED且未持久化`() {
        repo = newRepo()
        val reg = registry()
        val id = projectId()
        assertEquals(PState.NOT_ASKED, reg.state(id, "camera"))
        assertEquals(null, persisted(id, "camera"))
    }

    @Test
    fun `项目不存在时状态按NOT_ASKED`() {
        repo = newRepo()
        val reg = registry()
        assertEquals(PState.NOT_ASKED, reg.state("no-such-project", "camera"))
    }

    // ---- 授权卡路径 NOT_ASKED → ASKING → 终态 ----

    @Test
    fun `request授予则迁移GRANTED并持久化`() = runTest {
        repo = newRepo()
        val id = projectId()
        val reg = registry(ArrayDeque(listOf(true)))
        assertEquals(PState.GRANTED, reg.request(id, "camera", "拍照存档"))
        assertEquals(PState.GRANTED, reg.state(id, "camera"))
        assertEquals("GRANTED", persisted(id, "camera"))
    }

    @Test
    fun `request拒绝则迁移DENIED并持久化`() = runTest {
        repo = newRepo()
        val id = projectId()
        val reg = registry(ArrayDeque(listOf(false)))
        assertEquals(PState.DENIED, reg.request(id, "mic", "录音"))
        assertEquals("DENIED", persisted(id, "mic"))
    }

    @Test
    fun `授权卡载荷含项目名能力与理由`() = runTest {
        repo = newRepo()
        val id = projectId()
        registry(ArrayDeque(listOf(true))).request(id, "location", "展示附近天气")
        assertEquals(1, asks.size)
        assertEquals("演示", asks[0].projectName)
        assertEquals(id, asks[0].projectId)
        assertEquals("location", asks[0].capability)
        assertEquals("展示附近天气", asks[0].why)
    }

    @Test
    fun `ASKING期间状态对外可见`() = runTest {
        repo = newRepo()
        val id = projectId()
        val gate = CompletableDeferred<Boolean>()
        val reg = PermissionRegistry(repo, prompt = { gate.await() })
        val job = launch { reg.request(id, "camera", "拍照") }
        advanceUntilIdle()
        assertEquals(PState.ASKING, reg.state(id, "camera"))
        assertEquals("ASKING", persisted(id, "camera"))
        gate.complete(true)
        job.join()
        assertEquals(PState.GRANTED, reg.state(id, "camera"))
    }

    @Test
    fun `ASKING期间并发请求共乘同一张卡`() = runTest {
        repo = newRepo()
        val id = projectId()
        val gate = CompletableDeferred<Boolean>()
        val reg = PermissionRegistry(repo, prompt = { ask ->
            asks += ask
            gate.await()
        })
        val first = async { reg.request(id, "camera", "第一次") }
        advanceUntilIdle()
        val second = async { reg.request(id, "camera", "第二次") }
        advanceUntilIdle()
        gate.complete(true)
        assertEquals(PState.GRANTED, first.await())
        assertEquals(PState.GRANTED, second.await())
        assertEquals(listOf("第一次"), asks.map { it.why }, "并发请求必须共乘一张卡，不得双弹")
    }

    // ---- 终态不重弹 ----

    @Test
    fun `GRANTED后再调直接返回不重弹`() = runTest {
        repo = newRepo()
        val id = projectId()
        val reg = registry(ArrayDeque(listOf(true)))
        reg.request(id, "camera", "第一次")
        assertEquals(PState.GRANTED, reg.request(id, "camera", "第二次"))
        assertEquals(1, asks.size, "终态不得再次弹卡")
    }

    @Test
    fun `DENIED后再调直接DENIED不重弹`() = runTest {
        repo = newRepo()
        val id = projectId()
        val reg = registry(ArrayDeque(listOf(false)))
        reg.request(id, "mic", "第一次")
        assertEquals(PState.DENIED, reg.request(id, "mic", "第二次"))
        assertEquals(1, asks.size, "拒绝后不得重弹")
    }

    @Test
    fun `不同能力各自独立不互相串态`() = runTest {
        repo = newRepo()
        val id = projectId()
        val reg = registry(ArrayDeque(listOf(true, false)))
        assertEquals(PState.GRANTED, reg.request(id, "camera", "相机"))
        assertEquals(PState.DENIED, reg.request(id, "mic", "麦克风"))
        assertEquals(PState.GRANTED, reg.state(id, "camera"))
        assertEquals(PState.DENIED, reg.state(id, "mic"))
    }

    @Test
    fun `不同项目各自独立`() = runTest {
        repo = newRepo()
        val a = projectId()
        val b = projectId()
        val reg = registry(ArrayDeque(listOf(true, false)))
        assertEquals(PState.GRANTED, reg.request(a, "camera", "A 授予"))
        assertEquals(PState.DENIED, reg.request(b, "camera", "B 拒绝"))
        assertEquals(PState.GRANTED, reg.state(a, "camera"))
        assertEquals(PState.DENIED, reg.state(b, "camera"))
    }

    // ---- 异常与缺卡 ----

    @Test
    fun `授权卡抛异常按拒绝结算不悬挂`() = runTest {
        repo = newRepo()
        val id = projectId()
        val reg = PermissionRegistry(repo, prompt = { throw IllegalStateException("卡崩了") })
        assertEquals(PState.DENIED, reg.request(id, "camera", "拍照"))
        assertEquals("DENIED", persisted(id, "camera"))
    }

    @Test
    fun `无授权卡实现按拒绝结算`() = runTest {
        repo = newRepo()
        val id = projectId()
        val reg = PermissionRegistry(repo, prompt = null)
        assertEquals(PState.DENIED, reg.request(id, "camera", "拍照"))
    }

    @Test
    fun `未知能力拒绝进入状态机`() = runTest {
        repo = newRepo()
        val reg = registry()
        assertFailsWith<IllegalArgumentException> { reg.request(projectId(), "printer", "不存在的能力") }
        assertFailsWith<IllegalArgumentException> { reg.set(projectId(), "printer", PState.GRANTED) }
        assertEquals(0, asks.size)
    }

    // ---- revoke / set（权限中心直改） ----

    @Test
    fun `revoke后回到NOT_ASKED并持久化`() = runTest {
        repo = newRepo()
        val id = projectId()
        val reg = registry(ArrayDeque(listOf(true)))
        reg.request(id, "camera", "先授予")
        reg.revoke(id, "camera")
        assertEquals(PState.NOT_ASKED, reg.state(id, "camera"))
        assertEquals("NOT_ASKED", persisted(id, "camera"))
    }

    @Test
    fun `revoke后再request重新弹卡`() = runTest {
        repo = newRepo()
        val id = projectId()
        val script = ArrayDeque(listOf(true, false))
        val reg = registry(script)
        reg.request(id, "camera", "第一次")
        reg.revoke(id, "camera")
        assertEquals(PState.DENIED, reg.request(id, "camera", "吊销后再要"))
        assertEquals(2, asks.size, "吊销后必须重新过授权卡")
    }

    @Test
    fun `revoke时在途卡作废_迟到的卡答案不覆盖吊销结果`() = runTest {
        repo = newRepo()
        val id = projectId()
        val gate = CompletableDeferred<Boolean>()
        val reg = PermissionRegistry(repo, prompt = { gate.await() })
        val job = launch { reg.request(id, "camera", "在途") }
        advanceUntilIdle()
        assertEquals(PState.ASKING, reg.state(id, "camera"))
        reg.revoke(id, "camera") // 吊销发生在卡还开着时
        gate.complete(true) // 用户随后才点「授予」——迟到答案必须被丢弃
        job.join()
        assertEquals(PState.NOT_ASKED, reg.state(id, "camera"), "吊销语义：回到 NOT_ASKED")
    }

    @Test
    fun `权限中心直改GRANTED`() {
        repo = newRepo()
        val id = projectId()
        val reg = registry()
        reg.set(id, "bluetooth", PState.GRANTED)
        assertEquals(PState.GRANTED, reg.state(id, "bluetooth"))
        assertEquals("GRANTED", persisted(id, "bluetooth"))
    }

    @Test
    fun `直改DENIED后request不重弹`() = runTest {
        repo = newRepo()
        val id = projectId()
        val reg = registry()
        reg.set(id, "screen", PState.DENIED)
        assertEquals(PState.DENIED, reg.request(id, "screen", "再要也不弹"))
        assertEquals(0, asks.size)
    }

    @Test
    fun `set不接受ASKING`() {
        repo = newRepo()
        val reg = registry()
        assertFailsWith<IllegalArgumentException> { reg.set(projectId(), "camera", PState.ASKING) }
    }

    @Test
    fun `直改会结算在途授权卡_迟到答案不覆盖`() = runTest {
        repo = newRepo()
        val id = projectId()
        val gate = CompletableDeferred<Boolean>()
        val reg = PermissionRegistry(repo, prompt = { gate.await() })
        val job = launch { reg.request(id, "camera", "在途") }
        advanceUntilIdle()
        reg.set(id, "camera", PState.GRANTED) // 用户没点卡，权限中心直改
        gate.complete(false) // 迟到的卡答案（拒绝）不得覆盖直改结果
        job.join()
        assertEquals(PState.GRANTED, reg.state(id, "camera"))
    }

    @Test
    fun `吊销到授予的完整迁移回路`() = runTest {
        repo = newRepo()
        val id = projectId()
        val script = ArrayDeque(listOf(true, true))
        val reg = registry(script)
        assertEquals(PState.NOT_ASKED, reg.state(id, "file"))
        assertEquals(PState.GRANTED, reg.request(id, "file", "读文件"))
        reg.revoke(id, "file")
        assertEquals(PState.NOT_ASKED, reg.state(id, "file"))
        assertEquals(PState.GRANTED, reg.request(id, "file", "重新授予"))
        assertEquals(PState.GRANTED, reg.state(id, "file"))
    }

    // ---- usage 计数与导出建议 ----

    @Test
    fun `usage初始为空`() {
        repo = newRepo()
        val reg = registry()
        assertTrue(reg.usage(projectId()).isEmpty())
    }

    @Test
    fun `recordUse计数累加并持久化`() {
        repo = newRepo()
        val id = projectId()
        val reg = registry()
        reg.recordUse(id, "camera")
        reg.recordUse(id, "camera")
        reg.recordUse(id, "camera")
        reg.recordUse(id, "location")
        assertEquals(mapOf("camera" to 3, "location" to 1), reg.usage(id))
        assertEquals(3, repo.meta(id).permissionUsage["camera"], "usage 必须落盘 project.json")
    }

    @Test
    fun `recordUse未知能力拒绝`() {
        repo = newRepo()
        val reg = registry()
        assertFailsWith<IllegalStateException> { reg.recordUse(projectId(), "printer") }
    }

    @Test
    fun `suggestForExport仅含usage大于0的能力且升序`() {
        repo = newRepo()
        val id = projectId()
        val reg = registry()
        reg.recordUse(id, "sensor")
        reg.recordUse(id, "camera")
        reg.recordUse(id, "camera")
        // 未使用的能力（即使授权过）不进建议清单：授权≠使用
        reg.set(id, "bluetooth", PState.GRANTED)
        assertEquals(listOf("camera", "sensor"), reg.suggestForExport(id))
    }

    @Test
    fun `suggestForExport无使用时为空清单`() = runTest {
        repo = newRepo()
        val id = projectId()
        val reg = registry(ArrayDeque(listOf(true)))
        reg.request(id, "camera", "只授权未使用")
        assertTrue(reg.suggestForExport(id).isEmpty())
    }

    @Test
    fun `manifestForExport映射集中权限名并集`() {
        repo = newRepo()
        val id = projectId()
        val reg = registry()
        reg.recordUse(id, "camera")
        reg.recordUse(id, "location")
        reg.recordUse(id, "file") // 无 manifest 权限的能力不贡献
        val manifest = reg.manifestForExport(id)
        assertTrue(manifest.contains("android.permission.CAMERA"))
        assertTrue(manifest.contains("android.permission.ACCESS_FINE_LOCATION"))
        assertTrue(manifest.contains("android.permission.ACCESS_COARSE_LOCATION"))
        assertTrue(!manifest.contains("android.permission.RECORD_AUDIO"), "未使用能力不得进 manifest")
        assertEquals(manifest.size, manifest.toSet().size, "并集必须去重")
    }

    // ---- 矩阵视图与能力表 ----

    @Test
    fun `matrix覆盖全部十个能力且含持久化状态`() = runTest {
        repo = newRepo()
        val id = projectId()
        val reg = registry(ArrayDeque(listOf(true, false)))
        reg.request(id, "camera", "授予")
        reg.request(id, "mic", "拒绝")
        val matrix = reg.matrix(id)
        assertEquals(Capability.ALL.size, matrix.size)
        assertEquals(PState.GRANTED, matrix["camera"])
        assertEquals(PState.DENIED, matrix["mic"])
        assertEquals(PState.NOT_ASKED, matrix["clipboard"])
    }

    @Test
    fun `能力表恰好十个且id可逆解析`() {
        assertEquals(10, Capability.ALL.size)
        assertEquals(
            setOf("camera", "mic", "file", "location", "sensor", "bluetooth", "notification", "clipboard", "share", "screen"),
            Capability.ALL.map { it.id }.toSet(),
        )
        for (cap in Capability.ALL) assertEquals(cap, Capability.fromId(cap.id))
        assertEquals(null, Capability.fromId("printer"))
    }

    @Test
    fun `manifestFor未知能力忽略且并集保序去重`() {
        val manifest = Capability.manifestFor(listOf("camera", "printer", "camera", "mic"))
        assertEquals(
            listOf("android.permission.CAMERA", "android.permission.RECORD_AUDIO"),
            manifest,
        )
        assertTrue(Capability.manifestFor(listOf("file", "clipboard", "share", "screen")).isEmpty())
    }

    // ---- 持久化兼容 ----

    @Test
    fun `损坏的持久化状态按NOT_ASKED容错`() {
        repo = newRepo()
        val id = projectId()
        repo.setPermission(id, "camera", "MAYBE", lastAsked = 1L)
        val reg = registry()
        assertEquals(PState.NOT_ASKED, reg.state(id, "camera"))
        assertEquals(PState.NOT_ASKED, reg.matrix(id)["camera"])
    }

    @Test
    fun `重建注册表后状态从磁盘恢复`() = runTest {
        repo = newRepo()
        val id = projectId()
        val first = registry(ArrayDeque(listOf(true)))
        first.request(id, "camera", "授予")
        // 模拟进程重启：同一磁盘根，新注册表
        val reopened = PermissionRegistry(repo, prompt = { asks += it; false })
        assertEquals(PState.GRANTED, reopened.state(id, "camera"))
        val before = asks.size
        assertEquals(PState.GRANTED, reopened.request(id, "camera", "重启后仍不重弹"))
        assertEquals(before, asks.size, "重启后终态仍不重弹")
        assertEquals(1, before, "首次授予恰弹一张卡")
    }
}
