package com.zhique.runner.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** 发布偏好（§7「发布与同步」）：自更新通道 stable / beta。 */
class PublishPreferences(private val store: DataStore<Preferences>) {

    val channel: Flow<String> = store.data.map { prefs -> prefs[KEY_CHANNEL] ?: CHANNEL_STABLE }

    suspend fun channelNow(): String = channel.first()

    suspend fun setChannel(channel: String) {
        require(channel == CHANNEL_STABLE || channel == CHANNEL_BETA)
        store.edit { prefs -> prefs[KEY_CHANNEL] = channel }
    }

    companion object {
        const val CHANNEL_STABLE = "stable"
        const val CHANNEL_BETA = "beta"
        private val KEY_CHANNEL = stringPreferencesKey("publish_channel")
    }
}
