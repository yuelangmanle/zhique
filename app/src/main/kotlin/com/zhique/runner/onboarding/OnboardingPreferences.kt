package com.zhique.runner.onboarding

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** 首启引导偏好：完成标记 + 隐私告知勾选记录（X4 / §6 隐私：首次用云 API 前告知）。 */
class OnboardingPreferences(private val store: DataStore<Preferences>) {

    val done: Flow<Boolean> = store.data.map { it[KEY_DONE] ?: false }

    /** 隐私告知勾选时刻（epoch ms），0 = 未勾选。 */
    val privacyAckedAt: Flow<Long> = store.data.map { it[KEY_PRIVACY_ACK_AT] ?: 0L }

    suspend fun isDone(): Boolean = done.first()

    suspend fun markDone() {
        store.edit { it[KEY_DONE] = true }
    }

    suspend fun ackPrivacy(atMs: Long = System.currentTimeMillis()) {
        store.edit { it[KEY_PRIVACY_ACK_AT] = atMs }
    }

    companion object {
        val KEY_DONE = booleanPreferencesKey("onboarding_done")
        val KEY_PRIVACY_ACK_AT = longPreferencesKey("onboarding_privacy_ack_at")
    }
}
