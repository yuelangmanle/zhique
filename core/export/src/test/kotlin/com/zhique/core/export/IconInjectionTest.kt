package com.zhique.core.export

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * M6 偏差③收口：launcher 图标注入——模板 arsc 资源 ID 查询、项目图标色映射、
 * AXML 图标引用改写，全部对真实模板壳 release 产物断言。
 */
class IconInjectionTest {

    private val injector = AssetInjector()

    private fun slatePatch() = AxmlPatcher.ManifestPatch(
        packageName = "com.zhique.export.icon",
        versionCode = 1,
        versionName = "1.0.1",
        label = "图标壳",
        iconResId = ResourceTableReader.entryId(
            injector.resourcesArsc(TemplateFixtures.min())!!,
            "mipmap",
            "icon_slate",
        ).toInt(),
        iconRef = IconPreset.SLATE.ref,
    )

    // ---- ResourceTableReader（真模板 arsc） ----

    @Test
    fun `min模板arsc查出两套图标ID且包段为用户段`() {
        val arsc = injector.resourcesArsc(TemplateFixtures.min())
        assertNotNull(arsc)
        val indigo = ResourceTableReader.entryId(arsc, "mipmap", "icon_indigo")
        val slate = ResourceTableReader.entryId(arsc, "mipmap", "icon_slate")
        assertEquals(0x7FL, indigo shr 24, "应用资源包段固定 0x7F")
        assertEquals(0x7FL, slate shr 24)
        assertNotEquals(indigo, slate, "两套预设必须是不同资源")
        // 前景 drawable 同样可查（自适应图标引用链完整性）
        assertNotNull(ResourceTableReader.entryId(arsc, "drawable", "icon_foreground"))
    }

    @Test
    fun `full模板同样预置两套图标`() {
        val arsc = injector.resourcesArsc(TemplateFixtures.full())
        assertNotNull(arsc)
        assertNotNull(ResourceTableReader.entryId(arsc, "mipmap", "icon_indigo"))
        assertNotNull(ResourceTableReader.entryId(arsc, "mipmap", "icon_slate"))
    }

    @Test
    fun `未知条目明确抛错不静默`() {
        val arsc = injector.resourcesArsc(TemplateFixtures.min())!!
        val e = assertFailsWith<ResourceTableReader.EntryNotFoundException> {
            ResourceTableReader.entryId(arsc, "mipmap", "icon_purple")
        }
        assertTrue(e.message!!.contains("mipmap/icon_purple"))
    }

    @Test
    fun `非arsc输入明确报错`() {
        assertFailsWith<IllegalArgumentException> {
            ResourceTableReader.entryId("not arsc".toByteArray(), "mipmap", "icon_indigo")
        }
    }

    // ---- 项目图标色 → 最近预设 ----

    @Test
    fun `图标色映射最近预设`() {
        assertEquals(IconPreset.INDIGO, nearestIconPreset("#46509F"), "品牌靛蓝映射靛蓝")
        assertEquals(IconPreset.INDIGO, nearestIconPreset("#FF46509F"), "ARGB 丢弃 alpha")
        assertEquals(IconPreset.INDIGO, nearestIconPreset("bad-color"), "非法色回落默认")
        assertEquals(IconPreset.INDIGO, nearestIconPreset(""), "空色回落默认")
        assertEquals(IconPreset.SLATE, nearestIconPreset("#2E3644"), "暗灰蓝映射石板")
        assertEquals(IconPreset.SLATE, nearestIconPreset("#333333"), "中性深灰映射石板")
        // 预设资源名与 rawValue 提示串一致
        assertEquals("@mipmap/icon_indigo", IconPreset.INDIGO.ref)
        assertEquals("@mipmap/icon_slate", IconPreset.SLATE.ref)
    }

    // ---- AXML 图标引用改写（真模板 manifest） ----

    @Test
    fun `icon引用改写-typed值换新资源id且模板默认可读回`() {
        val template = TemplateFixtures.min()
        val arsc = injector.resourcesArsc(template)!!
        val indigo = ResourceTableReader.entryId(arsc, "mipmap", "icon_indigo")
        val slate = ResourceTableReader.entryId(arsc, "mipmap", "icon_slate")

        // 模板默认 icon = 靛蓝（manifest android:icon="@mipmap/icon_indigo" 编译产物）
        val before = AxmlReader.readManifest(TemplateFixtures.manifestOf(template))
        assertEquals(indigo, before.iconResId, "模板默认图标应为靛蓝预设")

        // 补丁改到石板
        val patched = AxmlPatcher.patch(TemplateFixtures.manifestOf(template), slatePatch())
        val after = AxmlReader.readManifest(patched)
        assertEquals(slate, after.iconResId, "application@icon 引用须改写到石板资源 ID")
        // rawValue 提示串进了字符串池（运行时以 typed value 为准，raw 仅可读性）
        val (strings, _) = AxmlReader.readPool(patched, shortAt(patched, 2))
        assertTrue(strings.contains("@mipmap/icon_slate"))
        // 其余身份字段不受影响
        assertEquals("com.zhique.export.icon", after.packageName)
        assertEquals("图标壳", after.label)
    }

    @Test
    fun `icon引用改写幂等-重复补丁结果一致`() {
        val once = AxmlPatcher.patch(TemplateFixtures.manifestOf(TemplateFixtures.min()), slatePatch())
        val twice = AxmlPatcher.patch(once, slatePatch())
        assertTrue(once.contentEquals(twice))
    }

    @Test
    fun `icon属性缺失明确抛错不静默`() {
        // 负控制：真模板有 android:icon，同参数补丁应当成功
        val real = AxmlPatcher.patch(TemplateFixtures.manifestOf(TemplateFixtures.min()), slatePatch())
        assertNotNull(AxmlReader.readManifest(real).iconResId)
        // 合成无 icon 属性的 manifest → 明确抛错（模板底版与补丁器不匹配的守护）
        val e = assertFailsWith<IllegalStateException> {
            AxmlPatcher.patch(SyntheticAxml.minimalWithoutIcon(), slatePatch())
        }
        assertTrue(e.message!!.contains("属性缺失") && e.message!!.contains("icon"))
    }

    private fun shortAt(data: ByteArray, at: Int): Int =
        (data[at].toInt() and 0xFF) or ((data[at + 1].toInt() and 0xFF) shl 8)
}

/**
 * 极小合成 AXML：manifest(package/versionCode/versionName) + application(label)，
 * 无 icon 属性——AxmlPatcher 缺失守护用（异常路径的合成壳）。
 */
private object SyntheticAxml {

    fun minimalWithoutIcon(): ByteArray {
        val strings = listOf(
            "manifest", "application", "package", "versionCode", "versionName", "label",
            "com.zhique.export.x", "1", "1.0", "x",
        )
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(3, 0, 8, 0)) // RES_XML_TYPE + fileHeaderSize=8
        out.write(int(0)) // size 后补
        out.write(AxmlPatcher.StringPool.encode(strings, utf8 = false))
        out.write(element(strings, "manifest", listOf(
            "package" to "com.zhique.export.x",
            "versionCode" to "1",
            "versionName" to "1.0",
        )))
        out.write(element(strings, "application", listOf("label" to "x")))
        out.write(skipChunk(0x0103, 24)) // END 元素占位
        val bytes = out.toByteArray()
        System.arraycopy(int(bytes.size), 0, bytes, 4, 4)
        return bytes
    }

    private fun element(
        strings: List<String>,
        name: String,
        attrs: List<Pair<String, String>>,
    ): ByteArray {
        val size = 36 + 20 * attrs.size
        val el = ByteArray(size)
        put(el, 0, 0x0102, 2) // START_ELEMENT
        put(el, 2, 36, 2) // headerSize
        put(el, 4, size, 4)
        put(el, 20, strings.indexOf(name), 4) // 元素名
        put(el, 24, 20, 2) // attributeStart
        put(el, 26, 20, 2) // attributeSize
        put(el, 28, attrs.size, 2)
        attrs.forEachIndexed { i, (attrName, value) ->
            val at = 36 + i * 20
            put(el, at + 4, strings.indexOf(attrName), 4)
            put(el, at + 8, strings.indexOf(value), 4) // rawValue
            el[at + 15] = 0x03 // TYPE_STRING
            put(el, at + 16, strings.indexOf(value), 4)
        }
        return el
    }

    private fun skipChunk(type: Int, size: Int): ByteArray {
        val chunk = ByteArray(size)
        put(chunk, 0, type, 2)
        put(chunk, 2, 8, 2)
        put(chunk, 4, size, 4)
        return chunk
    }

    private fun put(data: ByteArray, at: Int, v: Int, width: Int) {
        for (i in 0 until width) data[at + i] = ((v shr (8 * i)) and 0xFF).toByte()
    }

    private fun int(v: Int): ByteArray = ByteArray(4).also { put(it, 0, v, 4) }
}
