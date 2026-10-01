package com.zhique.core.export

import java.io.ByteArrayOutputStream

/**
 * 二进制 AndroidManifest.xml（AXML）补丁器（M6 Task 6.2）：
 *
 * 模板壳 manifest 是 aapt 编译的二进制；端上导出须按项目改写
 * `manifest@package` / `manifest@versionCode` / `manifest@versionName` /
 * `application@label` 四个属性。策略：
 *
 * 1. 解码字符串池 → 追加新串（既有索引不动 → 属性名/资源映射不失效）→
 *    重编码整池；
 * 2. 定位 `manifest`/`application` START_ELEMENT 的属性项，改写
 *    rawValue/typedData（versionCode 只改 int 数值）；
 * 3. 文件头尺寸重排，其余 chunk 原样保留。
 *
 * 只支持模板这类「无 style 池」的 AXML（导出底版由我们构建，可控）。
 */
/** 字符串池含 style 表：模板 manifest 不应出现，拒绝静默损坏。 */
class UnsupportedPoolException(message: String) : IllegalStateException(message)

object AxmlPatcher {

    /** 需要写入的四个身份字段。 */
    data class ManifestPatch(
        val packageName: String,
        val versionCode: Int,
        val versionName: String,
        val label: String,
    )

    private const val RES_XML_TYPE = 0x0003
    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val RES_XML_START_ELEMENT_TYPE = 0x0102
    private const val UTF8_FLAG = 0x100
    private const val TYPE_STRING = 0x03
    private const val TYPE_INT_DEC = 0x10
    private const val TYPE_INT_HEX = 0x11
    private const val NO_INDEX = -1

    fun patch(manifest: ByteArray, patch: ManifestPatch): ByteArray {
        val fileType = shortAt(manifest, 0)
        require(fileType == RES_XML_TYPE) { "not a binary XML document (type=$fileType)" }
        val fileHeaderSize = shortAt(manifest, 2) // 文件头：type(2)+headerSize(2)+size(4)
        val (pool, poolAt, poolSize) = StringPool.read(manifest, fileHeaderSize)

        // 定位两个目标元素的属性改写点（坐标在原始字节数组上）
        val edits = mutableListOf<Pair<Int, ByteArray>>()
        var off = fileHeaderSize + poolSize
        while (off < manifest.size) {
            val chunkType = shortAt(manifest, off)
            val chunkSize = intAt(manifest, off + 4)
            if (chunkType == RES_XML_START_ELEMENT_TYPE) {
                val nameIdx = intAt(manifest, off + 20) // nodeHeader(16) 后第 2 个 u32：元素名
                when (pool.string(nameIdx)) {
                    "manifest" -> collectEdits(
                        manifest, off, pool, edits,
                        want = mapOf(
                            "package" to pool.indexOfOrAppend(patch.packageName),
                            "versionName" to pool.indexOfOrAppend(patch.versionName),
                            "versionCode" to pool.indexOfOrAppend(patch.versionCode.toString()),
                        ),
                        intValues = mapOf("versionCode" to patch.versionCode),
                    )
                    "application" -> collectEdits(
                        manifest, off, pool, edits,
                        want = mapOf("label" to pool.indexOfOrAppend(patch.label)),
                        intValues = emptyMap(),
                    )
                }
            }
            if (chunkSize <= 0) break
            off += chunkSize
        }

        // 池后内容拷出并打补丁（坐标换算：原坐标 - 池尾）
        val tailStart = fileHeaderSize + poolSize
        val tail = manifest.copyOfRange(tailStart, manifest.size)
        for ((at, bytes) in edits) {
            bytes.copyInto(tail, at - tailStart)
        }

        val poolBytes = StringPool.encode(pool.strings, pool.utf8)
        val out = ByteArrayOutputStream(manifest.size + 256)
        val header = manifest.copyOf(fileHeaderSize)
        putInt(header, 4, fileHeaderSize + poolBytes.size + tail.size)
        out.write(header)
        out.write(poolBytes)
        out.write(tail)
        return out.toByteArray()
    }

    // ---- 属性定位与改写 ----

    /**
     * START_ELEMENT 布局：nodeHeader(16) + ns(4) + name(4) + attributeStart(2) +
     * attributeSize(2) + attributeCount(2) + id/class/style(6) + attribute 数组。
     * attribute：ns(4)+name(4)+rawValue(4)+size(2)+res0(1)+dataType(1)+data(4) = 20。
     */
    private fun collectEdits(
        data: ByteArray,
        chunkAt: Int,
        pool: StringPool,
        edits: MutableList<Pair<Int, ByteArray>>,
        want: Map<String, Int>,
        intValues: Map<String, Int>,
    ) {
        val attributeStart = shortAt(data, chunkAt + 24)
        val attributeSize = shortAt(data, chunkAt + 26)
        val attributeCount = shortAt(data, chunkAt + 28)
        require(attributeSize >= 20) { "unexpected attribute size: $attributeSize" }
        val matched = mutableSetOf<String>()
        for (i in 0 until attributeCount) {
            val attrAt = chunkAt + 16 + attributeStart + i * attributeSize
            val attrName = pool.string(intAt(data, attrAt + 4))
            val newStringIdx = want[attrName] ?: continue
            matched += attrName
            val dataType = data[attrAt + 15].toInt() and 0xFF
            // rawValue → 新字符串索引（解析器取 raw 或 typed 均一致）
            edits += (attrAt + 8) to intBytes(newStringIdx)
            when {
                // 整型属性（versionCode）：data 直接写 int 数值，类型不动
                attrName in intValues && (dataType == TYPE_INT_DEC || dataType == TYPE_INT_HEX) ->
                    edits += (attrAt + 16) to intBytes(intValues.getValue(attrName))
                // 字符串字面量属性：typed data → 新字符串索引
                dataType == TYPE_STRING ->
                    edits += (attrAt + 16) to intBytes(newStringIdx)
                // 引用型（如 label=@string/...）→ 改成字符串字面量（typedValue: size=8, dataType=STRING）
                else -> {
                    edits += (attrAt + 12) to shortBytes(8)
                    edits += (attrAt + 15) to byteArrayOf(TYPE_STRING.toByte())
                    edits += (attrAt + 16) to intBytes(newStringIdx)
                }
            }
        }
        val missing = want.keys - matched
        if (missing.isNotEmpty()) {
            throw IllegalStateException("manifest 目标属性缺失: $missing（模板底版与补丁器不匹配）")
        }
    }

    // ---- 字符串池 ----

    internal class StringPool private constructor(
        val strings: MutableList<String>,
        val utf8: Boolean,
    ) {

        fun string(index: Int): String = strings.getOrNull(index) ?: "<idx:$index>"

        /** 已存在则复用（补丁幂等）；否则追加到池尾（既有索引不变）。 */
        fun indexOfOrAppend(s: String): Int =
            strings.indexOf(s).takeIf { it >= 0 } ?: run {
                strings.add(s)
                strings.lastIndex
            }

        companion object {
            fun read(data: ByteArray, poolAt: Int): Triple<StringPool, Int, Int> {
                val type = shortAt(data, poolAt)
                require(type == RES_STRING_POOL_TYPE) { "first chunk is not a string pool (type=$type)" }
                val headerSize = shortAt(data, poolAt + 2)
                require(headerSize == 28) { "unexpected pool header size: $headerSize" }
                val chunkSize = intAt(data, poolAt + 4)
                val stringCount = intAt(data, poolAt + 8)
                val styleCount = intAt(data, poolAt + 12)
                if (styleCount != 0) {
                    throw UnsupportedPoolException("字符串池含 style 表（styleCount=$styleCount）：模板 manifest 不应包含，拒绝静默损坏")
                }
                val flags = intAt(data, poolAt + 16)
                val stringsStart = intAt(data, poolAt + 20)
                val utf8 = flags and UTF8_FLAG != 0
                val offsets = IntArray(stringCount) { intAt(data, poolAt + 28 + it * 4) }
                val dataStart = poolAt + stringsStart
                val strings = offsets.map { o -> decodeString(data, dataStart + o, utf8) }.toMutableList()
                return Triple(StringPool(strings, utf8), poolAt, chunkSize)
            }

            private fun decodeString(data: ByteArray, at: Int, utf8: Boolean): String {
                if (!utf8) {
                    var len = shortAt(data, at)
                    var pos = at + 2
                    if (len >= 0x8000) { // 高位标记：后随 u32 长度
                        len = intAt(data, pos)
                        pos += 4
                    }
                    return String(data, pos, len * 2, Charsets.UTF_16LE)
                }
                var pos = at
                var u16len = data[pos].toInt() and 0xFF
                pos++
                if (u16len >= 0x80) {
                    u16len = ((u16len and 0x7F) shl 8) or (data[pos].toInt() and 0xFF)
                    pos++
                }
                var len8 = data[pos].toInt() and 0xFF
                pos++
                if (len8 >= 0x80) {
                    len8 = ((len8 and 0x7F) shl 8) or (data[pos].toInt() and 0xFF)
                    pos++
                }
                return String(data, pos, len8, Charsets.UTF_8)
            }

            /** 重编码整池（保持原编码格式），尺寸 4 字节对齐。 */
            fun encode(strings: List<String>, utf8: Boolean): ByteArray {
                val offsets = IntArray(strings.size)
                val body = ByteArrayOutputStream()
                for ((i, s) in strings.withIndex()) {
                    offsets[i] = body.size()
                    if (utf8) {
                        val bytes = s.toByteArray(Charsets.UTF_8)
                        writeLen8(body, s.length)
                        writeLen8(body, bytes.size)
                        body.write(bytes)
                        body.write(0)
                    } else {
                        val chars = s.toCharArray()
                        // M6 债务收敛：UTF-16 len≥0x8000 走高位标记转义（与 decodeString 对称），
                        // 否则长串（如超长 label）会被当短长度解析，重编码池静默损坏
                        writeU16Len(body, chars.size)
                        for (c in chars) writeU16(body, c.code)
                        body.write(0)
                        body.write(0)
                    }
                }
                while (body.size() % 4 != 0) body.write(0)
                val stringsStart = 28 + offsets.size * 4
                val size = stringsStart + body.size()
                val out = ByteArrayOutputStream()
                writeU16(out, RES_STRING_POOL_TYPE)
                writeU16(out, 28) // headerSize
                writeInt(out, size)
                writeInt(out, strings.size)
                writeInt(out, 0) // styleCount
                writeInt(out, if (utf8) UTF8_FLAG else 0)
                writeInt(out, stringsStart)
                writeInt(out, 0) // stylesStart
                for (o in offsets) writeInt(out, o)
                body.writeTo(out)
                return out.toByteArray()
            }

            private fun writeU16(out: ByteArrayOutputStream, v: Int) {
                out.write(v and 0xFF)
                out.write((v shr 8) and 0xFF)
            }

            /** UTF-16 字符串长度：≥0x8000 时写高位标记 + u32 实长（aapt 转义约定）。 */
            private fun writeU16Len(out: ByteArrayOutputStream, len: Int) {
                if (len >= 0x8000) {
                    writeU16(out, 0x8000)
                    writeInt(out, len)
                } else {
                    writeU16(out, len)
                }
            }

            private fun writeInt(out: ByteArrayOutputStream, v: Int) {
                out.write(v and 0xFF)
                out.write((v shr 8) and 0xFF)
                out.write((v shr 16) and 0xFF)
                out.write((v shr 24) and 0xFF)
            }

            private fun writeLen8(out: ByteArrayOutputStream, len: Int) {
                if (len < 0x80) {
                    out.write(len)
                } else {
                    out.write(0x80 or (len shr 8))
                    out.write(len and 0xFF)
                }
            }
        }
    }

    // ---- 字节读写字节原语 ----

    private fun intBytes(v: Int): ByteArray =
        byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte())

    private fun shortBytes(v: Int): ByteArray = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

    private fun shortAt(data: ByteArray, at: Int): Int =
        (data[at].toInt() and 0xFF) or ((data[at + 1].toInt() and 0xFF) shl 8)

    private fun intAt(data: ByteArray, at: Int): Int =
        (data[at].toInt() and 0xFF) or ((data[at + 1].toInt() and 0xFF) shl 8) or
            ((data[at + 2].toInt() and 0xFF) shl 16) or ((data[at + 3].toInt() and 0xFF) shl 24)

    private fun putInt(data: ByteArray, at: Int, v: Int) {
        data[at] = (v and 0xFF).toByte()
        data[at + 1] = ((v shr 8) and 0xFF).toByte()
        data[at + 2] = ((v shr 16) and 0xFF).toByte()
        data[at + 3] = ((v shr 24) and 0xFF).toByte()
    }
}
