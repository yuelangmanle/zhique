package com.zhique.core.export

import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.rules.TemporaryFolder

/** 资产注入器（zipflinger）：删旧 assets/project、写入项目文件、其余条目原样保留。 */
class AssetInjectorTest {

    private val tmp = TemporaryFolder()

    private fun out(): File = File(tmp.newFolder(), "out.apk")

    private fun projectFiles(): Map<String, ByteArray> = mapOf(
        AssetInjector.PROJECT_ASSETS_PREFIX + "index.html" to "<h1>你好</h1>".toByteArray(),
        AssetInjector.PROJECT_ASSETS_PREFIX + "js/app.js" to "console.log(1)".toByteArray(),
        AssetInjector.PROJECT_JSON_ENTRY to "{}".toByteArray(),
    )

    @Test
    fun `注入覆盖模板占位项目页`() {
        tmp.create()
        val out = out()
        AssetInjector().inject(TemplateFixtures.min(), projectFiles(), out)
        ZipFile(out).use { zip ->
            val entry = zip.getEntry("assets/project/index.html")
            assertNotNull(entry)
            val html = zip.getInputStream(entry).use { it.readBytes().decodeToString() }
            // 模板占位页（织雀壳自检页）已被项目内容覆盖
            assertEquals("<h1>你好</h1>", html)
            assertTrue(!html.contains("织雀壳"))
        }
    }

    @Test
    fun `注入的项目文件与嵌套目录可读`() {
        tmp.create()
        val out = out()
        AssetInjector().inject(TemplateFixtures.min(), projectFiles(), out)
        ZipFile(out).use { zip ->
            val html = zip.getInputStream(zip.getEntry("assets/project/index.html")).use { it.readBytes() }
            assertEquals("<h1>你好</h1>", html.decodeToString())
            val js = zip.getInputStream(zip.getEntry("assets/project/js/app.js")).use { it.readBytes() }
            assertEquals("console.log(1)", js.decodeToString())
            val meta = zip.getInputStream(zip.getEntry(AssetInjector.PROJECT_JSON_ENTRY)).use { it.readBytes() }
            assertEquals("{}", meta.decodeToString())
        }
    }

    @Test
    fun `模板其余条目原样保留`() {
        tmp.create()
        val before = TemplateFixtures.entryNames(TemplateFixtures.min())
        val out = out()
        AssetInjector().inject(TemplateFixtures.min(), projectFiles(), out)
        val after = TemplateFixtures.entryNames(out)
        // 模板条目都在（除被替换的 assets/project）
        for (name in before) {
            if (name.startsWith("META-INF/")) continue
            assertTrue(after.contains(name), "missing after inject: $name")
        }
        assertNotNull(after.firstOrNull { it == "AndroidManifest.xml" })
        assertNotNull(after.firstOrNull { it == "classes.dex" })
    }

    @Test
    fun `重复注入清掉上一次的项目文件`() {
        tmp.create()
        val out = out()
        val injector = AssetInjector()
        injector.inject(TemplateFixtures.min(), projectFiles(), out)
        val second = mapOf(AssetInjector.PROJECT_ASSETS_PREFIX + "index.html" to "v2".toByteArray())
        injector.inject(out, second, File(out.parentFile, "out2.apk"))
        ZipFile(File(out.parentFile, "out2.apk")).use { zip ->
            assertEquals("v2", zip.getInputStream(zip.getEntry("assets/project/index.html")).use { it.readBytes().decodeToString() })
            assertNull(zip.getEntry("assets/project/js/app.js"))
            assertFalse(zip.entries().asSequence().any { it.name == AssetInjector.PROJECT_JSON_ENTRY })
        }
    }
}
