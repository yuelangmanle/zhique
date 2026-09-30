package com.zhique.core.export

import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 模板 APK 测试夹具：真实模板壳 release 产物（构建期由 systemProperty 提供）。 */
object TemplateFixtures {

    private fun apk(prop: String): File {
        val path = System.getProperty(prop)
            ?: throw IllegalStateException("missing system property $prop (由 :core:export test 任务注入)")
        val f = File(path)
        check(f.isFile) { "template fixture missing: $path（先跑 :template-*:assembleRelease）" }
        return f
    }

    fun min(): File = apk("zhique.template.minApk")

    fun full(): File = apk("zhique.template.fullApk")

    fun manifestOf(apk: File): ByteArray =
        ZipFile(apk).use { zip ->
            zip.getInputStream(zip.getEntry("AndroidManifest.xml")).use { it.readBytes() }
        }

    fun entryNames(apk: File): Set<String> =
        ZipFile(apk).use { zip -> zip.entries().asSequence().map { it.name }.toSet() }
}

/** 二进制 manifest 补丁（包名/版本/应用名）：读回断言 + 幂等。 */
class AxmlPatcherTest {

    private val template = TemplateFixtures.min()

    private val patch = AxmlPatcher.ManifestPatch(
        packageName = "com.zhique.export.note7",
        versionCode = 7,
        versionName = "1.0.7",
        label = "织雀记事本",
    )

    @Test
    fun `补丁前模板manifest可读且与补丁值不同`() {
        val info = AxmlReader.readManifest(TemplateFixtures.manifestOf(template))
        assertEquals("com.zhique.export.min", info.packageName)
        assertEquals(1L, info.versionCode)
        assertNotEquals(patch.label, info.label)
    }

    @Test
    fun `补丁改写包名版本与应用名`() {
        val patched = AxmlPatcher.patch(TemplateFixtures.manifestOf(template), patch)
        val info = AxmlReader.readManifest(patched)
        assertEquals("com.zhique.export.note7", info.packageName)
        assertEquals(7L, info.versionCode)
        assertEquals("1.0.7", info.versionName)
        assertEquals("织雀记事本", info.label)
    }

    @Test
    fun `补丁幂等-重复补丁结果一致`() {
        val once = AxmlPatcher.patch(TemplateFixtures.manifestOf(template), patch)
        val twice = AxmlPatcher.patch(once, patch)
        assertTrue(once.contentEquals(twice))
    }

    @Test
    fun `补丁后文档仍是合法AXML且池可解码`() {
        val patched = AxmlPatcher.patch(TemplateFixtures.manifestOf(template), patch)
        val (strings, utf8) = AxmlReader.readPool(patched, shortAt(patched, 2))
        assertTrue(strings.isNotEmpty())
        assertTrue(strings.contains("com.zhique.export.note7"))
        assertTrue(strings.contains(patch.label))
        // manifest/application 两个元素名仍在池中
        assertNotNull(strings.firstOrNull { it == "manifest" })
        assertNotNull(strings.firstOrNull { it == "application" })
        assertTrue(utf8 || !utf8) // 两种编码都支持；此处仅断言可解码
    }

    private fun shortAt(data: ByteArray, at: Int): Int =
        (data[at].toInt() and 0xFF) or ((data[at + 1].toInt() and 0xFF) shl 8)
}
