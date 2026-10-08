package app.croptop.feature.library.batch

import androidx.core.content.edit
import android.content.Context
import app.croptop.core.engine.api.ExportFormat
import app.croptop.core.engine.api.ExportOptions
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A named batch-export recipe. */
@Serializable
data class ExportPreset(val name: String, val format: ExportFormat, val quality: Int, val scalePercent: Int, val stripMetadata: Boolean) {
    init {
        require(name.isNotBlank() && name.length <= 40) { "preset names are 1-40 characters" }
        require(quality in 1..100 && scalePercent in 1..100)
    }

    fun toOptions() = ExportOptions(format, quality, scalePercent / 100f, stripMetadata)
}

/** Where user presets live; tests use memory, the app uses SharedPreferences. */
interface PresetStorage {
    fun read(): String?
    fun write(json: String)
}

/** Built-in recipes plus the user's own, newest saved last. Built-ins cannot be replaced or deleted. */
class ExportPresetStore(private val storage: PresetStorage) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _user = MutableStateFlow(loadUser())
    val user: StateFlow<List<ExportPreset>> = _user.asStateFlow()

    private fun loadUser(): List<ExportPreset> = runCatching {
        json.decodeFromString<List<ExportPreset>>(storage.read() ?: return emptyList())
    }.getOrDefault(emptyList()).filter { u -> BUILT_IN.none { it.name.equals(u.name, ignoreCase = true) } }

    /** Saves [preset], replacing a user preset of the same name. False when the name belongs to a built-in. */
    @Synchronized
    fun save(preset: ExportPreset): Boolean {
        if (BUILT_IN.any { it.name.equals(preset.name, ignoreCase = true) }) return false
        val next = _user.value.filterNot { it.name.equals(preset.name, ignoreCase = true) } + preset
        storage.write(json.encodeToString(next))
        _user.value = next
        return true
    }

    @Synchronized
    fun delete(name: String) {
        val next = _user.value.filterNot { it.name == name }
        if (next.size == _user.value.size) return
        storage.write(json.encodeToString(next))
        _user.value = next
    }

    companion object {
        val BUILT_IN = listOf(
            ExportPreset("High-quality JPEG", ExportFormat.JPEG, 95, 100, false),
            ExportPreset("Web JPEG (half size)", ExportFormat.JPEG, 85, 50, true),
            ExportPreset("Lossless PNG", ExportFormat.PNG, 100, 100, false),
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
object ExportPresetModule {
    @Provides
    @Singleton
    fun store(@ApplicationContext context: Context): ExportPresetStore = ExportPresetStore(
        object : PresetStorage {
            private val prefs = context.getSharedPreferences("batch-export-presets", Context.MODE_PRIVATE)
            override fun read(): String? = prefs.getString("user", null)
            override fun write(json: String) {
                prefs.edit { putString("user", json) }
            }
        },
    )
}
