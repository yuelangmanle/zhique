package com.zhique.core.export

import com.android.zipflinger.BytesSource
import com.android.zipflinger.ZipArchive
import java.io.File
import java.util.zip.Deflater

/**
 * 资产注入器（M6 Task 6.2，zipflinger）：打开模板 APK → 删旧
 * `assets/project` 全部内容 → 写入项目文件（含注入用 project.json）→ 关闭重写。
 *
 * zipflinger 即 AGP 自带的 zip 引擎：既有条目的对齐布局在原位保留
 * （resources.arsc 等 stored 条目 4 字节对齐不变），新增 assets 条目为压缩条目
 * 无对齐要求——对齐规则与签名器（apksig alignment preserved）协同满足。
 *
 * **launcher 图标注入（M6 偏差③）**：launcher icon 是资源而非 assets，
 * 注入不能走 zip 层，resources.arsc 也不改——运行期替换方案：
 * 模板壳预置两套自适应图标（[IconPreset]），导出时把项目 [iconColor] 映射到
 * 最近预设，从模板 resources.arsc 查出资源 ID（[resourcesArsc] +
 * [ResourceTableReader]），由 [AxmlPatcher] 把 `application@icon` 引用改到
 * 该预设（引用已存在资源，无需动资源池）。
 */
class AssetInjector {

    /**
     * [template] → 注入 [files]（key=zip 条目名，如 `assets/project/index.html`）→ [out]。
     * 模板原字节不改动；[out] 为全新文件。
     */
    fun inject(template: File, files: Map<String, ByteArray>, out: File) {
        require(template.isFile) { "template apk not found: $template" }
        out.parentFile?.mkdirs()
        template.copyTo(out, overwrite = true)
        ZipArchive(out.toPath()).use { zip ->
            for (name in zip.listEntries()) {
                if (name.startsWith(PROJECT_ASSETS_PREFIX)) {
                    zip.delete(name)
                }
            }
            for ((name, bytes) in files) {
                require(name.startsWith(PROJECT_ASSETS_PREFIX)) {
                    "refusing to inject outside assets/project: $name"
                }
                zip.add(BytesSource(bytes, name, Deflater.BEST_SPEED))
            }
        }
    }

    /** 收集项目目录全部文件 → `assets/project/<rel>` 字节表（供 [inject]）。 */
    fun collect(projectDir: File, extra: Map<String, ByteArray> = emptyMap()): Map<String, ByteArray> {
        val files = LinkedHashMap<String, ByteArray>()
        collectDir(projectDir, "", files)
        files.putAll(extra)
        return files
    }

    /** 读模板/导出包的 `resources.arsc`（图标资源 ID 查询用；缺失返回 null）。 */
    fun resourcesArsc(apk: File): ByteArray? =
        runCatching {
            java.util.zip.ZipFile(apk).use { zip ->
                zip.getInputStream(zip.getEntry(ARSC_ENTRY)).use { it.readBytes() }
            }
        }.getOrNull()

    /** 读 APK 内任一条目字节（测试核对用；缺失返回 null）。 */
    fun entryBytes(apk: File, name: String): ByteArray? =
        runCatching {
            java.util.zip.ZipFile(apk).use { zip ->
                zip.getInputStream(zip.getEntry(name)).use { it.readBytes() }
            }
        }.getOrNull()

    private fun collectDir(dir: File, rel: String, out: MutableMap<String, ByteArray>) {
        for (child in dir.listFiles() ?: emptyArray()) {
            val childRel = if (rel.isEmpty()) child.name else "$rel/${child.name}"
            if (child.isDirectory) {
                collectDir(child, childRel, out)
            } else {
                out["$PROJECT_ASSETS_PREFIX$childRel"] = child.readBytes()
            }
        }
    }

    companion object {
        const val PROJECT_ASSETS_PREFIX = "assets/project/"
        const val PROJECT_JSON_ENTRY = "assets/project/project.json"
        const val ARSC_ENTRY = "resources.arsc"
    }
}

/**
 * 模板壳预置 launcher 图标预设（两套自适应图标资源随模板构建）：
 * 用户项目图标色映射到感知距离最近的预设（ITU-R BT.709 加权 RGB 距离）。
 *
 * @param resName 模板里 `mipmap/` 下的条目名（AxmlPatcher 改引用写回 rawValue 用）
 */
enum class IconPreset(val resName: String, private val r: Int, private val g: Int, private val b: Int) {
    /** 靛蓝（织雀语义色，默认）。 */
    INDIGO("icon_indigo", 0x46, 0x50, 0x9F),

    /** 石板蓝（偏暗/偏灰的项目色映射目标）。 */
    SLATE("icon_slate", 0x41, 0x4B, 0x5C),
    ;

    /** `@mipmap/<resName>`（manifest rawValue 提示串）。 */
    val ref: String get() = "@mipmap/$resName"

    /** ITU-R BT.709 感知加权 RGB 距离平方（绿最重、红次之、蓝最轻）。 */
    fun distanceTo(r2: Int, g2: Int, b2: Int): Long {
        val dr = r2 - r
        val dg = g2 - g
        val db = b2 - b
        return 2L * dr * dr + 4L * dg * dg + 3L * db * db
    }

    companion object {
        /** 解析项目图标色 → 最近预设；非法/缺省色一律回落 [INDIGO]。 */
        fun nearest(colorHex: String): IconPreset {
            val rgb = parseHex(colorHex) ?: return INDIGO
            return entries.minBy { it.distanceTo(rgb[0], rgb[1], rgb[2]) }
        }

        /** `#RGB/#ARRGGBB/#RRGGBB` → (r,g,b)；非法返回 null。 */
        private fun parseHex(colorHex: String): IntArray? {
            val hex = colorHex.trim().removePrefix("#")
            val rgb = when (hex.length) {
                3 -> hex.map { "$it$it" }.joinToString("")
                6 -> hex
                8 -> hex.substring(2) // ARGB：丢弃 alpha（launcher 背景色不透明）
                else -> return null
            }
            val v = rgb.toIntOrNull(16) ?: return null
            return intArrayOf((v shr 16) and 0xFF, (v shr 8) and 0xFF, v and 0xFF)
        }
    }
}

/** 解析项目图标色 → 最近预设；非法/缺省色一律回落 [IconPreset.INDIGO]。 */
fun nearestIconPreset(colorHex: String): IconPreset = IconPreset.nearest(colorHex)
