package com.zhique.runner.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 「通用」设置（规格 §7 通用子节点，M9）：外观 · 运行器（默认布局三选一/悬浮球
 * 位置记忆/沉浸模式）· 编辑器（字号/自动缩进）· 通知三事件开关。
 * 智能粘贴分组复用 [com.zhique.runner.paste.PastePreferences]（同键不同 store 文件）。
 * 外观当前仅「跟随系统」（规格 §7），键留扩展位。
 */
class GeneralPreferences(private val store: DataStore<Preferences>) {

    data class Snapshot(
        val themeFollowSystem: Boolean,
        val runnerDefaultLayout: String, // drawer | split | bubble
        val bubbleRememberPosition: Boolean,
        val bubbleX: Float, // 0–1 归一化
        val bubbleY: Float,
        val immersiveMode: Boolean,
        val editorFontSize: Int,
        val editorAutoIndent: Boolean,
        val notifyExportDone: Boolean,
        val notifyAgentDone: Boolean,
        val notifyNewVersion: Boolean,
    )

    val themeFollowSystem: Flow<Boolean> = store.data.map { it[THEME] ?: true }
    val runnerDefaultLayout: Flow<String> = store.data.map { it[RUNNER_LAYOUT] ?: LAYOUT_DRAWER }
    val bubbleRememberPosition: Flow<Boolean> = store.data.map { it[BUBBLE_REMEMBER] ?: true }
    val bubbleX: Flow<Float> = store.data.map { it[BUBBLE_X] ?: 0.85f }
    val bubbleY: Flow<Float> = store.data.map { it[BUBBLE_Y] ?: 0.3f }
    val immersiveMode: Flow<Boolean> = store.data.map { it[IMMERSIVE] ?: false }
    val editorFontSize: Flow<Int> = store.data.map { it[EDITOR_FONT] ?: 14 }
    val editorAutoIndent: Flow<Boolean> = store.data.map { it[EDITOR_AUTO_INDENT] ?: true }
    val notifyExportDone: Flow<Boolean> = store.data.map { it[NOTIFY_EXPORT] ?: true }
    val notifyAgentDone: Flow<Boolean> = store.data.map { it[NOTIFY_AGENT] ?: true }
    val notifyNewVersion: Flow<Boolean> = store.data.map { it[NOTIFY_NEW_VERSION] ?: true }

    suspend fun snapshot(): Snapshot = Snapshot(
        themeFollowSystem = themeFollowSystem.first(),
        runnerDefaultLayout = runnerDefaultLayout.first(),
        bubbleRememberPosition = bubbleRememberPosition.first(),
        bubbleX = bubbleX.first(),
        bubbleY = bubbleY.first(),
        immersiveMode = immersiveMode.first(),
        editorFontSize = editorFontSize.first(),
        editorAutoIndent = editorAutoIndent.first(),
        notifyExportDone = notifyExportDone.first(),
        notifyAgentDone = notifyAgentDone.first(),
        notifyNewVersion = notifyNewVersion.first(),
    )

    suspend fun setRunnerDefaultLayout(v: String) {
        require(v == LAYOUT_DRAWER || v == LAYOUT_SPLIT || v == LAYOUT_BUBBLE)
        store.edit { it[RUNNER_LAYOUT] = v }
    }

    /** 悬浮球位置记忆（归一化坐标，退出运行器时由 FloatingBubble 持久层回写）。 */
    suspend fun setBubblePosition(x: Float, y: Float) {
        require(x in 0f..1f && y in 0f..1f)
        store.edit {
            it[BUBBLE_X] = x.coerceIn(0f, 1f)
            it[BUBBLE_Y] = y.coerceIn(0f, 1f)
        }
    }

    suspend fun setBubbleRemember(v: Boolean) = store.edit { it[BUBBLE_REMEMBER] = v }
    suspend fun setImmersiveMode(v: Boolean) = store.edit { it[IMMERSIVE] = v }

    suspend fun setEditorFontSize(v: Int) {
        require(v in 10..28) { "编辑器字号须 10–28" }
        store.edit { it[EDITOR_FONT] = v }
    }

    suspend fun setEditorAutoIndent(v: Boolean) = store.edit { it[EDITOR_AUTO_INDENT] = v }
    suspend fun setNotifyExportDone(v: Boolean) = store.edit { it[NOTIFY_EXPORT] = v }
    suspend fun setNotifyAgentDone(v: Boolean) = store.edit { it[NOTIFY_AGENT] = v }
    suspend fun setNotifyNewVersion(v: Boolean) = store.edit { it[NOTIFY_NEW_VERSION] = v }

    companion object {
        const val LAYOUT_DRAWER = "drawer"
        const val LAYOUT_SPLIT = "split"
        const val LAYOUT_BUBBLE = "bubble"

        val THEME = booleanPreferencesKey("theme_follow_system")
        val RUNNER_LAYOUT = stringPreferencesKey("runner_default_layout")
        val BUBBLE_REMEMBER = booleanPreferencesKey("bubble_remember_position")
        val BUBBLE_X = floatPreferencesKey("bubble_x")
        val BUBBLE_Y = floatPreferencesKey("bubble_y")
        val IMMERSIVE = booleanPreferencesKey("runner_immersive")
        val EDITOR_FONT = intPreferencesKey("editor_font_size")
        val EDITOR_AUTO_INDENT = booleanPreferencesKey("editor_auto_indent")
        val NOTIFY_EXPORT = booleanPreferencesKey("notify_export_done")
        val NOTIFY_AGENT = booleanPreferencesKey("notify_agent_done")
        val NOTIFY_NEW_VERSION = booleanPreferencesKey("notify_new_version")
    }
}
