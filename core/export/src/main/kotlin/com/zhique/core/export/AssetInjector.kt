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
    }
}
