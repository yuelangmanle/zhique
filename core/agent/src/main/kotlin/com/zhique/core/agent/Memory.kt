package com.zhique.core.agent

import com.zhique.core.project.ProjectRepository
import java.io.File

/**
 * 三层记忆之全局层 + 项目层（规格 §4.5，决策 21）：
 * - 全局记忆：跨项目用户偏好与约定（应用数据区 global-memory.md）；
 * - 项目记忆 zhique.md：会话自动携带，会话结束 [writeBack] 把新约定去重写回。
 */
class Memory(
    private val globalFile: File,
    private val repo: ProjectRepository,
) : AgentMemory {

    private val lock = Any()
    private val pendingNotes = LinkedHashSet<String>()

    fun globalText(): String = if (globalFile.isFile) globalFile.readText() else ""

    fun setGlobal(text: String) {
        globalFile.parentFile?.mkdirs()
        globalFile.writeText(text)
    }

    /** 项目记忆全文（缺失为空串）。 */
    fun projectText(projectId: String): String =
        runCatching { repo.readFile(projectId, MEMORY_FILE) }.getOrDefault("")

    /** 会话中学到的新约定（编排器/对话层随时记，结束时统一写回）。 */
    fun addSessionNote(note: String) {
        val n = note.trim()
        if (n.isNotEmpty()) synchronized(lock) { pendingNotes += n }
    }

    fun pendingNotes(): Set<String> = synchronized(lock) { pendingNotes.toSet() }

    /** 会话结束写回：pending 追加到 zhique.md（与既有行去重），**写盘成功后**才清 pending。 */
    override suspend fun writeBack(projectId: String) {
        val notes = synchronized(lock) { pendingNotes.toList() }
        if (notes.isEmpty()) return
        val existing = projectText(projectId)
        val existingLines = existing.lines().map { it.trim().removePrefix("- ").trim() }.toSet()
        val addition = notes.filter { it !in existingLines }
        if (addition.isEmpty()) {
            // 全部已存在：pending 也要清（removeAll(空集) 是 no-op，内存会无限累积）
            synchronized(lock) { pendingNotes.removeAll(notes.toSet()) }
            return
        }
        val merged = buildString {
            append(existing)
            if (existing.isNotBlank() && !existing.endsWith("\n")) append('\n')
            addition.forEach { append("- ").append(it).append('\n') }
        }
        // 先写盘后清 pending：写失败（IO 异常）时本会话新约定不丢，下次重试
        repo.writeFile(projectId, MEMORY_FILE, merged)
        synchronized(lock) { pendingNotes.removeAll(addition.toSet()) }
    }

    companion object {
        const val MEMORY_FILE = "zhique.md"
    }
}
