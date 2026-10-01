package com.zhique.core.apilot

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 桥接审计记录（规格 §4.9「桥接审计记录可清」）。
 *
 * 与 Apilot 客户端侧审计对齐：**不含密钥、不含 payload**——只记方向、时间、
 * 连接名与是否带 Key（布尔，不记 Key 本体）。用户可在设置里清除。
 */
@Serializable
data class AuditRecord(
    val time: Long,
    /** `read`（从 Apilot 接入）或 `write`（同步到 Apilot）。 */
    val direction: String,
    /** 连接名等摘要——调用方禁止把 Key/payload 传进来。 */
    val summary: String,
    val hasKey: Boolean,
)

/** 文件式 JSON-lines 审计仓；[record] 追加、[clear] 整体清除（删文件）。 */
class ApilotAuditStore(private val file: File) {

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    fun list(): List<AuditRecord> = synchronized(lock) {
        if (!file.isFile) return emptyList()
        runCatching {
            file.readLines().filter { it.isNotBlank() }
                .mapNotNull { line -> runCatching { json.decodeFromString(AuditRecord.serializer(), line) }.getOrNull() }
        }.getOrDefault(emptyList())
    }

    fun record(direction: String, summary: String, hasKey: Boolean) {
        synchronized(lock) {
            file.parentFile?.mkdirs()
            val line = json.encodeToString(
                AuditRecord.serializer(),
                AuditRecord(time = System.currentTimeMillis(), direction = direction, summary = summary, hasKey = hasKey),
            )
            file.appendText(line + "\n")
            trimToCap()
        }
    }

    /** 滚动裁剪：超过 [MAX_RECORDS] 条时丢弃最旧的（防审计文件无限增长）。 */
    private fun trimToCap() {
        val lines = file.readLines().filter { it.isNotBlank() }
        if (lines.size <= MAX_RECORDS) return
        file.writeText(lines.takeLast(MAX_RECORDS).joinToString(separator = "\n", postfix = "\n"))
    }

    /** 清除全部审计记录（删文件；幂等）。 */
    fun clear() {
        synchronized(lock) { file.delete() }
    }

    companion object {
        /** 审计上限：超出滚动丢弃最旧。 */
        const val MAX_RECORDS = 200
    }
}
