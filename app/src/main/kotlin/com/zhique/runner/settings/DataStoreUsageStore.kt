package com.zhique.runner.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import com.zhique.core.ai.UsageStore
import kotlinx.coroutines.flow.first

/** DataStore 版用量存储（UsageMeter 落盘，规格 §2.3 Token 用量统计）。 */
class DataStoreUsageStore(private val store: DataStore<Preferences>) : UsageStore {

    override suspend fun get(key: String): Long =
        store.data.first()[prefKey(key)] ?: 0L

    override suspend fun add(key: String, delta: Long) {
        store.edit { prefs ->
            prefs[prefKey(key)] = (prefs[prefKey(key)] ?: 0L) + delta
        }
    }

    override suspend fun all(): Map<String, Long> =
        store.data.first()
            .asMap()
            .filterKeys { it.name.startsWith(PREFIX) }
            .mapKeys { it.key.name.removePrefix(PREFIX) }
            .mapValues { it.value as Long }

    private fun prefKey(key: String) = longPreferencesKey(PREFIX + key)

    companion object {
        const val PREFIX = "usage_"
    }
}
