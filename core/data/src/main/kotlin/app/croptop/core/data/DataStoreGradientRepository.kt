package app.croptop.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.croptop.core.model.ColorGradient
import app.croptop.core.model.GradientPreset
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Singleton
class DataStoreGradientRepository @Inject constructor(private val store: DataStore<Preferences>) : GradientRepository {
    override val customPresets: Flow<List<GradientPreset>> = store.data.map {
        decode(it[Keys.presets]).sortedBy { preset -> preset.name.lowercase() }
    }

    override suspend fun save(name: String, gradient: ColorGradient): GradientPreset {
        val cleanName = requireName(name)
        var created: GradientPreset? = null
        store.edit { prefs ->
            val current = decode(prefs[Keys.presets])
            val id = ((current.maxOfOrNull { it.id } ?: 0L) + 1L).coerceAtLeast(1L)
            val preset = GradientPreset(id, cleanName, gradient)
            prefs[Keys.presets] = json.encodeToString(ListSerializer(GradientPreset.serializer()), current + preset)
            created = preset
        }
        return checkNotNull(created)
    }

    override suspend fun update(id: Long, name: String, gradient: ColorGradient): Boolean {
        if (id <= 0L) return false
        val cleanName = requireName(name)
        var changed = false
        store.edit { prefs ->
            val current = decode(prefs[Keys.presets])
            if (current.any { it.id == id }) {
                val next = current.map { if (it.id == id) it.copy(name = cleanName, gradient = gradient) else it }
                prefs[Keys.presets] = json.encodeToString(ListSerializer(GradientPreset.serializer()), next)
                changed = true
            }
        }
        return changed
    }

    override suspend fun delete(id: Long): Boolean {
        if (id <= 0L) return false
        var changed = false
        store.edit { prefs ->
            val current = decode(prefs[Keys.presets])
            val next = current.filterNot { it.id == id }
            changed = next.size != current.size
            if (changed) prefs[Keys.presets] = json.encodeToString(ListSerializer(GradientPreset.serializer()), next)
        }
        return changed
    }

    private fun decode(raw: String?): List<GradientPreset> = raw?.let {
        runCatching { json.decodeFromString(ListSerializer(GradientPreset.serializer()), it) }.getOrNull()
    }.orEmpty().filter { it.id > 0L }

    private fun requireName(name: String): String = name.trim().take(60).also {
        require(it.isNotEmpty()) { "Preset name cannot be blank" }
    }

    private val json = Json { ignoreUnknownKeys = true }

    private object Keys {
        val presets = stringPreferencesKey("gradient.presets")
    }
}
