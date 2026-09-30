package com.zhique.runner.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.zhique.core.ai.ModelRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 角色绑定持久化态：当前预设 + 五槽用户绑定 + 每项目覆盖。 */
@Serializable
data class RoleBindings(
    val preset: String = com.zhique.core.ai.Preset.BALANCED.name,
    val roles: Map<String, ModelRef> = emptyMap(),
    val projectOverrides: Map<String, Map<String, ModelRef>> = emptyMap(),
)

/** 角色绑定仓：读改写在 `edit{}` 单事务内完成（并发 bind 不丢更新，fix 11）。 */
class RoleBindingStore(private val store: DataStore<Preferences>, private val json: Json = Json { ignoreUnknownKeys = true }) {

    val bindings: Flow<RoleBindings> = store.data.map { prefs -> decode(prefs) }

    suspend fun current(): RoleBindings = bindings.first()

    suspend fun setPreset(preset: String) {
        store.edit { prefs -> write(prefs) { it.copy(preset = preset) } }
    }

    suspend fun bind(role: String, ref: ModelRef) {
        store.edit { prefs -> write(prefs) { it.copy(roles = it.roles + (role to ref)) } }
    }

    suspend fun unbind(role: String) {
        store.edit { prefs -> write(prefs) { it.copy(roles = it.roles - role) } }
    }

    suspend fun setProjectOverride(projectId: String, role: String, ref: ModelRef?) {
        store.edit { prefs ->
            write(prefs) { cur ->
                val map = (cur.projectOverrides[projectId] ?: emptyMap()).toMutableMap()
                if (ref == null) map.remove(role) else map[role] = ref
                cur.copy(projectOverrides = cur.projectOverrides + (projectId to map))
            }
        }
    }

    private fun decode(prefs: Preferences): RoleBindings =
        prefs[KEY]
            ?.let { raw -> runCatching { json.decodeFromString(RoleBindings.serializer(), raw) }.getOrNull() }
            ?: RoleBindings()

    /** 单事务内的原子读改写。 */
    private fun write(prefs: MutablePreferences, transform: (RoleBindings) -> RoleBindings) {
        prefs[KEY] = json.encodeToString(RoleBindings.serializer(), transform(decode(prefs)))
    }

    companion object {
        val KEY = stringPreferencesKey("role_bindings_json")
    }
}
