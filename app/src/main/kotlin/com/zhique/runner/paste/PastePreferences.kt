package com.zhique.runner.paste

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 「粘贴后自动运行/停在预览」偏好的可替换存储口（测试用假实现）。 */
interface PasteAutoRunStore {
    val autoRun: Flow<Boolean>
    suspend fun setAutoRun(value: Boolean)
}

/**
 * 通用偏好键 `paste_auto_run`（DataStore 落盘）。M2 先由粘贴预览屏开关；
 * M5 设置屏「智能粘贴」分组复用同一键（规格 §5.4）。
 * M9 补齐：剪贴板检测开关（Home 剪贴板卡显隐）+ 清洗严格度（Cleaner 档位）。
 */
class PastePreferences(private val store: DataStore<Preferences>) : PasteAutoRunStore {

    override val autoRun: Flow<Boolean> = store.data.map { it[AUTO_RUN] ?: false }

    /** 剪贴板检测开关（关=Home 不自动检测、不显剪贴板卡）。 */
    val clipboardDetection: Flow<Boolean> = store.data.map { it[CLIPBOARD_DETECT] ?: true }

    /** 清洗严格度：standard（全量剥离）/ conservative（保守：只剥结构围栏）。 */
    val cleanStrictness: Flow<String> = store.data.map { it[CLEAN_STRICT] ?: CLEAN_STANDARD }

    override suspend fun setAutoRun(value: Boolean) {
        store.edit { it[AUTO_RUN] = value }
    }

    suspend fun setClipboardDetection(value: Boolean) {
        store.edit { it[CLIPBOARD_DETECT] = value }
    }

    suspend fun setCleanStrictness(value: String) {
        require(value == CLEAN_STANDARD || value == CLEAN_CONSERVATIVE)
        store.edit { it[CLEAN_STRICT] = value }
    }

    companion object {
        val AUTO_RUN = booleanPreferencesKey("paste_auto_run")
        val CLIPBOARD_DETECT = booleanPreferencesKey("paste_clipboard_detect")
        val CLEAN_STRICT = stringPreferencesKey("paste_clean_strict")
        const val CLEAN_STANDARD = "standard"
        const val CLEAN_CONSERVATIVE = "conservative"

        fun fromContext(context: Context): PastePreferences {
            val dir = File(context.filesDir, "datastore").apply { mkdirs() }
            val store = PreferenceDataStoreFactory.create(
                produceFile = { File(dir, "paste.preferences_pb") },
            )
            return PastePreferences(store)
        }
    }
}
