package app.croptop.feature.editor.properties

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.component.AeButton
import app.croptop.core.designsystem.component.AeButtonStyle
import app.croptop.core.designsystem.component.AeSlider
import app.croptop.core.designsystem.component.AeText
import app.croptop.core.designsystem.icon.AeIcons
import app.croptop.core.designsystem.theme.Ae
import app.croptop.core.model.LayerMaskInfo

/** What the mask section can do; each maps to one engine call. */
class MaskActions(
    val onChange: (LayerMaskInfo) -> Unit,
    val onToggle: () -> Unit,
    val onInvert: () -> Unit,
    val onLoadSelection: () -> Unit,
    val onApply: (() -> Unit)?,
    val onDelete: () -> Unit,
)

/** Properties ▸ Layer Mask: density, feather and the mask commands. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MaskProperties(mask: LayerMaskInfo, actions: MaskActions, modifier: Modifier = Modifier) {
    Column(modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AeText("Layer Mask", style = Ae.type.title, modifier = Modifier.weight(1f))
            if (!mask.enabled) AeText("Disabled", style = Ae.type.caption, color = Ae.colors.danger)
        }
        AeSlider(
            "Density",
            mask.density * 100f,
            { actions.onChange(mask.copy(density = it / 100f)) },
            range = 0f..100f,
            defaultValue = 100f,
            unit = "%",
        )
        AeSlider(
            "Feather",
            mask.feather,
            { actions.onChange(mask.copy(feather = it)) },
            range = 0f..MAX_FEATHER,
            defaultValue = 0f,
            step = 0.1f,
            decimals = 1,
            unit = "px",
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AeButton("Invert", actions.onInvert, style = AeButtonStyle.Secondary)
            AeButton("Load as Selection", actions.onLoadSelection, style = AeButtonStyle.Secondary)
            AeButton(if (mask.enabled) "Disable" else "Enable", actions.onToggle, style = AeButtonStyle.Ghost)
            actions.onApply?.let { AeButton("Apply", it, style = AeButtonStyle.Ghost) }
            AeButton("Delete", actions.onDelete, style = AeButtonStyle.Ghost, icon = AeIcons.Delete)
        }
    }
}

/** Properties for a layer without a mask (Layer ▸ Layer Mask has the Hide variants). */
@Composable
fun AddMaskButton(onAdd: () -> Unit, modifier: Modifier = Modifier) {
    AeButton(
        "Add Layer Mask",
        onAdd,
        modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
        style = AeButtonStyle.Secondary,
        icon = AeIcons.Mask,
    )
}

private const val MAX_FEATHER = 1000f
