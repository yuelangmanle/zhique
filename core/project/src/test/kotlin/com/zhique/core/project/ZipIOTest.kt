package com.zhique.core.project

import java.io.File
import java.io.IOException
import java.util.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class ZipIOTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun zipOf(vararg entries: Pair<String, ByteArray>): File {
        val f = File.createTempFile("zqzip-", ".zip", tmp.root)
        ZipOutputStream(f.outputStream().buffered()).use { out ->
            for ((name, bytes) in entries) {
                out.putNextEntry(ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return f
    }

    @Test
    fun `相对路径穿越条目被拒绝`() {
        val z = zipOf("../evil.txt" to byteArrayOf(1), "a/../../evil2" to byteArrayOf(2))
        assertFailsWith<IllegalArgumentException> { ZipIO.read(z) }
    }

    @Test
    fun `绝对路径与反斜杠条目被拒绝`() {
        assertFailsWith<IllegalArgumentException> { ZipIO.read(zipOf("/etc/evil" to byteArrayOf(1))) }
        assertFailsWith<IllegalArgumentException> { ZipIO.read(zipOf("a\\..\\evil" to byteArrayOf(1))) }
    }

    @Test
    fun `目录条目被拒绝`() {
        val f = File.createTempFile("zqzip-dir-", ".zip", tmp.root)
        ZipOutputStream(f.outputStream().buffered()).use { out ->
            out.putNextEntry(ZipEntry("dir/"))
            out.closeEntry()
        }
        assertFailsWith<IllegalArgumentException> { ZipIO.read(f) }
    }

    @Test
    fun `损坏zip截断抛IOException`() {
        val random = Random(42)
        val big = ByteArray(256 * 1024).also(random::nextBytes) // 不可压，保证截断落在数据区
        val good = zipOf("big.bin" to big)
        val bytes = good.readBytes()
        val bad = File(tmp.root, "truncated.zip")
        bad.writeBytes(bytes.copyOf((bytes.size * 3) / 5))
        assertFailsWith<IOException> { ZipIO.read(bad) }
    }

    @Test
    fun `解压总量超限抛ZipTooLargeException`() {
        // 2 × 33MB 全零条目：压缩后极小，但解压总量 66MB 超上限（防解压炸弹）
        val f = File.createTempFile("zqzip-bomb-", ".zip", tmp.root)
        ZipOutputStream(f.outputStream().buffered()).use { out ->
            for (i in 0..1) {
                out.putNextEntry(ZipEntry("big$i.bin"))
                out.write(ByteArray(33 * 1024 * 1024))
                out.closeEntry()
            }
        }
        assertFailsWith<ZipTooLargeException> { ZipIO.read(f) }
    }

    @Test
    fun `打包总量超限拒绝写出`() {
        val out = File(tmp.root, "too-big.zip")
        assertFailsWith<ZipTooLargeException> {
            ZipIO.write(out, mapOf("big" to ByteArray(ZipIO.MAX_TOTAL_BYTES.toInt() + 1)))
        }
        assertFalse(out.exists()) // 未写出任何内容
    }
}
