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
 * 「Web」设置（规格 §7 通用子节点，M9）：WebGPU 状态检测（信息展示，不落盘）、
 * 桌面模式 UA · 下载行为 · eruda 面板开关。
 * eruda 开关经 [erudaNow] 由运行器入口读取（重开运行器生效）。
 */
class WebPreferences(private val store: DataStore<Preferences>) {

    data class Snapshot(
        val desktopUA: Boolean,
        val downloadBehavior: String, // ask | direct
        val erudaEnabled: Boolean,
    )

    val desktopUA: Flow<Boolean> = store.data.map { it[DESKTOP_UA] ?: false }
    val downloadBehavior: Flow<String> = store.data.map { it[DOWNLOAD] ?: DOWNLOAD_ASK }
    val erudaEnabled: Flow<Boolean> = store.data.map { it[ERUDA_ENABLED] ?: false }

    suspend fun snapshot(): Snapshot = Snapshot(
        desktopUA = desktopUA.first(),
        downloadBehavior = downloadBehavior.first(),
        erudaEnabled = erudaEnabled.first(),
    )

    suspend fun erudaNow(): Boolean = erudaEnabled.first()

    suspend fun setDesktopUA(v: Boolean) = store.edit { it[DESKTOP_UA] = v }

    suspend fun setDownloadBehavior(v: String) {
        require(v == DOWNLOAD_ASK || v == DOWNLOAD_DIRECT)
        store.edit { it[DOWNLOAD] = v }
    }

    suspend fun setErudaEnabled(v: Boolean) = store.edit { it[ERUDA_ENABLED] = v }

    companion object {
        const val DOWNLOAD_ASK = "ask"
        const val DOWNLOAD_DIRECT = "direct"

        val DESKTOP_UA = booleanPreferencesKey("web_desktop_ua")
        val DOWNLOAD = stringPreferencesKey("web_download_behavior")
        val ERUDA_ENABLED = booleanPreferencesKey("web_eruda_enabled")
    }
}
