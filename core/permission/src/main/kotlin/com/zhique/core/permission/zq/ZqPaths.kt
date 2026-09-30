package com.zhique.core.permission.zq

import java.io.File

/** 项目沙盒路径解析（zq.file 的边界守卫；纯逻辑可 JVM 测试）。 */
object ZqPaths {

    /**
     * 相对路径 → 项目沙盒内的文件。越界一律拒绝：
     * 绝对路径、空串、`../` 逃逸（canonicalPath 前缀校验，与 ProjectRepository 同规则）。
     */
    fun resolveInSandbox(base: File, rel: String): File {
        require(rel.isNotBlank()) { "路径不能为空" }
        require(!File(rel).isAbsolute) { "只允许项目内相对路径: $rel" }
        val f = File(base, rel)
        require(f.canonicalPath.startsWith(base.canonicalPath + File.separator)) {
            "路径越出项目沙盒: $rel"
        }
        return f
    }
}

/** 内联回传（dataURL / readText）的大小上限（质量审查 Minor #7）。 */
object ZqLimits {
    const val MAX_INLINE_BYTES: Long = 8L * 1024 * 1024
}

/**
 * 流式订阅登记表（zq.location.watch / zq.sensor.watch 的 sub 句柄）。
 * 纯逻辑：native 侧每个事件推送前复查 [isActive]，吊销/取消即停流。
 */
class Subscriptions {

    private val lock = Any()
    private var seq = 0L
    private val active = HashSet<String>()

    fun new(): String = synchronized(lock) {
        "sub-${++seq}".also { active += it }
    }

    /** 取消订阅；重复取消返回 false。 */
    fun cancel(id: String): Boolean = synchronized(lock) { active.remove(id) }

    fun isActive(id: String): Boolean = synchronized(lock) { id in active }

    fun count(): Int = synchronized(lock) { active.size }

    /** 全量关停（运行器销毁时）：返回取消的句柄数。 */
    fun cancelAll(): Int = synchronized(lock) {
        val n = active.size
        active.clear()
        n
    }
}
