package com.zhique.runner.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.zhique.core.common.crypto.CryptoStore
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

/** Provider 配置仓：DataStore 存 JSON 列表，Key 密文经 [CryptoStore]。 */
class ProviderStore(
    private val store: DataStore<Preferences>,
    private val crypto: CryptoStore,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    val providers: Flow<List<ProviderConfig>> = store.data.map { prefs ->
        val raw = prefs[KEY_PROVIDERS] ?: return@map emptyList()
        runCatching { json.decodeFromString(ListSerializer(ProviderConfig.serializer()), raw) }
            .getOrDefault(emptyList())
    }

    suspend fun list(): List<ProviderConfig> = providers.first()

    suspend fun upsert(config: ProviderConfig) {
        val next = list().filterNot { it.id == config.id } + config
        write(next)
    }

    suspend fun remove(id: String) = write(list().filterNot { it.id == id })

    suspend fun get(id: String): ProviderConfig? = list().firstOrNull { it.id == id }

    /** 保存明文 Key → 密文。返回可落库的配置。 */
    fun encryptKey(plainKey: String): String = crypto.encrypt(plainKey)

    fun decryptKey(config: ProviderConfig): String =
        runCatching { crypto.decrypt(config.keyCipher) }.getOrDefault("")

    private suspend fun write(configs: List<ProviderConfig>) {
        store.edit { prefs ->
            prefs[KEY_PROVIDERS] = json.encodeToString(ListSerializer(ProviderConfig.serializer()), configs)
        }
    }

    companion object {
        val KEY_PROVIDERS = stringPreferencesKey("providers_json")
    }
}
