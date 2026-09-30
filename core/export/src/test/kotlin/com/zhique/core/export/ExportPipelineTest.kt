package com.zhique.core.export

import com.zhique.core.project.ProjectRepository
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.rules.TemporaryFolder

/**
 * 端到端出包管线（真实模板壳底版）：注入 → AXML 身份补丁 → v2+v3 签名 →
 * ApkVerifier 校验 → ExportRecord（含证书 SHA-256）写回。
 */
class ExportPipelineTest {

    private val tmp = TemporaryFolder()

    private fun wire(variant: String): Triple<ProjectRepository, KeystoreManager, ExportService> {
        tmp.create()
        val root = tmp.newFolder()
        val repo = ProjectRepository(root)
        val keystore = KeystoreManager(root, com.zhique.core.common.crypto.CryptoStore(softwareKey()))
        val pipeline = ExportPipeline(
            templates = object : TemplateProvider {
                override fun template(v: String): File = if (v == "full") TemplateFixtures.full() else TemplateFixtures.min()
            },
            signer = Signer(),
            workDir = File(root, "work"),
        )
        val version = VersionManager(repo)
        val service = ExportService(repo, version, pipeline, keystore)
        return Triple(repo, keystore, service)
    }

    private fun manifestOf(apk: File): ByteArray =
        ZipFile(apk).use { zip ->
            zip.getInputStream(zip.getEntry("AndroidManifest.xml")).use { it.readBytes() }
        }

    @Test
    fun `min变体端到端出包-身份与签名与资产全对`() {
        val (repo, keystore, service) = wire("min")
        val meta = repo.create("织雀记事本", "<h1>hi</h1>")
        val outcome = service.export(meta.id, "织雀记事本", "min")

        // 校验过的产物 + 指纹与密钥库一致
        assertEquals(keystore.certificateSha256(), outcome.record.certSha256)
        assertEquals(1, outcome.record.versionCode)
        assertTrue(outcome.apk.isFile)

        // manifest 身份补丁生效（签过的包同样可读）
        val info = AxmlReader.readManifest(manifestOf(outcome.apk))
        assertEquals(outcome.record.packageName, info.packageName)
        assertEquals(1L, info.versionCode)
        assertEquals("1.0.1", info.versionName)
        assertEquals("织雀记事本", info.label)
        assertTrue(info.packageName!!.startsWith("com.zhique.export."))

        // 项目资产进了包
        ZipFile(outcome.apk).use { zip ->
            val html = zip.getInputStream(zip.getEntry("assets/project/index.html")).use { it.readBytes() }
            assertEquals("<h1>hi</h1>", html.decodeToString())
            assertNotNull(zip.getEntry("assets/project/project.json"))
        }

        // 记录写回 project.json（含证书 SHA-256）
        val record = repo.meta(meta.id).export
        assertNotNull(record)
        assertEquals(outcome.record, record)
        assertEquals(64, record!!.certSha256.length)
    }

    @Test
    fun `full变体端到端出包-版本自增且包名稳定`() {
        val (repo, keystore, service) = wire("full")
        val meta = repo.create("翻译卡", "<p></p>")
        val v1 = service.export(meta.id, "翻译卡", "full")
        val v2 = service.export(meta.id, "翻译卡", "full")

        assertEquals(1, v1.record.versionCode)
        assertEquals(2, v2.record.versionCode)
        assertEquals(v1.record.packageName, v2.record.packageName)
        assertEquals("1.0.2", v2.record.versionName)
        assertEquals(keystore.certificateSha256(), v2.record.certSha256)
        assertEquals("full", v2.record.variant)
        // 版本号写回
        assertEquals(2, repo.meta(meta.id).export!!.versionCode)
    }

    @Test
    fun `管线失败清理工作残骸`() {
        tmp.create()
        val root = tmp.newFolder()
        val repo = ProjectRepository(root)
        val keystore = KeystoreManager(root, com.zhique.core.common.crypto.CryptoStore(softwareKey()))
        val workDir = File(root, "work")
        // 坏模板：AndroidManifest.xml 非 AXML → 注入成功、补丁失败
        val badTemplate = File(root, "bad-template.apk")
        java.util.zip.ZipOutputStream(badTemplate.outputStream()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("AndroidManifest.xml"))
            zip.write("这不是二进制XML".toByteArray())
            zip.closeEntry()
        }
        val pipeline = ExportPipeline(
            templates = object : TemplateProvider {
                override fun template(v: String): File = badTemplate
            },
            signer = Signer(),
            workDir = workDir,
        )
        val meta = repo.create("坏模板", "<p></p>")
        val e = runCatching {
            ExportService(repo, VersionManager(repo), pipeline, keystore).export(meta.id, "坏模板", "min")
        }
        assertTrue(e.isFailure)
        // unsigned/signed 残骸一律清理
        assertEquals(emptyList(), workDir.listFiles()?.filter { it.name.endsWith(".apk") }.orEmpty())
    }

    @Test
    fun `两个项目导出包名不同可共存`() {
        val (repo, _, service) = wire("min")
        val a = repo.create("计算器", "<p></p>")
        val b = repo.create("计算器", "<p></p>")
        val oa = service.export(a.id, "计算器", "min")
        val ob = service.export(b.id, "计算器", "min")
        assertTrue(oa.record.packageName != ob.record.packageName)
    }
}
