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

    /** 唯一 HistoryStore 实例：单实例约定——消费方一律从仓库取用，禁止自行构造（避免多实例锁失效竞态）。 */
    val history: HistoryStore = HistoryStore(root)
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    init {
        projectsDir.mkdirs()
    }

    /** 最近一次 [list] 发现的损坏项目（project.json 不可解析/缺失）目录名。 */
    var corruptedProjects: List<String> = emptyList()
        private set

    fun create(name: String, html: String): ProjectMeta {
        val meta = ProjectMeta(id = UUID.randomUUID().toString(), name = name)
        val dir = dir(meta.id)
        dir.mkdirs()
        File(dir, "index.html").writeText(html)
        save(meta)
        return meta
    }

    fun list(): List<ProjectMeta> {
        val corrupted = mutableListOf<String>()
        val metas = (projectsDir.listFiles()?.filter { it.isDirectory } ?: emptyList())
            .mapNotNull { dir ->
                runCatching { readMeta(dir) }
                    .onFailure {
                        corrupted += dir.name
                        System.err.println("[zhique] 损坏的 project.json，已跳过: ${dir.name}")
                    }
                    .getOrNull()
            }
            .sortedBy { it.createdAt }
        corruptedProjects = corrupted
        return metas
    }

    fun meta(id: String): ProjectMeta = readMeta(dir(id))

    fun rename(id: String, name: String): ProjectMeta = mutate(id) { it.name = name }

    fun moveGroup(id: String, group: String): ProjectMeta = mutate(id) { it.group = group }

    /** 运行器模式写回（drawer | split | bubble），选中即持久化。 */
    fun setRunnerMode(id: String, mode: String): ProjectMeta = mutate(id) { it.runnerMode = mode }

    /** 权限矩阵写回（M5 权限桥：state ∈ NOT_ASKED/ASKING/GRANTED/DENIED）。 */
    fun setPermission(
        id: String,
        capability: String,
        state: String,
        lastAsked: Long = System.currentTimeMillis(),
    ): ProjectMeta = mutate(id) {
        it.permissions[capability] = PermissionRecord(capability, state, lastAsked)
    }

    /** 运行期真实使用计数 +1（M5：导出最小权限建议的依据，规格 §4.6）。 */
    fun bumpPermissionUsage(id: String, capability: String): ProjectMeta = mutate(id) {
        it.permissionUsage[capability] = (it.permissionUsage[capability] ?: 0) + 1
    }

    /** 导出记录写回（M6：含证书 SHA-256，决策29 覆盖安装保证的锚点）。 */
    fun recordExport(id: String, record: ExportRecord): ProjectMeta = mutate(id) { it.export = record }

    /** 元数据序列化（M6 导出：注入 APK assets/project/project.json 的品牌信息源）。 */
    fun metaJson(id: String): ByteArray = json.encodeToString(ProjectMeta.serializer(), meta(id)).toByteArray()

    /** 项目目录（只读视图用途：Agent 文件遍历/编辑器文件 Tab；写路径仍走 writeFile 沙箱）。 */
    fun projectDir(id: String): File = dir(id)

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

    /** 追加一条项目快照（history 非空即删除需显式 confirm）。委托给唯一实例 [history]。 */
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
        dir.toPath().resolve(META_FILE)
            .writeStringAtomic(json.encodeToString(ProjectMeta.serializer(), meta))
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
