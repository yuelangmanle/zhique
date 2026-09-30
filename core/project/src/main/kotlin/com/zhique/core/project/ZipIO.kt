package com.zhique.core.project

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** zip 解压/打包总量超过上限（防解压炸弹）。 */
class ZipTooLargeException(message: String) : IOException(message)

/** 纯 JVM zip 读写：entry 名 -> 内容字节。 */
object ZipIO {

    const val MAX_TOTAL_BYTES: Long = 64L * 1024 * 1024

    fun write(zipFile: File, entries: Map<String, ByteArray>) {
        val total = entries.values.sumOf { it.size.toLong() }
        if (total > MAX_TOTAL_BYTES) {
            throw ZipTooLargeException("zip entries total $total bytes > $MAX_TOTAL_BYTES")
        }
        ZipOutputStream(zipFile.outputStream().buffered()).use { out ->
            for ((name, bytes) in entries) {
                out.putNextEntry(ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
        }
    }

    /** 全量读出 zip；拒绝绝对路径、反斜杠与「..」穿越（zip-slip），总量超限抛 [ZipTooLargeException]。 */
    fun read(zipFile: File): Map<String, ByteArray> {
        val result = LinkedHashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(zipFile.inputStream().buffered()).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                require(!entry.isDirectory) { "unexpected directory entry: ${entry.name}" }
                requireSafeName(entry.name)
                val bytes = readBounded(zin, MAX_TOTAL_BYTES - total)
                total += bytes.size
                result[entry.name] = bytes
                zin.closeEntry()
            }
        }
        return result
    }

    private fun readBounded(input: ZipInputStream, budget: Long): ByteArray {
        val buf = ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            if (buf.size() + n > budget) throw ZipTooLargeException("zip exceeds $MAX_TOTAL_BYTES bytes")
            buf.write(chunk, 0, n)
        }
        return buf.toByteArray()
    }

    private fun requireSafeName(name: String) {
        require(name.isNotBlank()) { "blank entry name" }
        require(!name.startsWith('/') && !name.contains('\\')) { "unsafe entry name: $name" }
        require(name.split('/').none { it == ".." }) { "path traversal in entry: $name" }
    }
}
