package com.zhique.core.ai

/** 用量持久化口（DataStore 落盘由宿主实现，测试用内存假实现）。 */
interface UsageStore {
    suspend fun get(key: String): Long
    suspend fun add(key: String, delta: Long)
    suspend fun all(): Map<String, Long>
}

/**
 * Token 用量统计（规格 §2.3）：每 Provider / 每项目累计。
 * 键：`provider:<id>` / `project:<id>`；不区分模型（会话粒度在 M4 预算层处理）。
 */
class UsageMeter(private val store: UsageStore) {

    suspend fun record(providerId: String, projectId: String?, tokens: Int) {
        if (tokens <= 0) return
        store.add(providerKey(providerId), tokens.toLong())
        if (!projectId.isNullOrBlank()) store.add(projectKey(projectId), tokens.toLong())
    }

    suspend fun providerTotal(providerId: String): Long = store.get(providerKey(providerId))

    suspend fun projectTotal(projectId: String): Long = store.get(projectKey(projectId))

    suspend fun all(): Map<String, Long> = store.all()

    companion object {
        fun providerKey(providerId: String) = "provider:$providerId"
        fun projectKey(projectId: String) = "project:$projectId"
    }
}
