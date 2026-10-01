package com.zhique.core.export

/**
 * resources.arsc 最小读取器（M6 偏差③）：只做一件事——按 (类型, 条目名) 查
 * 资源完整 ID。导出管线用它从模板壳里取 `mipmap/icon_indigo` / `mipmap/icon_slate`
 * 的 ID，交给 [AxmlPatcher] 改写 `application@icon` 引用（resources.arsc 本身不动）。
 *
 * 只支持模板这类「单包、条目非 bag」的表（底版由我们构建，可控）；
 * 解析按 chunk 头推进，遇到不认识的 chunk 跳过（前向兼容），查不到明确抛错。
 */
object ResourceTableReader {

    /** arsc 里查不到目标条目。 */
    class EntryNotFoundException(type: String, name: String) :
        IllegalStateException("resources.arsc 中未找到条目: $type/$name（模板底版与读取器不匹配）")

    private const val RES_TABLE_TYPE = 0x0002
    private const val RES_PACKAGE_TYPE = 0x0200
    private const val RES_TABLE_TYPE_TYPE = 0x0201
    private const val ENTRY_FLAG_COMPLEX = 0x0001
    private const val NO_ENTRY = 0xFFFFFFFFL

    /**
     * 查条目 ID。[type] 为类型名（如 "mipmap"），[name] 为条目名（如 "icon_slate"）。
     * 多 config 只要有任一 config 定义即命中（同一 (类型,条目) 资源 ID 与 config 无关）。
     */
    fun entryId(arsc: ByteArray, type: String, name: String): Long {
        val tableType = u16(arsc, 0)
        require(tableType == RES_TABLE_TYPE) { "not a resource table (type=$tableType)" }
        val headerSize = u16(arsc, 2)
        val tableSize = u32(arsc, 4).toInt()
        val packageCount = u32(arsc, 8).toInt()

        // 表级 chunk 序列：全局值字符串池 → 各 package chunk（按 chunk 头推进，
        // 不能按 packageCount 逐个消费——首个 chunk 是全局字符串池）
        var off = headerSize
        var packages = 0
        while (off < tableSize && packages < packageCount) {
            val chunkType = u16(arsc, off)
            val chunkSize = u32(arsc, off + 4).toInt()
            if (chunkSize <= 0) break
            check(chunkSize <= tableSize - off) {
                "resources.arsc 表级 chunk 越界（type=0x%04x size=%d）".format(chunkType, chunkSize)
            }
            if (chunkType == RES_PACKAGE_TYPE) {
                findInPackage(arsc, off, chunkSize, type, name)?.let { return it }
                packages++
            }
            off += chunkSize
        }
        throw EntryNotFoundException(type, name)
    }

    private fun findInPackage(
        data: ByteArray,
        packageAt: Int,
        packageSize: Int,
        type: String,
        name: String,
    ): Long? {
        val packageId = u32(data, packageAt + 8)
        // ResTable_package 头 288 字节：typeStrings/keyStrings 为相对 chunk 起点的偏移
        val typeStringsAt = packageAt + u32(data, packageAt + 268).toInt()
        val keyStringsAt = packageAt + u32(data, packageAt + 276).toInt()
        val (typePool, _, _) = AxmlPatcher.StringPool.read(data, typeStringsAt)
        val (keyPool, _, keyPoolSize) = AxmlPatcher.StringPool.read(data, keyStringsAt)

        // keyStrings 池之后是 typeSpec(0x0202) / type(0x0201) chunk 序列
        var off = keyStringsAt + keyPoolSize
        val packageEnd = packageAt + packageSize
        while (off < packageEnd) {
            val chunkType = u16(data, off)
            val chunkSize = u32(data, off + 4).toInt()
            if (chunkSize <= 0) break
            check(chunkSize <= packageEnd - off) {
                "resources.arsc chunk 越界（type=0x%04x size=%d）".format(chunkType, chunkSize)
            }
            if (chunkType == RES_TABLE_TYPE_TYPE) {
                val typeId = data[off + 8].toInt() and 0xFF // 1-based 类型索引
                if (typePool.string(typeId - 1) == type) {
                    val entryCount = u32(data, off + 12)
                    val entriesStart = u32(data, off + 16)
                    val headerSize = u16(data, off + 2)
                    for (i in 0 until entryCount) {
                        val rel = u32(data, off + headerSize + 4 * i.toInt())
                        if (rel == NO_ENTRY) continue
                        val entryAt = (off + entriesStart + rel).toInt()
                        val flags = u16(data, entryAt + 2)
                        if (flags and ENTRY_FLAG_COMPLEX != 0) continue // bag 条目（图标不是）
                        val key = u32(data, entryAt + 4)
                        if (keyPool.string(key.toInt()) == name) {
                            return (packageId shl 24) or (typeId.toLong() shl 16) or i.toLong()
                        }
                    }
                }
            }
            off += chunkSize
        }
        return null
    }
}

private fun u16(data: ByteArray, at: Int): Int =
    (data[at].toInt() and 0xFF) or ((data[at + 1].toInt() and 0xFF) shl 8)

private fun u32(data: ByteArray, at: Int): Long =
    (data[at].toLong() and 0xFF) or ((data[at + 1].toLong() and 0xFF) shl 8) or
        ((data[at + 2].toLong() and 0xFF) shl 16) or ((data[at + 3].toLong() and 0xFF) shl 24)
