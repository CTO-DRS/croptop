package app.croptop.feature.editor.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.croptop.core.designsystem.component.AeButton
import app.croptop.core.designsystem.component.AeButtonStyle
import app.croptop.core.designsystem.component.AeDialog
import app.croptop.core.designsystem.component.AeGradientEditor
import app.croptop.core.designsystem.component.AeText
import app.croptop.core.designsystem.theme.Ae
import app.croptop.core.model.ColorGradient
import app.croptop.core.model.GradientPreset

@Composable
fun GradientPresetDialog(
    initial: ColorGradient,
    onApply: (ColorGradient) -> Unit,
    onDismiss: () -> Unit,
    viewModel: GradientPresetsViewModel = hiltViewModel(),
    onCancel: () -> Unit = {},
    onActionCompleted: (String) -> Unit = {},
    onActionFailed: (String, String) -> Unit = { _, _ -> },
) {
    val customs by viewModel.customPresets.collectAsStateWithLifecycle()
    val presets = BuiltInGradientPresets + customs
    val presetListHeight = (presets.size.coerceAtMost(8) * 42).dp
    var gradient by remember { mutableStateOf(initial) }
    var selectedId by remember { mutableStateOf<Long?>(null) }
    var name by remember { mutableStateOf("My gradient") }
    var error by remember { mutableStateOf<String?>(null) }
    val selected = presets.firstOrNull { it.id == selectedId }
    val cancel = {
        onCancel()
        onDismiss()
    }
    AeDialog(
        title = "Gradient editor",
        body = "Drag a stop to move it. Click the strip to add a stop.",
        onDismiss = cancel,
        width = 640.dp,
        actions = {
            AeButton("Close", cancel, style = AeButtonStyle.Ghost)
            AeButton("Apply", {
                onApply(gradient)
                onDismiss()
            }, style = AeButtonStyle.Primary)
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AeGradientEditor(
                    gradient,
                    { gradient = it },
                    Modifier.fillMaxWidth(),
                    onValueChangeFinished = { onActionCompleted("Edit gradient stops") },
                )
                BasicTextField(
                    value = name,
                    onValueChange = {
                        name = it.take(60)
                        error = null
                    },
                    singleLine = true,
                    textStyle = Ae.type.body.copy(color = Ae.colors.textPrimary),
                    modifier = Modifier.fillMaxWidth().background(Ae.colors.surface1, Ae.shapes.control)
                        .border(1.dp, Ae.colors.lineStrong, Ae.shapes.control).padding(horizontal = 10.dp, vertical = 9.dp)
                        .semantics { contentDescription = "Gradient preset name" },
                    decorationBox = { inner ->
                        if (name.isEmpty()) AeText("Preset name", color = Ae.colors.textTertiary)
                        inner()
                    },
                )
                if (error != null) AeText(error!!, color = Ae.colors.danger)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AeButton("Save as new", {
                        val cleanName = name.trim()
                        if (cleanName.isBlank()) {
                            error = "Enter a preset name"
                        } else {
                            viewModel.save(
                                cleanName,
                                gradient,
                                onSaved = { saved ->
                                    selectedId = saved.id
                                    error = null
                                    onActionCompleted("Save gradient preset")
                                },
                                onFailure = { detail ->
                                    error = detail
                                    onActionFailed("Save gradient preset", detail)
                                },
                            )
                        }
                    }, style = AeButtonStyle.Secondary, enabled = name.isNotBlank())
                    AeButton(
                        "Update",
                        {
                            selected?.let { preset ->
                                viewModel.update(
                                    preset,
                                    name,
                                    gradient,
                                    onUpdated = {
                                        error = null
                                        onActionCompleted("Update gradient preset")
                                    },
                                    onFailure = { detail ->
                                        error = detail
                                        onActionFailed("Update gradient preset", detail)
                                    },
                                )
                            }
                        },
                        enabled = selected?.isBuiltIn == false && name.isNotBlank(),
                    )
                    AeButton(
                        "Delete",
                        {
                            selected?.let { preset ->
                                viewModel.delete(
                                    preset,
                                    onDeleted = {
                                        selectedId = null
                                        error = null
                                        onActionCompleted("Delete gradient preset")
                                    },
                                    onFailure = { detail ->
                                        error = detail
                                        onActionFailed("Delete gradient preset", detail)
                                    },
                                )
                            }
                        },
                        style = AeButtonStyle.Ghost,
                        enabled = selected?.isBuiltIn == false,
                    )
                }
            }
            Column(Modifier.weight(0.72f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AeText("Presets", style = Ae.type.label)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.height(presetListHeight)) {
                    items(presets, key = { it.id }) { preset ->
                        PresetRow(preset, selectedId == preset.id) {
                            selectedId = preset.id
                            name = preset.name
                            gradient = preset.gradient
                            onActionCompleted("Preview ${preset.name} gradient")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PresetRow(preset: GradientPreset, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(if (selected) Ae.colors.accent.copy(alpha = 0.18f) else Ae.colors.surface1, Ae.shapes.control)
            .clickable(onClick = onClick).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        androidx.compose.foundation.Canvas(Modifier.weight(1f).height(20.dp)) {
            val stops = preset.gradient.stops.sortedBy { it.position }
            drawRect(
                androidx.compose.ui.graphics.Brush.horizontalGradient(
                    *stops.map {
                        it.position to androidx.compose.ui.graphics.Color(it.color.r, it.color.g, it.color.b, it.color.a)
                    }.toTypedArray(),
                ),
            )
        }
        AeText(preset.name, style = Ae.type.caption, modifier = Modifier.weight(1.5f), maxLines = 1)
    }
}
