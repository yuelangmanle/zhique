package com.zhique.core.export

/**
 * AXML 清单读取器（导出记录核对与测试用；与 [AxmlPatcher] 同一套二进制格式）。
 */
object AxmlReader {

    data class ManifestInfo(
        val packageName: String?,
        val versionCode: Long?,
        val versionName: String?,
        val label: String?,
        /** application@icon 的资源完整 ID（REFERENCE typed value；无 icon 属性为 null）。 */
        val iconResId: Long? = null,
    )

    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val RES_XML_START_ELEMENT_TYPE = 0x0102
    private const val TYPE_STRING = 0x03
    private const val TYPE_INT_DEC = 0x10
    private const val TYPE_INT_HEX = 0x11
    private const val TYPE_REFERENCE = 0x01

    fun readManifest(manifest: ByteArray): ManifestInfo {
        val fileHeaderSize = shortAt(manifest, 2) // 文件头：type(2)+headerSize(2)+size(4)
        val (pool, _, poolSize) = AxmlPatcher.StringPool.read(manifest, fileHeaderSize)

        var off = fileHeaderSize + poolSize
        var pkg: String? = null
        var code: Long? = null
        var vName: String? = null
        var label: String? = null
        var icon: Long? = null
        while (off < manifest.size) {
            val chunkType = shortAt(manifest, off)
            val chunkSize = intAt(manifest, off + 4)
            if (chunkType == RES_XML_START_ELEMENT_TYPE) {
                val element = pool.string(intAt(manifest, off + 20))
                val attributeStart = shortAt(manifest, off + 24)
                val attributeSize = shortAt(manifest, off + 26)
                val attributeCount = shortAt(manifest, off + 28)
                for (i in 0 until attributeCount) {
                    val attrAt = off + 16 + attributeStart + i * attributeSize
                    val attrName = pool.string(intAt(manifest, attrAt + 4))
                    val rawIdx = intAt(manifest, attrAt + 8)
                    val dataType = manifest[attrAt + 15].toInt() and 0xFF
                    val dataVal = intAt(manifest, attrAt + 16)
                    val value: Any? = when (dataType) {
                        TYPE_STRING -> pool.string(if (rawIdx != -1) rawIdx else dataVal)
                        TYPE_INT_DEC, TYPE_INT_HEX -> dataVal.toLong()
                        else -> null
                    }
                    when (element to attrName) {
                        "manifest" to "package" -> pkg = value as? String
                        "manifest" to "versionCode" -> code = value as? Long
                        "manifest" to "versionName" -> vName = value as? String
                        "application" to "label" -> label = value as? String
                        "application" to "icon" ->
                            if (dataType == TYPE_REFERENCE) icon = dataVal.toLong()
                    }
                }
            }
            if (chunkSize <= 0) break
            off += chunkSize
        }
        return ManifestInfo(pkg, code, vName, label, icon)
    }

    /** 解码字符串池（测试核对用）。返回 (字符串清单, 是否 UTF-8 编码)。 */
    fun readPool(data: ByteArray, poolAt: Int): Pair<List<String>, Boolean> {
        val (pool, _, _) = AxmlPatcher.StringPool.read(data, poolAt)
        return pool.strings.toList() to pool.utf8
    }

    private fun shortAt(data: ByteArray, at: Int): Int =
        (data[at].toInt() and 0xFF) or ((data[at + 1].toInt() and 0xFF) shl 8)

    private fun intAt(data: ByteArray, at: Int): Int =
        (data[at].toInt() and 0xFF) or ((data[at + 1].toInt() and 0xFF) shl 8) or
            ((data[at + 2].toInt() and 0xFF) shl 16) or ((data[at + 3].toInt() and 0xFF) shl 24)
}
