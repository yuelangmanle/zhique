package com.zhique.runner.settings

import androidx.datastore.core.DataStore
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

class RoleBindingStore(private val store: DataStore<Preferences>, private val json: Json = Json { ignoreUnknownKeys = true }) {

    val bindings: Flow<RoleBindings> = store.data.map { prefs ->
        val raw = prefs[KEY] ?: return@map RoleBindings()
        runCatching { json.decodeFromString(RoleBindings.serializer(), raw) }.getOrDefault(RoleBindings())
    }

    suspend fun current(): RoleBindings = bindings.first()

    suspend fun setPreset(preset: String) = write(current().copy(preset = preset))

    suspend fun bind(role: String, ref: ModelRef) = write(current().copy(roles = current().roles + (role to ref)))

    suspend fun unbind(role: String) = write(current().copy(roles = current().roles - role))

    suspend fun setProjectOverride(projectId: String, role: String, ref: ModelRef?) {
        val cur = current()
        val forProject = (cur.projectOverrides[projectId] ?: emptyMap()).toMutableMap()
        if (ref == null) forProject.remove(role) else forProject[role] = ref
        write(cur.copy(projectOverrides = cur.projectOverrides + (projectId to forProject)))
    }

    private suspend fun write(value: RoleBindings) {
        store.edit { it[KEY] = json.encodeToString(RoleBindings.serializer(), value) }
    }

    companion object {
        val KEY = stringPreferencesKey("role_bindings_json")
    }
}
