package com.zhique.runner.settings

import com.zhique.core.ai.AiError
import com.zhique.core.ai.ModelListFetcher
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 服务商模型目录：每个 provider 拉取过的 /models 列表缓存（内存 + 磁盘）。
 *
 * 解决的问题：模型列表只在「AI 服务商→编辑→拉取」那一刻可见，角色路由、
 * 对话等其他要选模型的地方拿不到——用户被迫手抄 `deepseek-ai/DeepSeek-V4-Flash-0731`
 * 这类长名。目录拉一次全局可用。
 *
 * 稳定性：文件读写全 runCatching（损坏即当空目录重建）；并发拉取按 providerId
 * 互斥（同 provider 的重复点击只打一次网络）。
 */
class ProviderModelCatalog(private val file: File) {

    @kotlinx.serialization.Serializable
    private data class Entry(val providerId: String, val models: List<String>, val updatedAt: Long)

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val mutex = Mutex()

    private val _catalog = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val catalog: StateFlow<Map<String, List<String>>> = _catalog.asStateFlow()

    /** App 启动时从磁盘载入一次。 */
    suspend fun load() = mutex.withLock {
        val map = runCatching {
            if (!file.isFile) emptyMap()
            else json.decodeFromString<List<Entry>>(file.readText())
                .associate { it.providerId to it.models }
        }.getOrDefault(emptyMap())
        _catalog.value = map
    }

    fun modelsOf(providerId: String): List<String> = _catalog.value[providerId].orEmpty()

    /** 写入（拉取成功后调用）。 */
    suspend fun put(providerId: String, models: List<String>) = mutex.withLock {
        if (models.isEmpty()) return@withLock
        val current = _catalog.value.toMutableMap()
        current[providerId] = models
        _catalog.value = current
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(current.map { (k, v) -> Entry(k, v, System.currentTimeMillis()) }))
        }
    }

    /** 现场拉取并写入；失败抛 AiError 供 UI 分类提示。Key 明文由调用方解密传入（零日志零 URL）。 */
    suspend fun fetchInto(
        providerId: String,
        protocol: String,
        baseUrl: String,
        apiKey: String,
        fetcher: ModelListFetcher,
    ): List<String> {
        val models = fetcher.fetch(protocol, baseUrl, apiKey)
        put(providerId, models)
        return models
    }

    fun remove(providerId: String) {
        // 删除服务商时清目录（内存态即可，磁盘由下次 put 收敛）
        val current = _catalog.value.toMutableMap()
        current.remove(providerId)
        _catalog.value = current
    }
}
