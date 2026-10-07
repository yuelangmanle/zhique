package com.zhique.runner.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.zhique.core.common.crypto.CryptoStore
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** 一个已接入的 Provider 配置；Key 只存密文（keyCipher），明文仅在编辑时解出且不落日志。 */
@Serializable
data class ProviderConfig(
    val id: String,
    val name: String,
    val protocol: String,
    val baseUrl: String,
    val keyCipher: String,
    val model: String,
    val modalityManual: String? = null,
    val maxOutputManual: Int = 0,
    val contextManual: Int = 0,
)

/**
 * Provider 配置仓：DataStore 存 JSON 列表，Key 密文经 [CryptoStore]。
 * 读改写全部在 `edit{}` 单事务内完成（并发 upsert 不丢更新，fix 11）。
 */
class ProviderStore(
    private val store: DataStore<Preferences>,
    private val crypto: CryptoStore,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    val providers: Flow<List<ProviderConfig>> = store.data.map { prefs -> decode(prefs) }

    /** 同步缓存（init 即开始收集）：UI 同步解密/查找用，启动后毫秒级就绪。 */
    private val cachedFlow = kotlinx.coroutines.flow.MutableStateFlow<List<ProviderConfig>>(emptyList())
    val cached: List<ProviderConfig> get() = cachedFlow.value

    init {
        // 独立作用域收集（进程级 DataStore，生命周期同 App）
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            providers.collect { cachedFlow.value = it }
        }
    }

    suspend fun list(): List<ProviderConfig> = providers.first()

    suspend fun upsert(config: ProviderConfig) {
        store.edit { prefs -> write(prefs) { it.filterNot { c -> c.id == config.id } + config } }
    }

    suspend fun remove(id: String) {
        store.edit { prefs -> write(prefs) { it.filterNot { c -> c.id == id } } }
    }

    suspend fun get(id: String): ProviderConfig? = list().firstOrNull { it.id == id }

    /** 保存明文 Key → 密文。返回可落库的配置。 */
    fun encryptKey(plainKey: String): String = crypto.encrypt(plainKey)

    /** 按 id 解密（模型目录现场拉取用；同步缓存，未命中返回空串）。 */
    fun decryptKeyById(id: String): String = cached.firstOrNull { it.id == id }?.let { decryptKey(it) } ?: ""

    fun decryptKey(config: ProviderConfig): String =
        runCatching { crypto.decrypt(config.keyCipher) }.getOrDefault("")

    private fun decode(prefs: Preferences): List<ProviderConfig> =
        prefs[KEY_PROVIDERS]
            ?.let { raw -> runCatching { json.decodeFromString(ListSerializer(ProviderConfig.serializer()), raw) }.getOrNull() }
            ?: emptyList()

    /** 单事务内的原子读改写。 */
    private fun write(prefs: MutablePreferences, transform: (List<ProviderConfig>) -> List<ProviderConfig>) {
        prefs[KEY_PROVIDERS] = json.encodeToString(ListSerializer(ProviderConfig.serializer()), transform(decode(prefs)))
    }

    companion object {
        val KEY_PROVIDERS = stringPreferencesKey("providers_json")
    }
}
