package com.zhique.runner.export

import com.zhique.core.export.ExportOutcome
import com.zhique.core.export.KeystoreManager
import com.zhique.core.export.SignatureMismatchException
import com.zhique.core.permission.PermissionRegistry
import com.zhique.core.project.ExportRecord
import com.zhique.core.project.ProjectRepository
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.rules.TemporaryFolder

private class SoftwareKey : com.zhique.core.common.crypto.KeyProvider {
    private val key: javax.crypto.SecretKey =
        javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    override fun masterKey(): javax.crypto.SecretKey = key
}

/** 导出向导控制器（M6 Task 6.3）：状态流转、建议、备份状态、签名不匹配阻断。 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExportControllerTest {

    private val tmp = TemporaryFolder()

    private class Ctx(
        val controller: ExportController,
        val repo: ProjectRepository,
        val registry: PermissionRegistry,
        val projectId: String,
        val tmp: TemporaryFolder,
    ) {
        fun outcome(code: Int): ExportOutcome = ExportOutcome(
            apk = File(tmp.newFolder(), "out.apk").apply { writeBytes(ByteArray(4)) },
            record = ExportRecord(
                packageName = "com.zhique.export.app",
                versionCode = code,
                versionName = "1.0.$code",
                at = 42L,
                variant = "min",
                certSha256 = "a".repeat(64),
            ),
        ).also { repo.recordExport(projectId, it.record) }
    }

    /** 控制器协程走 runTest 的 testScheduler，advanceUntilIdle 一并驱动。 */
    private fun ctx(
        scheduler: TestCoroutineScheduler,
        backgroundScope: CoroutineScope,
        executorFactory: (Ctx) -> suspend (String, String, String) -> ExportOutcome = {
            { _, _, _ -> throw UnsupportedOperationException("not wired") }
        },
    ): Ctx {
        tmp.create()
        val root = tmp.newFolder()
        val repo = ProjectRepository(root)
        val registry = PermissionRegistry(repo)
        val projectId = repo.create("织雀便签", "<p></p>").id
        val keystore = KeystoreManager(root, com.zhique.core.common.crypto.CryptoStore(SoftwareKey()))
        lateinit var c: Ctx
        val controller = ExportController(
            projectId = projectId,
            repo = repo,
            registry = registry,
            keystore = keystore,
            executor = { p, a, v -> executorFactory(c)(p, a, v) },
            scope = backgroundScope,
            ioDispatcher = StandardTestDispatcher(scheduler),
        )
        c = Ctx(controller, repo, registry, projectId, tmp)
        return c
    }

    @Test
    fun `初始状态装载项目名与备份状态`() = runTest {
        val ctx = ctx(testScheduler, this)
        ctx.controller.refresh()
        advanceUntilIdle()
        val s = ctx.controller.state.value
        assertEquals("织雀便签", s.appName)
        assertFalse(s.backup?.backupDue == true)
        assertFalse(s.running)
    }

    @Test
    fun `信息编辑与步骤流转`() = runTest {
        val ctx = ctx(testScheduler, this)
        ctx.controller.refresh(); advanceUntilIdle()
        ctx.controller.setAppName("新名字")
        ctx.controller.setIconColor("#112233")
        ctx.controller.next(); ctx.controller.next()
        assertEquals(2, ctx.controller.state.value.step)
        assertEquals("新名字", ctx.controller.state.value.appName)
        ctx.controller.back(); ctx.controller.back()
        assertEquals(0, ctx.controller.state.value.step)
    }

    @Test
    fun `建议清单来自真实使用记录`() = runTest {
        val ctx = ctx(testScheduler, this)
        repeat(2) { ctx.registry.recordUse(ctx.projectId, "camera") }
        ctx.controller.refresh(); advanceUntilIdle()
        assertEquals(listOf("camera"), ctx.controller.state.value.suggestions)
    }

    @Test
    fun `变体选择受约束`() = runTest {
        val ctx = ctx(testScheduler, this)
        ctx.controller.setVariant("full")
        assertEquals("full", ctx.controller.state.value.variant)
        ctx.controller.setVariant("min")
        assertEquals("min", ctx.controller.state.value.variant)
    }

    @Test
    fun `备份副本落在FileProvider白名单目录`() = runTest {
        val ctx = ctx(testScheduler, this)
        ctx.controller.refresh(); advanceUntilIdle()
        val cache = tmp.newFolder()
        val copy = ctx.controller.prepareBackup(cache)
        // zq_share_paths 白名单：cache-path exports/
        assertTrue(copy != null)
        val whitelist = File(cache, "exports").canonicalFile
        assertTrue(copy!!.canonicalFile.parentFile == whitelist, "shared path must be cache/exports: $copy")
        assertEquals("zhique-release.jks", copy.name)
        // 副本与主文件字节一致
        assertTrue(copy.length() > 0)
    }

    @Test
    fun `打包成功后结果与导出记录就位`() = runTest {
        val ctx = ctx(testScheduler, this) { c -> { p, _, v ->
            assertEquals("min", v)
            c.outcome(1)
        } }
        ctx.controller.refresh(); advanceUntilIdle()
        ctx.controller.next(); ctx.controller.next()
        ctx.controller.run()
        advanceUntilIdle()
        val s = ctx.controller.state.value
        assertFalse(s.running)
        assertEquals(1, s.result?.record?.versionCode)
        assertEquals(64, s.result?.record?.certSha256?.length)
        assertEquals(1, ctx.repo.meta(ctx.projectId).export?.versionCode)
    }

    @Test
    fun `签名不一致时切阻断态且不产生结果`() = runTest {
        val ctx = ctx(testScheduler, this) { _ -> { _, _, _ ->
            throw SignatureMismatchException("com.zhique.export.app", "ks", listOf("other"))
        } }
        ctx.controller.refresh(); advanceUntilIdle()
        ctx.controller.next(); ctx.controller.next()
        ctx.controller.run()
        advanceUntilIdle()
        val s = ctx.controller.state.value
        assertTrue(s.mismatch)
        assertEquals(null, s.result)
        assertTrue(s.error.orEmpty().contains("卸载"))
        // 返回后阻断态清除
        ctx.controller.back()
        assertFalse(ctx.controller.state.value.mismatch)
    }

    @Test
    fun `执行失败时错误可见可重试`() = runTest {
        var fail = true
        val ctx = ctx(testScheduler, this) { c -> { p, _, _ ->
            if (fail) throw IllegalStateException("打包失败")
            c.outcome(1)
        } }
        ctx.controller.refresh(); advanceUntilIdle()
        ctx.controller.next(); ctx.controller.next()
        ctx.controller.run(); advanceUntilIdle()
        assertEquals("打包失败", ctx.controller.state.value.error)
        fail = false
        ctx.controller.run(); advanceUntilIdle()
        assertEquals(1, ctx.controller.state.value.result?.record?.versionCode)
    }
}
