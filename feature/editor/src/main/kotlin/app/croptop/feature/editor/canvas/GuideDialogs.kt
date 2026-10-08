package app.croptop.feature.editor.canvas

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.component.AeButton
import app.croptop.core.designsystem.component.AeButtonStyle
import app.croptop.core.designsystem.component.AeCheckbox
import app.croptop.core.designsystem.component.AeDialog
import app.croptop.core.designsystem.component.AeNumberField
import app.croptop.core.designsystem.component.AeSegmented
import app.croptop.core.designsystem.component.AeText
import app.croptop.core.designsystem.theme.Ae
import app.croptop.core.model.DocSize
import app.croptop.core.model.GridSettings
import app.croptop.core.model.Guide
import app.croptop.core.model.GuideAxis
import app.croptop.core.model.GuideLayout

@Composable
private fun Field(label: String, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        AeText(label, color = Ae.colors.textSecondary, modifier = Modifier.weight(1f))
        content()
    }
}

/** View ▸ New Guide… */
@Composable
fun NewGuideDialog(doc: DocSize, onAdd: (Guide) -> Unit, onDismiss: () -> Unit) {
    var axis by remember { mutableStateOf(GuideAxis.VERTICAL) }
    var pos by remember { mutableFloatStateOf(doc.width / 2f) }
    val max = if (axis == GuideAxis.VERTICAL) doc.width.toFloat() else doc.height.toFloat()
    val add = { onAdd(Guide(axis, pos.coerceIn(0f, max))) }
    AeDialog(
        "New Guide",
        onDismiss = onDismiss,
        onConfirm = add,
        width = 360.dp,
        actions = {
            AeButton("Cancel", onDismiss)
            AeButton("Add Guide", add, style = AeButtonStyle.Primary)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Orientation") {
                AeSegmented(GuideAxis.entries, axis, { a ->
                    axis = a
                    pos = (if (a == GuideAxis.VERTICAL) doc.width else doc.height) / 2f
                }, { if (it == GuideAxis.VERTICAL) "Vertical" else "Horizontal" })
            }
            Field("Position") {
                AeNumberField(pos, { pos = it }, range = 0f..max, decimals = 1, unit = "px", width = 96.dp, contentDescription = "Guide position")
            }
        }
    }
}

/** View ▸ New Guide Layout… */
@Composable
fun GuideLayoutDialog(onApply: (GuideLayout) -> Unit, onDismiss: () -> Unit) {
    var columns by remember { mutableIntStateOf(3) }
    var colGutter by remember { mutableFloatStateOf(20f) }
    var rows by remember { mutableIntStateOf(0) }
    var rowGutter by remember { mutableFloatStateOf(20f) }
    var margin by remember { mutableFloatStateOf(0f) }
    var clear by remember { mutableStateOf(true) }
    val apply = { onApply(GuideLayout(columns, colGutter, rows, rowGutter, margin, clear)) }
    AeDialog(
        "New Guide Layout",
        onDismiss = onDismiss,
        onConfirm = apply,
        width = 380.dp,
        actions = {
            AeButton("Cancel", onDismiss)
            AeButton("Add Guides", apply, style = AeButtonStyle.Primary, enabled = columns > 0 || rows > 0)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Columns") {
                AeNumberField(columns.toFloat(), { columns = it.toInt() }, range = 0f..48f, width = 96.dp, contentDescription = "Columns")
            }
            Field("Column gutter") {
                AeNumberField(colGutter, { colGutter = it }, range = 0f..10000f, unit = "px", width = 96.dp, contentDescription = "Column gutter")
            }
            Field("Rows") { AeNumberField(rows.toFloat(), { rows = it.toInt() }, range = 0f..48f, width = 96.dp, contentDescription = "Rows") }
            Field("Row gutter") {
                AeNumberField(rowGutter, { rowGutter = it }, range = 0f..10000f, unit = "px", width = 96.dp, contentDescription = "Row gutter")
            }
            Field("Margin") { AeNumberField(margin, { margin = it }, range = 0f..10000f, unit = "px", width = 96.dp, contentDescription = "Margin") }
            AeCheckbox(clear, { clear = it }, "Clear existing guides")
        }
    }
}

/** Grid spacing and subdivisions (Photoshop keeps these in Preferences; here they sit next to the toggle). */
@Composable
fun GridSettingsDialog(current: GridSettings, onApply: (GridSettings) -> Unit, onDismiss: () -> Unit) {
    var spacing by remember { mutableFloatStateOf(current.spacing) }
    var subdivisions by remember { mutableIntStateOf(current.subdivisions) }
    val apply = { onApply(GridSettings(spacing.coerceAtLeast(1f), subdivisions.coerceIn(1, 16))) }
    AeDialog(
        "Grid",
        onDismiss = onDismiss,
        onConfirm = apply,
        width = 360.dp,
        actions = {
            AeButton("Cancel", onDismiss)
            AeButton("Apply", apply, style = AeButtonStyle.Primary)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Gridline every") {
                AeNumberField(spacing, { spacing = it }, range = 1f..10000f, unit = "px", width = 96.dp, contentDescription = "Gridline spacing")
            }
            Field("Subdivisions") {
                AeNumberField(subdivisions.toFloat(), {
                    subdivisions = it.toInt()
                }, range = 1f..16f, width = 96.dp, contentDescription = "Subdivisions")
            }
        }
    }
}
