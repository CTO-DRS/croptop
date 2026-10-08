package app.croptop.feature.editor.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import app.croptop.core.designsystem.component.AeDropdown
import app.croptop.core.designsystem.component.AeInlineSlider
import app.croptop.core.designsystem.component.AeSwitch
import app.croptop.core.designsystem.component.AeText
import app.croptop.core.designsystem.theme.Ae
import app.croptop.core.model.AdjustmentInfo
import app.croptop.core.model.AdjustmentSpec
import app.croptop.core.model.BlendMode
import app.croptop.core.model.ParamValue
import app.croptop.feature.editor.properties.AdjustmentProperties

@Composable
fun SmartFilterDialog(
    spec: AdjustmentSpec,
    initial: AdjustmentInfo,
    onApply: (AdjustmentInfo) -> Boolean,
    onDismiss: () -> Unit,
) {
    var values by remember(spec.id, initial) { mutableStateOf(spec.normalize(initial.values)) }
    var enabled by remember(spec.id, initial) { mutableStateOf(initial.enabled) }
    var blendMode by remember(spec.id, initial) { mutableStateOf(initial.blendMode) }
    var opacity by remember(spec.id, initial) { mutableStateOf(initial.opacity * 100f) }
    var failure by remember(spec.id, initial) { mutableStateOf<String?>(null) }

    fun apply() {
        val updated = initial.copy(
            values = values,
            enabled = enabled,
            blendMode = blendMode,
            opacity = opacity / 100f,
        )
        if (onApply(updated)) {
            onDismiss()
        } else {
            failure = "Smart filter changes could not be applied"
        }
    }

    AeDialog(
        title = "Edit ${spec.name} Smart Filter",
        body = "Adjust this filter's settings. Applying updates the smart object in one history step.",
        onDismiss = onDismiss,
        onConfirm = ::apply,
        width = 440.dp,
        actions = {
            AeButton("Cancel", onDismiss)
            AeButton("Apply", ::apply, style = AeButtonStyle.Primary)
        },
    ) {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                AeText("Enabled")
                AeSwitch(
                    checked = enabled,
                    onCheckedChange = { enabled = it },
                    contentDescription = "Enable ${spec.name} smart filter",
                )
            }
            AeDropdown(
                options = BlendMode.entries.filterNot { it.groupOnly },
                selected = blendMode,
                onSelect = { blendMode = it },
                label = { it.displayName },
                modifier = Modifier.fillMaxWidth(),
            )
            AeInlineSlider(
                label = "Filter Opacity",
                value = opacity,
                onValueChange = { opacity = it },
                range = 0f..100f,
                defaultValue = 100f,
                unit = "%",
                modifier = Modifier.fillMaxWidth(),
                contentDescription = "${spec.name} smart filter opacity",
            )
            AdjustmentProperties(
                spec = spec,
                values = values,
                onChange = { next, _ ->
                    values = spec.normalize(next)
                    failure = null
                },
            )
            failure?.let { AeText(it, color = Ae.colors.danger) }
        }
    }
}
