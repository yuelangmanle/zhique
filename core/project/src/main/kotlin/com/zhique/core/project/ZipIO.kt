package com.zhique.core.project

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** 纯 JVM zip 读写：entry 名 -> 内容字节。 */
object ZipIO {

    fun write(zipFile: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(zipFile.outputStream().buffered()).use { out ->
            for ((name, bytes) in entries) {
                out.putNextEntry(ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
        }
    }

    /** 全量读出 zip；拒绝绝对路径与「..」穿越（zip-slip）。 */
    fun read(zipFile: File): Map<String, ByteArray> {
        val result = LinkedHashMap<String, ByteArray>()
        ZipInputStream(zipFile.inputStream().buffered()).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                require(!entry.isDirectory) { "unexpected directory entry: ${entry.name}" }
                requireSafeName(entry.name)
                result[entry.name] = zin.readBytes()
                zin.closeEntry()
            }
        }
        return result
    }

    private fun requireSafeName(name: String) {
        require(name.isNotBlank()) { "blank entry name" }
        require(!name.startsWith('/') && !name.contains('\\')) { "unsafe entry name: $name" }
        require(name.split('/').none { it == ".." }) { "path traversal in entry: $name" }
    }
}
