package app.croptop.feature.library.batch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.croptop.core.data.catalog.CatalogRepository
import app.croptop.core.engine.api.ExportOptions
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@HiltViewModel
class BatchExportViewModel @Inject constructor(
    private val controller: BatchExportController,
    private val catalog: CatalogRepository,
    private val develop: app.croptop.core.data.DevelopRepository,
    private val presetStore: ExportPresetStore,
) : ViewModel() {
    val state = controller.state

    /** Built-in recipes first, then the user's saved ones. */
    val userPresets = presetStore.user
    fun savePreset(preset: ExportPreset) = presetStore.save(preset)
    fun deletePreset(name: String) = presetStore.delete(name)

    /** Exports the photos [ids] (in the given order) into [folder] with [options]. */
    fun start(ids: List<Long>, options: ExportOptions, folder: String) {
        viewModelScope.launch {
            val byId = catalog.images(ids).first().associateBy { it.id }
            val items = ids.mapNotNull { byId[it] }.map { image ->
                // A RAW exports with the recipe saved in Develop; a neutral recipe leaves the engine's own defaults.
                val recipe = if (app.croptop.core.model.isRawFileName(image.name)) {
                    runCatching { develop.settings(image.uri).first() }.getOrNull()?.takeUnless { it.isDefault }
                } else {
                    null
                }
                val iptc = app.croptop.core.engine.api.IptcMetadata(
                    title = image.title,
                    caption = image.caption,
                    creator = image.creator,
                    copyright = image.copyright,
                    keywords = runCatching { catalog.keywords(image.id).first() }.getOrDefault(emptyList()),
                ).takeUnless { it.isEmpty }
                BatchItem(image.uri, image.name, recipe, iptc)
            }
            controller.start(items, options, folder)
        }
    }

    fun cancel() = controller.cancel()
    fun dismiss() = controller.dismiss()
}
