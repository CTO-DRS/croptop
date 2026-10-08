package app.croptop.feature.editor.brush

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.croptop.core.data.BrushFormatException
import app.croptop.core.data.BrushRepository
import app.croptop.core.model.BrushPreset
import app.croptop.core.model.BrushSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/**
 * What the brush options bar, Brush Settings and Brushes panels need. Hilt-free so screenshot tests
 * can pass fixed presets; [BrushViewModel.ui] builds the live one.
 */
data class BrushUi(
    val presets: List<BrushPreset> = BrushPreset.BuiltIn,
    val settings: BrushSettings = BrushPreset.BuiltIn.first().settings,
    /** The preset the settings came from; null after it's deleted. */
    val presetId: Long? = BrushPreset.BuiltIn.first().id,
    val message: String? = null,
    val onSettings: (BrushSettings) -> Unit = {},
    /** Updates high-frequency control previews without publishing a status result per sample. */
    val onSettingsPreview: (BrushSettings) -> Unit = onSettings,
    /** Publishes one status result when a continuous brush-setting interaction ends. */
    val onSettingsFinished: () -> Unit = {},
    val onSelect: (BrushPreset) -> Unit = {},
    val onSaveNew: (String) -> Unit = {},
    val onUpdatePreset: () -> Unit = {},
    val onFavorite: (BrushPreset) -> Unit = {},
    val onRename: (BrushPreset, String) -> Unit = { _, _ -> },
    val onDelete: (BrushPreset) -> Unit = {},
    val onImport: () -> Unit = {},
    val onExport: () -> Unit = {},
    val onActionCompleted: (String) -> Unit = {},
) {
    val preset: BrushPreset? get() = presets.firstOrNull { it.id == presetId }

    /** The settings differ from the preset they came from. */
    val modified: Boolean get() = preset?.settings?.normalized() != settings.normalized()
}

@HiltViewModel
class BrushViewModel @Inject constructor(
    private val brushes: BrushRepository,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {
    private val settings = MutableStateFlow(BrushPreset.BuiltIn.first().settings)
    private val presetId = MutableStateFlow<Long?>(BrushPreset.BuiltIn.first().id)
    val message = MutableStateFlow<String?>(null)

    val ui: StateFlow<BrushUi> = combine(brushes.presets, settings, presetId, message) { p, s, id, m ->
        ui(p, s, id, m)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ui(BrushPreset.BuiltIn, settings.value, presetId.value, null))

    private fun ui(p: List<BrushPreset>, s: BrushSettings, id: Long?, m: String?) = BrushUi(
        presets = p,
        settings = s,
        presetId = id,
        message = m,
        onSettings = { settings.value = it.normalized() },
        onSelect = ::select,
        onSaveNew = { saveNew(it) },
        onUpdatePreset = { updatePreset() },
        onFavorite = { setFavorite(it) },
        onRename = { b, name -> rename(b, name) },
        onDelete = { deletePreset(it) },
    )

    fun select(p: BrushPreset) {
        presetId.value = p.id
        settings.value = p.settings.normalized()
    }

    fun saveNew(name: String): Deferred<String> = viewModelScope.async {
        presetId.value = brushes.create(name, settings.value)
        "Saved “${name.trim()}” to My Brushes".also { message.value = it }
    }

    fun updatePreset(): Deferred<String> = viewModelScope.async {
        val preset = ui.value.preset
        if (preset == null || preset.builtIn) {
            return@async "Select a custom brush preset before updating".also { message.value = it }
        }
        brushes.update(preset.id, settings.value)
        "Updated ${preset.name}".also { message.value = it }
    }

    fun setFavorite(p: BrushPreset): Deferred<String> = viewModelScope.async {
        brushes.setFavorite(p.id, !p.favorite)
        "${if (p.favorite) "Removed" else "Added"} ${p.name} ${if (p.favorite) "from" else "to"} favorites".also { message.value = it }
    }

    fun rename(p: BrushPreset, name: String): Deferred<String> = viewModelScope.async {
        brushes.rename(p.id, name)
        "Renamed brush to ${name.trim()}".also { message.value = it }
    }

    fun deletePreset(p: BrushPreset): Deferred<String> = viewModelScope.async {
        brushes.delete(p.id)
        if (presetId.value == p.id) presetId.value = null
        "Deleted ${p.name}".also { message.value = it }
    }

    /** Imports a `.aebrushes` file into My Brushes. */
    fun import(uri: Uri): Deferred<String> = viewModelScope.async {
        val outcome = try {
            val imported = withContext(Dispatchers.IO) {
                val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                    // Bounded read (readNBytes needs API 33; minSdk is 31).
                    val out = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(chunk)
                        if (n < 0) break
                        out.write(chunk, 0, n)
                        if (out.size() > MAX_FILE) throw BrushFormatException("That file is too large to be a brushes file.")
                    }
                    out.toByteArray()
                } ?: error("Can't read the file")
                brushes.import(bytes)
            }
            if (imported == 1) "Imported 1 brush" else "Imported $imported brushes"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            (failure as? BrushFormatException)?.message ?: "Couldn't import brushes"
        }
        message.value = outcome
        outcome
    }

    /** Exports My Brushes (or the current brush when there are none). */
    fun export(uri: Uri): Deferred<String> = viewModelScope.async {
        val current = ui.value
        val mine = current.presets.filter { !it.builtIn }.ifEmpty { listOfNotNull(current.preset?.copy(settings = current.settings)) }
        val outcome = try {
            val bytes = brushes.export(mine)
            val written = withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } != null
            }
            if (written) "Exported ${mine.size} brush${if (mine.size == 1) "" else "es"}" else "Couldn't export brushes"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            "Couldn't export brushes"
        }
        message.value = outcome
        outcome
    }

    private companion object {
        const val MAX_FILE = 4 * 1024 * 1024
    }
}
