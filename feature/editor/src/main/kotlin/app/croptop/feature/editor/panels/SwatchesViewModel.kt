package app.croptop.feature.editor.panels

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.croptop.core.data.PreferencesRepository
import app.croptop.core.model.RgbaColor
import app.croptop.core.model.Swatch
import app.croptop.core.model.SwatchFormatException
import app.croptop.core.model.SwatchIo
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/** What the Color panel needs for swatches; Hilt-free so previews/tests can pass a fixed list. */
data class SwatchesUi(
    val swatches: List<Swatch>,
    val onAdd: (RgbaColor) -> Unit = {},
    val onRemove: (Int) -> Unit = {},
    val onImport: () -> Unit = {},
    val onExport: () -> Unit = {},
    val onReset: () -> Unit = {},
    val message: String? = null,
)

val DefaultSwatchList: List<Swatch> = DefaultSwatches.map { Swatch("#${it.hex}", it) }

@HiltViewModel
class SwatchesViewModel @Inject constructor(
    private val prefs: PreferencesRepository,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {
    val swatches: StateFlow<List<Swatch>> = prefs.swatches.map { it ?: DefaultSwatchList }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DefaultSwatchList)
    val message = MutableStateFlow<String?>(null)

    private fun edit(f: (List<Swatch>) -> List<Swatch>): Deferred<Unit> = viewModelScope.async {
        prefs.setSwatches(f(prefs.swatches.first() ?: DefaultSwatchList).take(SwatchIo.MAX_SWATCHES))
    }

    fun add(color: RgbaColor) = edit { list -> if (list.any { it.color.toArgb() == color.toArgb() }) list else list + Swatch("#${color.hex}", color) }

    fun remove(index: Int) = edit { it.filterIndexed { i, _ -> i != index } }

    fun reset(): Deferred<Unit> = viewModelScope.async { prefs.setSwatches(null) }

    /** Imports .aco/.ase and appends new colors (duplicates skipped). */
    fun import(uri: Uri): Deferred<String> = viewModelScope.async {
        val name = uri.lastPathSegment.orEmpty()
        val outcome = try {
            val added = withContext(Dispatchers.IO) {
                val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(chunk)
                        if (n < 0) break
                        out.write(chunk, 0, n)
                        if (out.size() > MAX_FILE) throw SwatchFormatException("That file is too large to be a swatch file.")
                    }
                    out.toByteArray()
                } ?: error("Can't read the file")
                SwatchIo.parse(bytes, name)
            }
            var count = 0
            var paletteFull = false
            edit { list ->
                val seen = list.map { it.color.toArgb() }.toMutableSet()
                val unique = added.filter { seen.add(it.color.toArgb()) }
                val incoming = unique.take((SwatchIo.MAX_SWATCHES - list.size).coerceAtLeast(0))
                paletteFull = incoming.size < unique.size
                count = incoming.size
                list + incoming
            }.await()
            if (count == 0 && paletteFull) {
                "Swatch palette is full"
            } else if (count == 0) {
                "No new colors in that file"
            } else {
                "Added $count swatches"
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            (failure as? SwatchFormatException)?.message ?: "Couldn't import swatches"
        }
        message.value = outcome
        outcome
    }

    fun export(uri: Uri): Deferred<String> = viewModelScope.async {
        val outcome = try {
            val exported = prefs.swatches.first() ?: DefaultSwatchList
            val bytes = SwatchIo.writeAse(exported)
            val written = withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } != null
            }
            if (written) "Exported ${exported.size} swatches" else "Couldn't export swatches"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            "Couldn't export swatches"
        }
        message.value = outcome
        outcome
    }

    private companion object {
        const val MAX_FILE = 4 * 1024 * 1024
    }
}
