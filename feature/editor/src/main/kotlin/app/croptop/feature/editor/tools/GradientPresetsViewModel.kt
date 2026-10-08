package app.croptop.feature.editor.tools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.croptop.core.data.GradientRepository
import app.croptop.core.model.ColorGradient
import app.croptop.core.model.GradientPreset
import app.croptop.core.model.GradientStop
import app.croptop.core.model.RgbaColor
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class GradientPresetsViewModel @Inject constructor(private val repository: GradientRepository) : ViewModel() {
    val customPresets: StateFlow<List<GradientPreset>> = repository.customPresets.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    fun save(
        name: String,
        gradient: ColorGradient,
        onSaved: (GradientPreset) -> Unit = {},
        onFailure: (String) -> Unit = {},
    ) = viewModelScope.launch {
        val saved = try {
            repository.save(name, gradient)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            onFailure(failure.message?.takeIf(String::isNotBlank) ?: "Couldn't save gradient preset")
            return@launch
        }
        onSaved(saved)
    }

    fun update(
        preset: GradientPreset,
        name: String,
        gradient: ColorGradient,
        onUpdated: () -> Unit = {},
        onFailure: (String) -> Unit = {},
    ) = viewModelScope.launch {
        val updated = try {
            repository.update(preset.id, name, gradient)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            onFailure(failure.message?.takeIf(String::isNotBlank) ?: "Couldn't update gradient preset")
            return@launch
        }
        if (updated) onUpdated() else onFailure("Gradient preset is no longer available")
    }

    fun delete(preset: GradientPreset, onDeleted: () -> Unit = {}, onFailure: (String) -> Unit = {}) = viewModelScope.launch {
        val deleted = try {
            repository.delete(preset.id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            onFailure(failure.message?.takeIf(String::isNotBlank) ?: "Couldn't delete gradient preset")
            return@launch
        }
        if (deleted) onDeleted() else onFailure("Gradient preset is no longer available")
    }
}

val BuiltInGradientPresets = listOf(
    GradientPreset(-1, "Black to white", ColorGradient.BlackToWhite),
    GradientPreset(
        -2,
        "Transparent to white",
        ColorGradient(listOf(GradientStop(0f, RgbaColor.Transparent), GradientStop(1f, RgbaColor.White))),
    ),
    GradientPreset(
        -3,
        "Sunset",
        ColorGradient(
            listOf(
                GradientStop(0f, RgbaColor(0.17f, 0.03f, 0.38f)),
                GradientStop(0.52f, RgbaColor(0.91f, 0.16f, 0.32f)),
                GradientStop(1f, RgbaColor(1f, 0.72f, 0.25f)),
            ),
        ),
    ),
)
