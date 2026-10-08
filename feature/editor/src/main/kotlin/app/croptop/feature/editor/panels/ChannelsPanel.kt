package app.croptop.feature.editor.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.component.AeButton
import app.croptop.core.designsystem.component.AeButtonStyle
import app.croptop.core.designsystem.component.AeColorPicker
import app.croptop.core.designsystem.component.AeColorWell
import app.croptop.core.designsystem.component.AeDialog
import app.croptop.core.designsystem.component.AeEmptyState
import app.croptop.core.designsystem.component.AeIconButton
import app.croptop.core.designsystem.component.AeMenu
import app.croptop.core.designsystem.component.AeMenuEntry
import app.croptop.core.designsystem.component.AeSlider
import app.croptop.core.designsystem.component.AeText
import app.croptop.core.designsystem.icon.AeIcons
import app.croptop.core.engine.api.SelectionChannel
import app.croptop.core.model.RgbaColor
import app.croptop.core.model.SelectionOp

/** Saved selection masks (alpha channels), backed by the current document's channel list. */
@Composable
fun ChannelsPanelContent(
    channels: List<SelectionChannel>,
    hasSelection: Boolean,
    onSave: () -> Boolean,
    onLoad: (String?, SelectionOp) -> Boolean,
    onDelete: (String) -> Boolean,
    quickMaskOverlayColor: RgbaColor = RgbaColor(1f, 0f, 0f, 0.5f),
    onQuickMaskOverlayColorChange: (RgbaColor) -> Boolean = { false },
    onQuickMaskOverlayColorEditComplete: () -> Unit = {},
) {
    var operation by remember { mutableStateOf(SelectionOp.REPLACE) }
    var operationMenu by remember { mutableStateOf(false) }
    var editingQuickMaskColor by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AeColorWell(
                quickMaskOverlayColor,
                "Quick Mask overlay color",
                onClick = { editingQuickMaskColor = true },
            )
            Column(Modifier.weight(1f)) {
                AeText("Quick Mask overlay")
                AeText("Choose the tint and opacity shown while Quick Mask is active.")
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AeButton(
                "Save Selection",
                onClick = { onSave() },
                modifier = Modifier.weight(1f),
                enabled = hasSelection,
                icon = AeIcons.Save,
            )
            AeIconButton(AeIcons.More, "Selection load operation: ${operation.label}", onClick = { operationMenu = true })
            AeMenu(
                expanded = operationMenu,
                onDismiss = { operationMenu = false },
                entries = SelectionOp.entries.map { op ->
                    AeMenuEntry.Item(op.label, {
                        operation = op
                        operationMenu = false
                    }, checked = operation == op)
                },
            )
        }
        AeText("Load operation: ${operation.label}", modifier = Modifier.padding(horizontal = 12.dp))
        if (channels.isEmpty()) {
            AeEmptyState(
                "No saved selections",
                body = "Save an active selection to keep it as an alpha channel.",
                icon = AeIcons.Channels,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            channels.forEach { channel ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                        AeText(channel.name)
                        val bounds = channel.bounds
                        AeText(
                            if (bounds == null || bounds.isEmpty) {
                                "Empty channel"
                            } else {
                                "${bounds.width} × ${bounds.height} px · (${bounds.left}, ${bounds.top})"
                            },
                        )
                    }
                    AeButton("Load", { onLoad(channel.name, operation) }, enabled = channel.bounds?.isEmpty == false)
                    AeIconButton(AeIcons.Delete, "Delete ${channel.name}", onClick = { onDelete(channel.name) })
                }
            }
        }
    }
    if (editingQuickMaskColor) {
        AeDialog(
            title = "Quick Mask Overlay Color",
            onDismiss = { editingQuickMaskColor = false },
            width = 520.dp,
            actions = {
                AeButton("Done", { editingQuickMaskColor = false }, style = AeButtonStyle.Primary)
            },
        ) {
            AeColorPicker(
                color = quickMaskOverlayColor,
                onColorChange = { color -> onQuickMaskOverlayColorChange(color) },
                onEditComplete = onQuickMaskOverlayColorEditComplete,
            )
            AeSlider(
                label = "Opacity",
                value = quickMaskOverlayColor.a * 100f,
                onValueChange = { opacity ->
                    onQuickMaskOverlayColorChange(quickMaskOverlayColor.copy(a = opacity / 100f))
                },
                range = 0f..100f,
                defaultValue = 50f,
                decimals = 0,
                unit = "%",
                onValueChangeFinished = onQuickMaskOverlayColorEditComplete,
            )
        }
    }
}

private val SelectionOp.label: String
    get() = when (this) {
        SelectionOp.REPLACE -> "Replace"
        SelectionOp.ADD -> "Add"
        SelectionOp.SUBTRACT -> "Subtract"
        SelectionOp.INTERSECT -> "Intersect"
    }
