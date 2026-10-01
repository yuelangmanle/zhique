package com.zhique.runner.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 发布偏好（§7「发布与同步」，M9 补齐推送偏好）：自更新通道 stable / beta、
 * commit 语言 zh/en、默认分支、新仓默认公私。PAT 管理在 PublishSyncScreen（M7）。
 */
class PublishPreferences(private val store: DataStore<Preferences>) {

    data class Snapshot(
        val channel: String,
        val commitLanguage: String, // zh | en
        val defaultBranch: String,
        val newRepoPrivate: Boolean,
    )

    val channel: Flow<String> = store.data.map { prefs -> prefs[KEY_CHANNEL] ?: CHANNEL_STABLE }
    val commitLanguage: Flow<String> = store.data.map { it[KEY_COMMIT_LANG] ?: COMMIT_ZH }
    val defaultBranch: Flow<String> = store.data.map { it[KEY_DEFAULT_BRANCH] ?: DEFAULT_BRANCH }
    val newRepoPrivate: Flow<Boolean> = store.data.map { it[KEY_NEW_REPO_PRIVATE] ?: true }

    suspend fun channelNow(): String = channel.first()

    suspend fun setChannel(channel: String) {
        require(channel == CHANNEL_STABLE || channel == CHANNEL_BETA)
        store.edit { prefs -> prefs[KEY_CHANNEL] = channel }
    }

    suspend fun setCommitLanguage(v: String) {
        require(v == COMMIT_ZH || v == COMMIT_EN)
        store.edit { it[KEY_COMMIT_LANG] = v }
    }

    suspend fun setDefaultBranch(v: String) {
        require(v.isNotBlank() && Regex("""[\w.\-/]+""").matches(v)) { "分支名不合法" }
        store.edit { it[KEY_DEFAULT_BRANCH] = v }
    }

    suspend fun setNewRepoPrivate(v: Boolean) = store.edit { it[KEY_NEW_REPO_PRIVATE] = v }

    companion object {
        const val CHANNEL_STABLE = "stable"
        const val CHANNEL_BETA = "beta"
        const val COMMIT_ZH = "zh"
        const val COMMIT_EN = "en"
        const val DEFAULT_BRANCH = "main"

        private val KEY_CHANNEL = stringPreferencesKey("publish_channel")
        private val KEY_COMMIT_LANG = stringPreferencesKey("publish_commit_language")
        private val KEY_DEFAULT_BRANCH = stringPreferencesKey("publish_default_branch")
        private val KEY_NEW_REPO_PRIVATE = booleanPreferencesKey("publish_new_repo_private")
    }
}
