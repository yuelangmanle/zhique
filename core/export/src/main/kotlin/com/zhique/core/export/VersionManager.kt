package com.zhique.core.export

import com.zhique.core.project.ProjectMeta
import com.zhique.core.project.ProjectRepository

/**
 * 版本与包名（M6 Task 6.2）：
 *
 * - 版本自增：`nextVersionCode = (meta.export?.versionCode ?: 0) + 1`（1,2,3…），
 *   同项目同包名同签名 + 版本自增 → 每次更新可覆盖安装、数据保留（决策29-1）；
 * - 包名：`com.zhique.export.<slug(应用名)>`。slug 只留 `[a-z0-9_]，段首必须是字母`
 *   （**Android 包名不允连字符**——真机"解析包失败"根因：旧实现把非法字符折成 '-'
 *   且冲突消解也拼 '-'，产出 INSTALL_PARSE_FAILED_BAD_PACKAGE_NAME）；
 *   CJK/空应用名回退 `app`；
 * - 已导出过的项目**沿用既有包名**（重命名不改包名——覆盖安装的前提）；
 * - 跨项目冲突以 `_<项目 id 前 8 位>` 消解（同项目稳定、不同项目必然不同 → 共存）。
 */
class VersionManager(private val repo: ProjectRepository) {

    /** 下一个 versionCode（首导出 = 1，自增单调）。 */
    fun nextVersionCode(meta: ProjectMeta): Int = (meta.export?.versionCode ?: 0) + 1

    /** 下一个 versionName（与 versionCode 同源的展示串）。 */
    fun versionName(versionCode: Int): String = "1.0.$versionCode"

    /**
     * slug 规则：小写、非 [a-z0-9] 折叠为 '_'、去首尾 '_'、上限 [MAX_SLUG_LEN]。
     * 包名段必须以字母开头（纯数字/下划线开头补 'p' 前缀）。
     */
    fun slug(name: String): String {
        val folded = name.lowercase()
            .map { if (it in 'a'..'z' || it in '0'..'9') it else '_' }
            .joinToString("")
            .replace(Regex("_+"), "_")
            .trim('_')
            .take(MAX_SLUG_LEN)
            .trimEnd('_')
        val base = folded.ifEmpty { return FALLBACK_SLUG }
        return if (base[0] in 'a'..'z') base else "p$base"
    }

    /**
     * 项目导出包名：
     * 1. 已导出过且既有包名**合法** → 沿用（决策29：覆盖安装的锚点，重命名不改包名）；
     *    既有包名非法（历史 bug 产物，如含 '-'）→ 弃用重生成；
     * 2. `$PREFIX.<slug>` 未被其他项目占用 → 即用；
     * 3. 冲突 → `$PREFIX<slug>_<id 前 8 位>`（下划线：合法包名字符）。
     */
    fun packageName(meta: ProjectMeta): String {
        meta.export?.packageName
            ?.takeIf { it.isNotBlank() && isValidPackageName(it) }
            ?.let { return it }
        val base = slug(meta.name)
        val takenByOthers = repo.list()
            .filter { it.id != meta.id }
            .map { it.export?.packageName ?: PREFIX + slug(it.name) }
            .toSet()
        val candidate = PREFIX + base
        val idSuffix = meta.id.filter { it.isLetterOrDigit() }.take(8).ifEmpty { "x" }
        return if (candidate in takenByOthers) "$PREFIX${base}_$idSuffix" else candidate
    }

    /** Android 包名合法性（每段 [a-z][a-z0-9_]*，点分段）。 */
    fun isValidPackageName(pkg: String): Boolean =
        pkg.split('.').all { seg ->
            seg.isNotEmpty() && seg[0] in 'a'..'z' &&
                seg.all { it in 'a'..'z' || it in '0'..'9' || it == '_' }
        }

    companion object {
        const val PREFIX = "com.zhique.export."
        const val MAX_SLUG_LEN = 24
        const val FALLBACK_SLUG = "app"
    }
}
