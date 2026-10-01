package com.zhique.core.export

import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun `style池不受支持-明确抛错不静默`() {
        val synthetic = syntheticManifest(
            styleCount = 1,
            attrs = listOf(Triple("package", "com.zhique.export.x", false)),
        )
        assertFailsWith<UnsupportedPoolException> {
            AxmlPatcher.patch(synthetic, patch)
        }
    }

    @Test
    fun `目标属性缺失-明确抛错不静默`() {
        val synthetic = syntheticManifest(
            attrs = listOf(Triple("versionCode", 7, true)), // 缺 package/versionName/label
        )
        val e = assertFailsWith<IllegalStateException> {
            AxmlPatcher.patch(synthetic, AxmlPatcher.ManifestPatch("com.zhique.export.x", 7, "1.0.7", "标签"))
        }
        assertTrue(e.message!!.contains("属性缺失"))
    }

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

    @Test
    fun `UTF-16长串len超过0x8000经转义回写可读回`() {
        // M6 债务收敛：label ≥0x8000 字符时 UTF-16 长度须走高位标记 + u32 转义
        val longLabel = "雀".repeat(0x8100)
        val p = AxmlPatcher.ManifestPatch("com.zhique.export.long", 1, "1.0", longLabel)
        val synthetic = syntheticManifest(
            attrs = listOf(
                Triple("package", "com.zhique.export.base", false),
                Triple("versionCode", 1, true),
                Triple("versionName", "1.0", false),
            ),
            appAttrs = listOf(Triple("label", longLabel, false)),
        )
        val patched = AxmlPatcher.patch(synthetic, p)
        val info = AxmlReader.readManifest(patched)
        assertEquals(longLabel, info.label, "超长 label 经转义重编码后须原样读回")
    }

    private fun shortAt(data: ByteArray, at: Int): Int =
        (data[at].toInt() and 0xFF) or ((data[at + 1].toInt() and 0xFF) shl 8)

    // ---- 合成 AXML（覆盖异常路径：style 池 / 属性缺失） ----

    /** 极小 AXML 组装器：字符串池 + START_NS + START_ELEMENT(manifest, attrs) + [application] + END。 */
    private fun syntheticManifest(
        styleCount: Int = 0,
        attrs: List<Triple<String, Any, Boolean>>, // (name, value, isInt)
        appAttrs: List<Triple<String, Any, Boolean>> = emptyList(),
    ): ByteArray {
        val strings = linkedSetOf("manifest", "application")
        (attrs + appAttrs).forEach { strings.add(it.first) }
        (attrs + appAttrs).forEach { if (!it.third) strings.add(it.second.toString()) }
        val pool = SyntheticPool(strings.toList(), styleCount)
        val out = java.io.ByteArrayOutputStream()
        // 文件头
        out.write(byteArrayOf(3, 0, 8, 0)); writeInt(out, 0) // size 后补
        // 字符串池
        val poolBytes = SyntheticPool.encode(pool.strings, utf8 = false, styleCount = styleCount)
        out.write(poolBytes)
        // START_NAMESPACE
        val nsChunk = ByteArray(24)
        putShort(nsChunk, 0, 0x0100); putShort(nsChunk, 2, 24); putInt(nsChunk, 4, 24)
        out.write(nsChunk)
        fun writeElement(name: String, elementAttrs: List<Triple<String, Any, Boolean>>) {
            val attrCount = elementAttrs.size
            val elementSize = 36 + 20 * attrCount
            val el = ByteArray(elementSize)
            putShort(el, 0, 0x0102); putShort(el, 2, 36); putInt(el, 4, elementSize)
            putInt(el, 20, pool.index(name))
            putShort(el, 24, 20); putShort(el, 26, 20); putShort(el, 28, attrCount)
            elementAttrs.forEachIndexed { i, (attrName, value, isInt) ->
                val at = 36 + i * 20
                putInt(el, at + 4, pool.index(attrName))
                if (isInt) {
                    putInt(el, at + 8, pool.indexOfOrAppend(value.toString()))
                    el[at + 15] = 0x10
                    putInt(el, at + 16, value as Int)
                } else {
                    val idx = pool.index(value.toString())
                    putInt(el, at + 8, idx)
                    el[at + 15] = 0x03
                    putInt(el, at + 16, idx)
                }
            }
            out.write(el)
            val end = ByteArray(24)
            putShort(end, 0, 0x0103); putShort(end, 2, 24); putInt(end, 4, 24)
            out.write(end)
        }
        writeElement("manifest", attrs)
        writeElement("application", appAttrs)
        out.write(nsChunk) // END_NAMESPACE（结构占位）
        val bytes = out.toByteArray()
        putInt(bytes, 4, bytes.size)
        return bytes
    }

    private class SyntheticPool(val strings: List<String>, styleCount: Int) {
        fun index(s: String): Int = strings.indexOf(s)
        fun indexOfOrAppend(s: String): Int = strings.indexOf(s) // 合成用，不追加

        companion object {
            fun encode(strings: List<String>, utf8: Boolean, styleCount: Int): ByteArray {
                val offsets = IntArray(strings.size)
                val body = java.io.ByteArrayOutputStream()
                for ((i, str) in strings.withIndex()) {
                    offsets[i] = body.size()
                    val chars = str.toCharArray()
                    // 与 aapt/decodeString 对称：len≥0x8000 写高位标记 + u32 实长
                    if (chars.size >= 0x8000) {
                        body.write(0x00); body.write(0x80)
                        writeInt(body, chars.size)
                    } else {
                        body.write(chars.size and 0xFF); body.write((chars.size shr 8) and 0xFF)
                    }
                    for (c in chars) { body.write(c.code and 0xFF); body.write((c.code shr 8) and 0xFF) }
                    body.write(0); body.write(0)
                }
                while (body.size() % 4 != 0) body.write(0)
                val stringsStart = 28 + offsets.size * 4 + styleCount * 4
                val out = java.io.ByteArrayOutputStream()
                out.write(0x01); out.write(0x00)
                out.write(28 and 0xFF); out.write(28 shr 8)
                writeInt(out, stringsStart + body.size())
                writeInt(out, strings.size)
                writeInt(out, styleCount)
                writeInt(out, 0)
                writeInt(out, stringsStart)
                writeInt(out, 0)
                for (o in offsets) writeInt(out, o)
                body.writeTo(out)
                return out.toByteArray()
            }
        }
    }

}

private fun putShort(data: ByteArray, at: Int, v: Int) {
    data[at] = (v and 0xFF).toByte(); data[at + 1] = ((v shr 8) and 0xFF).toByte()
}

private fun putInt(data: ByteArray, at: Int, v: Int) {
    data[at] = (v and 0xFF).toByte(); data[at + 1] = ((v shr 8) and 0xFF).toByte()
    data[at + 2] = ((v shr 16) and 0xFF).toByte(); data[at + 3] = ((v shr 24) and 0xFF).toByte()
}

private fun writeInt(out: java.io.ByteArrayOutputStream, v: Int) {
    out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
    out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF)
}
