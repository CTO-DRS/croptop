package app.croptop.feature.editor.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import app.croptop.core.designsystem.component.AeCheckbox
import app.croptop.core.designsystem.component.AeColorPicker
import app.croptop.core.designsystem.component.AeDialog
import app.croptop.core.designsystem.component.AeDropdown
import app.croptop.core.designsystem.component.AeGradientEditor
import app.croptop.core.designsystem.component.AeSlider
import app.croptop.core.designsystem.component.AeText
import app.croptop.core.engine.api.PatternInfo
import app.croptop.core.model.FillKind
import app.croptop.core.model.FillLayerInfo
import app.croptop.core.model.GradientShape
import app.croptop.core.model.LayerId

@Composable
fun FillLayerDialog(
    layerId: LayerId?,
    initial: FillLayerInfo,
    patterns: List<PatternInfo> = emptyList(),
    patternThumbnail: (String, Int) -> app.croptop.core.engine.api.LayerThumb? = { _, _ -> null },
    onApply: (LayerId?, FillLayerInfo) -> Boolean,
    onDismiss: () -> Unit,
) {
    var draft by remember(layerId, initial) { mutableStateOf(initial) }
    var failure by remember(layerId) { mutableStateOf<String?>(null) }
    val kindName = when (draft.kind) {
        FillKind.SOLID -> "Solid Color"
        FillKind.GRADIENT -> "Gradient"
        FillKind.PATTERN -> "Pattern"
    }
    val title = if (layerId == null) "New $kindName Fill Layer" else "Edit $kindName Fill"
    val hasSelectedPattern = draft.patternId?.let { id -> patterns.any { it.id == id } } == true
    val canApply = draft.kind != FillKind.PATTERN || hasSelectedPattern

    fun apply() {
        if (!canApply) {
            failure = "Choose a pattern before applying this fill layer"
        } else if (onApply(layerId, draft)) {
            onDismiss()
        } else {
            failure = "Fill layer could not be applied"
        }
    }

    AeDialog(
        title = title,
        body = "Choose the fill content and settings. Apply creates one undoable layer change.",
        onDismiss = onDismiss,
        onConfirm = ::apply,
        width = 520.dp,
        actions = {
            AeButton("Cancel", onDismiss)
            AeButton("Apply", ::apply, style = AeButtonStyle.Primary, enabled = canApply)
        },
    ) {
        Column(
            Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AeDropdown(
                FillKind.entries,
                draft.kind,
                { kind ->
                    val patternId = draft.patternId?.takeIf { id -> patterns.any { it.id == id } }
                    draft = draft.copy(kind = kind, patternId = if (kind == FillKind.PATTERN) patternId else draft.patternId)
                    failure = null
                },
                { kind ->
                    when (kind) {
                        FillKind.SOLID -> "Solid Color"
                        FillKind.GRADIENT -> "Gradient"
                        FillKind.PATTERN -> "Pattern"
                    }
                },
            )
            if (draft.kind == FillKind.SOLID) {
                AeColorPicker(
                    draft.color,
                    {
                        draft = draft.copy(color = it)
                        failure = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else if (draft.kind == FillKind.GRADIENT) {
                AeGradientEditor(
                    draft.gradient,
                    {
                        draft = draft.copy(gradient = it)
                        failure = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                AeDropdown(
                    GradientShape.entries,
                    draft.shape,
                    { draft = draft.copy(shape = it) },
                    { it.name.lowercase().replaceFirstChar(Char::uppercase) },
                )
                AeSlider(
                    "Angle",
                    draft.angle,
                    { draft = draft.copy(angle = it) },
                    range = 0f..360f,
                    decimals = 0,
                    unit = "°",
                )
                AeSlider(
                    "Scale",
                    draft.scale * 100f,
                    { draft = draft.copy(scale = it / 100f) },
                    range = 10f..150f,
                    decimals = 0,
                    unit = "%",
                )
                AeCheckbox(draft.reverse, { draft = draft.copy(reverse = it) }, "Reverse")
            } else {
                PatternPicker(patterns, draft.patternId, { draft = draft.copy(patternId = it) }, patternThumbnail)
            }
            failure?.let { AeText(it, color = app.croptop.core.designsystem.theme.Ae.colors.danger) }
        }
    }
}
