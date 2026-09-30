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

    // 快照编号/索引写与审计追加共用一把锁，防并发竞态
    private val lock = Any()

    fun append(projectId: String, label: String, content: String): Snapshot {
        synchronized(lock) {
            val historyDir = historyDir(projectId)
            historyDir.mkdirs()
            val n = (historyDir.listFiles()?.count { it.name.startsWith("snap-") } ?: 0) + 1
            val file = "snap-$n-${sanitizeLabel(label)}.html"
            File(historyDir, file).writeText(content)
            val snap = Snapshot(
                id = file.removeSuffix(".html"),
                label = label,
                file = file,
                at = System.currentTimeMillis(),
            )
            historyDir.toPath().resolve(INDEX)
                .writeStringAtomic(json.encodeToString(ListSerializer(Snapshot.serializer()), list(projectId) + snap))
            return snap
        }
    }

    fun list(projectId: String): List<Snapshot> {
        val f = File(historyDir(projectId), INDEX)
        if (!f.isFile) return emptyList()
        return json.decodeFromString(ListSerializer(Snapshot.serializer()), f.readText())
    }

    /** 把指定快照内容写回项目 index.html，返回快照内容。index.json 不可信，路径必须落在 history 内。 */
    fun restore(projectId: String, snapshotId: String): String {
        val snap = list(projectId).firstOrNull { it.id == snapshotId }
            ?: throw IllegalArgumentException("snapshot not found: $snapshotId")
        val historyDir = historyDir(projectId)
        val f = File(historyDir, snap.file)
        if (!f.canonicalPath.startsWith(historyDir.canonicalPath + File.separator)) {
            throw IllegalStateException("snapshot file escapes history dir: ${snap.file}")
        }
        val content = f.readText()
        // 原子写回项目 index.html（防写一半崩溃留下截断的项目入口）
        File(projectDir(projectId), "index.html").toPath().writeStringAtomic(content)
        return content
    }

    fun appendAudit(projectId: String, entry: AuditEntry) {
        synchronized(lock) {
            val historyDir = historyDir(projectId)
            historyDir.mkdirs()
            File(historyDir, AUDIT).appendText(
                json.encodeToString(AuditEntry.serializer(), entry) + "\n",
            )
        }
    }

    /** 按追加顺序分页读取审计流水；越界页返回空。读侧与写共用锁，避免读到半行。 */
    fun readAudit(projectId: String, page: Int = 0, pageSize: Int = 20): List<AuditEntry> {
        require(page >= 0 && pageSize > 0) { "invalid page/pageSize" }
        val lines = synchronized(lock) {
            val f = File(historyDir(projectId), AUDIT)
            if (!f.isFile) return emptyList()
            f.readLines().filter { it.isNotBlank() }
        }
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

        private val ALLOWED: (Char) -> Boolean = {
            it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' ||
                it == '_' || it == '-' || it == ' ' || it in '\u4e00'..'\u9fa5'
        }

        /** 白名单消毒：合法字符保留，其余替换 _，截断 64 字符，防空。 */
        fun sanitizeLabel(label: String): String {
            val cleaned = label.filter(ALLOWED).take(64)
            return cleaned.ifEmpty { "snap" }
        }
    }
}
