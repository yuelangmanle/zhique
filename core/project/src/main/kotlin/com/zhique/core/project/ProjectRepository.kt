package com.zhique.core.project

import java.io.File
import java.util.UUID
import kotlinx.serialization.json.Json

/**
 * 项目仓库：数据根目录下 `projects/<projectId>/`，含 index.html、任意资源、
 * project.json 与 history/（规格 §3.5）。
 */
class ProjectRepository(private val root: File) {

    private val projectsDir = File(root, "projects")
    private val history = HistoryStore(root)
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    init {
        projectsDir.mkdirs()
    }

    fun create(name: String, html: String): ProjectMeta {
        val meta = ProjectMeta(id = UUID.randomUUID().toString(), name = name)
        val dir = dir(meta.id)
        dir.mkdirs()
        File(dir, "index.html").writeText(html)
        save(meta)
        return meta
    }

    fun list(): List<ProjectMeta> =
        (projectsDir.listFiles()?.filter { it.isDirectory } ?: emptyList())
            .mapNotNull { runCatching { readMeta(it) }.getOrNull() }
            .sortedBy { it.createdAt }

    fun meta(id: String): ProjectMeta = readMeta(dir(id))

    fun rename(id: String, name: String): ProjectMeta = mutate(id) { it.name = name }

    fun moveGroup(id: String, group: String): ProjectMeta = mutate(id) { it.group = group }

    fun writeFile(id: String, path: String, content: String) {
        val f = resolveIn(dir(id), path)
        f.parentFile?.mkdirs()
        f.writeText(content)
    }

    fun readFile(id: String, path: String): String = resolveIn(dir(id), path).readText()

    /** 整项目复制：深拷项目文件，id 重生成、name 加「副本」；history 不随复制。 */
    fun copy(id: String): ProjectMeta {
        val srcMeta = meta(id)
        val newMeta = srcMeta.copy(
            id = UUID.randomUUID().toString(),
            name = "${srcMeta.name} 副本",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
        val dst = dir(newMeta.id)
        dst.mkdirs()
        copyDir(dir(id), dst, skip = setOf(HISTORY_DIR, "project.json"))
        save(newMeta)
        return newMeta
    }

    fun delete(id: String, confirm: Boolean = false) {
        val dir = dir(id)
        require(dir.exists()) { "project not found: $id" }
        if (hasHistory(id) && !confirm) {
            throw IllegalStateException("project $id has history; pass confirm=true to delete")
        }
        dir.deleteRecursively()
    }

    /** 导出 zip：project.json（meta）+ 全部项目文件（不含 history/）。 */
    fun exportZip(id: String): File {
        val dir = dir(id)
        val entries = buildMap {
            put("project.json", readMeta(dir).let { json.encodeToString(ProjectMeta.serializer(), it) }.toByteArray())
            collectFiles(dir, dir, skip = setOf(HISTORY_DIR)).forEach { (rel, f) ->
                put(rel, f.readBytes())
            }
        }
        val out = File.createTempFile("zhique-", ".zip")
        ZipIO.write(out, entries)
        return out
    }

    /** 导入 zip：解包到新 id 目录并剥离旧 id（重生成 id 后落盘）。 */
    fun importZip(zip: File): ProjectMeta {
        val entries = ZipIO.read(zip)
        val raw = entries[META_FILE] ?: throw IllegalArgumentException("zip missing $META_FILE")
        val imported = json.decodeFromString(ProjectMeta.serializer(), raw.decodeToString())
        val meta = imported.copy(
            id = UUID.randomUUID().toString(),
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
        val dst = dir(meta.id)
        dst.mkdirs()
        for ((name, bytes) in entries) {
            if (name == META_FILE) continue
            val f = resolveIn(dst, name)
            f.parentFile?.mkdirs()
            f.writeBytes(bytes)
        }
        save(meta)
        return meta
    }

    /** 追加一条项目快照（history 非空即删除需显式 confirm）。 */
    fun appendHistory(id: String, label: String, content: String) {
        history.append(id, label, content)
    }

    fun hasHistory(id: String): Boolean {
        val historyDir = File(dir(id), HISTORY_DIR)
        return historyDir.exists() && (historyDir.listFiles()?.any { it.isFile } ?: false)
    }

    // ---- internals ----

    private fun dir(id: String): File {
        require(id.isNotBlank() && !id.contains('/') && !id.contains('\\') && id != ".." && id != ".") {
            "invalid project id: $id"
        }
        return File(projectsDir, id)
    }

    private fun readMeta(dir: File): ProjectMeta {
        val f = File(dir, META_FILE)
        require(f.isFile) { "project not found: ${dir.name}" }
        return json.decodeFromString(ProjectMeta.serializer(), f.readText())
    }

    private fun save(meta: ProjectMeta) {
        meta.updatedAt = System.currentTimeMillis()
        val dir = dir(meta.id)
        dir.mkdirs()
        File(dir, META_FILE).writeText(json.encodeToString(ProjectMeta.serializer(), meta))
    }

    private fun mutate(id: String, block: (ProjectMeta) -> Unit): ProjectMeta {
        val meta = meta(id)
        block(meta)
        save(meta)
        return meta
    }

    private fun resolveIn(base: File, path: String): File {
        val f = File(base, path)
        require(f.canonicalPath.startsWith(base.canonicalPath + File.separator)) {
            "path escapes project sandbox: $path"
        }
        return f
    }

    private fun copyDir(src: File, dst: File, skip: Set<String>) {
        for (child in src.listFiles() ?: emptyArray()) {
            if (child.name in skip) continue
            val target = File(dst, child.name)
            if (child.isDirectory) {
                target.mkdirs()
                copyDir(child, target, skip)
            } else {
                child.copyTo(target, overwrite = true)
            }
        }
    }

    private fun collectFiles(base: File, cur: File, skip: Set<String>): List<Pair<String, File>> {
        val out = mutableListOf<Pair<String, File>>()
        for (child in cur.listFiles() ?: emptyArray()) {
            if (child.name in skip) continue
            if (child.isDirectory) {
                out += collectFiles(base, child, skip)
            } else {
                out += child.relativeTo(base).path to child
            }
        }
        return out
    }

    private companion object {
        const val META_FILE = "project.json"
        const val HISTORY_DIR = "history"
    }
}
