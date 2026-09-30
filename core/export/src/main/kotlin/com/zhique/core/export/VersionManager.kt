package com.zhique.core.export

import com.zhique.core.project.ProjectMeta
import com.zhique.core.project.ProjectRepository

/**
 * 版本与包名（M6 Task 6.2）：
 *
 * - 版本自增：`nextVersionCode = (meta.export?.versionCode ?: 0) + 1`（1,2,3…），
 *   同项目同包名同签名 + 版本自增 → 每次更新可覆盖安装、数据保留（决策29-1）；
 * - 包名：`com.zhique.export.<slug(应用名)>`；slug 只留 `[a-z0-9-]`，
 *   CJK/空应用名回退 `app`；跨项目冲突以项目 id 前 8 位消解——
 *   同一项目结果稳定（覆盖装包名不变），不同项目必然不同包名（共存）。
 */
class VersionManager(private val repo: ProjectRepository) {

    /** 下一个 versionCode（首导出 = 1，自增单调）。 */
    fun nextVersionCode(meta: ProjectMeta): Int = (meta.export?.versionCode ?: 0) + 1

    /** 下一个 versionName（与 versionCode 同源的展示串）。 */
    fun versionName(versionCode: Int): String = "1.0.$versionCode"

    /** slug 规则：小写、非 [a-z0-9] 折叠为 '-'、去首尾 '-'、上限 [MAX_SLUG_LEN]。 */
    fun slug(name: String): String {
        val folded = name.lowercase()
            .map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }
            .joinToString("")
            .replace(Regex("-+"), "-")
            .trim('-')
            .take(MAX_SLUG_LEN)
            .trimEnd('-')
        return folded.ifEmpty { FALLBACK_SLUG }
    }

    /**
     * 项目导出包名：`$PREFIX.<slug>`，冲突时追加 id 前 8 位。
     * 冲突判定涵盖：其他项目的既有导出包名，以及其他项目的同名 slug。
     */
    fun packageName(meta: ProjectMeta): String {
        val base = slug(meta.name)
        val takenByOthers = repo.list()
            .filter { it.id != meta.id }
            .map { it.export?.packageName ?: PREFIX + slug(it.name) }
            .toSet()
        val candidate = PREFIX + base
        return if (candidate in takenByOthers) "$PREFIX$base-${meta.id.take(8)}" else candidate
    }

    companion object {
        const val PREFIX = "com.zhique.export."
        const val MAX_SLUG_LEN = 24
        const val FALLBACK_SLUG = "app"
    }
}
