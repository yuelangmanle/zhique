package com.zhique.core.project

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** 项目版本快照条目（history/index.json）。 */
@Serializable
data class Snapshot(val id: String, val label: String, val file: String, val at: Long)

/** Agent 审计条目（history/audit.jsonl 逐行 JSON）。 */
@Serializable
data class AuditEntry(
    val at: Long = System.currentTimeMillis(),
    val action: String,
    val detail: String = "",
)

/**
 * 版本快照 + 审计日志：`projects/<projectId>/history/` 下
 * `snap-<n>-<label>.html` 快照文件、`index.json` 快照索引、`audit.jsonl` 审计流水。
 */
class HistoryStore(private val root: File) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun append(projectId: String, label: String, content: String): Snapshot {
        val historyDir = historyDir(projectId)
        historyDir.mkdirs()
        val n = (historyDir.listFiles()?.count { it.name.startsWith("snap-") } ?: 0) + 1
        val file = "snap-$n-$label.html"
        File(historyDir, file).writeText(content)
        val snap = Snapshot(
            id = file.removeSuffix(".html"),
            label = label,
            file = file,
            at = System.currentTimeMillis(),
        )
        val index = list(projectId) + snap
        File(historyDir, INDEX).writeText(
            json.encodeToString(ListSerializer(Snapshot.serializer()), index),
        )
        return snap
    }

    fun list(projectId: String): List<Snapshot> {
        val f = File(historyDir(projectId), INDEX)
        if (!f.isFile) return emptyList()
        return json.decodeFromString(ListSerializer(Snapshot.serializer()), f.readText())
    }

    /** 把指定快照内容写回项目 index.html，返回快照内容。 */
    fun restore(projectId: String, snapshotId: String): String {
        val snap = list(projectId).firstOrNull { it.id == snapshotId }
            ?: throw IllegalArgumentException("snapshot not found: $snapshotId")
        val content = File(historyDir(projectId), snap.file).readText()
        File(projectDir(projectId), "index.html").writeText(content)
        return content
    }

    fun appendAudit(projectId: String, entry: AuditEntry) {
        val historyDir = historyDir(projectId)
        historyDir.mkdirs()
        File(historyDir, AUDIT).appendText(
            json.encodeToString(AuditEntry.serializer(), entry) + "\n",
        )
    }

    /** 按追加顺序分页读取审计流水；越界页返回空。 */
    fun readAudit(projectId: String, page: Int = 0, pageSize: Int = 20): List<AuditEntry> {
        require(page >= 0 && pageSize > 0) { "invalid page/pageSize" }
        val f = File(historyDir(projectId), AUDIT)
        if (!f.isFile) return emptyList()
        val lines = f.readLines().filter { it.isNotBlank() }
        val from = page * pageSize
        if (from >= lines.size) return emptyList()
        return lines.subList(from, minOf(from + pageSize, lines.size))
            .map { json.decodeFromString(AuditEntry.serializer(), it) }
    }

    private fun projectDir(projectId: String) = File(root, "projects/$projectId")

    private fun historyDir(projectId: String) = File(projectDir(projectId), "history")

    private companion object {
        const val INDEX = "index.json"
        const val AUDIT = "audit.jsonl"
    }
}
