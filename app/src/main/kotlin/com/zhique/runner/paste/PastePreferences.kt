package com.zhique.runner.paste

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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
 */
class PastePreferences(private val store: DataStore<Preferences>) : PasteAutoRunStore {

    override val autoRun: Flow<Boolean> = store.data.map { it[AUTO_RUN] ?: false }

    override suspend fun setAutoRun(value: Boolean) {
        store.edit { it[AUTO_RUN] = value }
    }

    companion object {
        val AUTO_RUN = booleanPreferencesKey("paste_auto_run")

        fun fromContext(context: Context): PastePreferences {
            val dir = File(context.filesDir, "datastore").apply { mkdirs() }
            val store = PreferenceDataStoreFactory.create(
                produceFile = { File(dir, "paste.preferences_pb") },
            )
            return PastePreferences(store)
        }
    }
}
