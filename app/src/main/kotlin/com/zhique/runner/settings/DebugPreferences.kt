package com.zhique.runner.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 调试后端开关（设置域共享 DataStore，与 Provider/角色绑定同仓）。
 * 默认值跟构建类型：debug 构建开机即启，release 默认关、可在开发者页手动打开。
 */
class DebugPreferences(private val store: DataStore<Preferences>) {

    private val serverKey = booleanPreferencesKey("debug.server.enabled")
    private val sinkKey = booleanPreferencesKey("debug.sink.enabled")

    /** 传默认值（构建类型决定），避免本层感知 BuildConfig。 */
    fun serverEnabled(default: Boolean): Flow<Boolean> = store.data.map { it[serverKey] ?: default }

    fun sinkEnabled(default: Boolean): Flow<Boolean> = store.data.map { it[sinkKey] ?: default }

    suspend fun setServerEnabled(enabled: Boolean, default: Boolean) {
        store.edit { it[serverKey] = enabled }
        if (enabled == default) store.edit { it.remove(serverKey) }
    }

    suspend fun setSinkEnabled(enabled: Boolean, default: Boolean) {
        store.edit { it[sinkKey] = enabled }
        if (enabled == default) store.edit { it.remove(sinkKey) }
    }

    suspend fun currentServer(default: Boolean): Boolean = serverEnabled(default).first()

    suspend fun currentSink(default: Boolean): Boolean = sinkEnabled(default).first()
}
