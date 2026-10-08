package app.croptop.feature.editor.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.component.AeButton
import app.croptop.core.designsystem.component.AeButtonStyle
import app.croptop.core.designsystem.component.AeDialog
import app.croptop.core.designsystem.component.AeSlider
import app.croptop.core.model.BasicTone
import app.croptop.core.model.DevelopSettings
import app.croptop.core.model.LayerId

/**
 * Re-develops a RAW smart object from its original file. Edits the Basic tone controls; everything else in the recipe
 * (curves, color mixer, masks) is preserved, so use Develop for those. Apply is one history step.
 */
@Composable
fun RawDevelopDialog(
    layerId: LayerId,
    layerName: String,
    initial: DevelopSettings,
    onApply: (LayerId, DevelopSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    var tone by remember(layerId) { mutableStateOf(initial.basic) }
    fun apply() {
        onApply(layerId, initial.copy(basic = tone))
        onDismiss()
    }
    AeDialog(
        title = "Develop Settings · $layerName",
        body = "Adjusts how the RAW file is developed. The original stays in the document, and this is one undo step.",
        onDismiss = onDismiss,
        onConfirm = ::apply,
        width = 440.dp,
        actions = {
            AeButton("Reset", { tone = BasicTone() })
            AeButton("Cancel", onDismiss)
            AeButton("Apply", ::apply, style = AeButtonStyle.Primary, enabled = tone != initial.basic)
        },
    ) {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AeSlider("Exposure", tone.exposure, {
                tone = tone.copy(exposure = it)
            }, range = -5f..5f, defaultValue = 0f, step = 0.05f, decimals = 2, unit = "EV")
            Percent("Contrast", tone.contrast) { tone = tone.copy(contrast = it) }
            Percent("Highlights", tone.highlights) { tone = tone.copy(highlights = it) }
            Percent("Shadows", tone.shadows) { tone = tone.copy(shadows = it) }
            Percent("Whites", tone.whites) { tone = tone.copy(whites = it) }
            Percent("Blacks", tone.blacks) { tone = tone.copy(blacks = it) }
            Percent("Texture", tone.texture) { tone = tone.copy(texture = it) }
            Percent("Clarity", tone.clarity) { tone = tone.copy(clarity = it) }
            Percent("Dehaze", tone.dehaze) { tone = tone.copy(dehaze = it) }
            Percent("Vibrance", tone.vibrance) { tone = tone.copy(vibrance = it) }
            Percent("Saturation", tone.saturation) { tone = tone.copy(saturation = it) }
        }
    }
}

@Composable
private fun Percent(label: String, value: Float, onChange: (Float) -> Unit) =
    AeSlider(label, value, onChange, range = -100f..100f, defaultValue = 0f)
