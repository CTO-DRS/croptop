package app.croptop.feature.editor.brush

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.croptop.core.designsystem.component.AeButton
import app.croptop.core.designsystem.component.AeButtonStyle
import app.croptop.core.designsystem.component.AeCheckbox
import app.croptop.core.designsystem.component.AeContextMenuArea
import app.croptop.core.designsystem.component.AeCurveEditor
import app.croptop.core.designsystem.component.AeDialog
import app.croptop.core.designsystem.component.AeDropdown
import app.croptop.core.designsystem.component.AeIconButton
import app.croptop.core.designsystem.component.AeInlineSlider
import app.croptop.core.designsystem.component.AeMenuEntry
import app.croptop.core.designsystem.component.AeSegmented
import app.croptop.core.designsystem.component.AeSlider
import app.croptop.core.designsystem.component.AeText
import app.croptop.core.designsystem.component.AeToggleIconButton
import app.croptop.core.designsystem.icon.AeIcons
import app.croptop.core.designsystem.theme.Ae
import app.croptop.core.model.BrushCategory
import app.croptop.core.model.BrushDabs
import app.croptop.core.model.BrushPreset
import app.croptop.core.model.BrushSettings
import app.croptop.core.model.Dynamics
import app.croptop.core.model.DynamicsInput
import app.croptop.core.model.SmoothingMode

/**
 * Live stroke preview from the reference dab generator (the engine uses the same rules). Scaled so
 * big brushes still fit; the size field says the real size.
 */
@Composable
fun BrushStrokePreview(settings: BrushSettings, modifier: Modifier = Modifier, color: Color = Ae.colors.textPrimary) {
    Canvas(modifier.semantics { contentDescription = "Brush stroke preview" }) {
        val maxD = (size.height * 0.6f).coerceAtLeast(2f)
        val k = (maxD / settings.size).coerceAtMost(1f)
        // Preview at a scaled size; spacing, scatter and jitter scale with it.
        val scaled = settings.copy(size = (settings.size * k).coerceAtLeast(1f))
        val dabs = BrushDabs.dabs(scaled, BrushDabs.previewStroke(size.width, size.height))
        dabs.forEach { d ->
            val r = d.diameter / 2f
            val hard = settings.hardness.coerceIn(0f, 0.999f)
            val c = color.copy(alpha = d.alpha)
            val brush = Brush.radialGradient(
                0f to c,
                hard to c,
                1f to c.copy(alpha = 0f),
                center = Offset(d.x, d.y),
                radius = r.coerceAtLeast(0.5f),
            )
            rotate(d.angle, Offset(d.x, d.y)) {
                scale(1f, d.roundness, Offset(d.x, d.y)) { drawCircle(brush, r, Offset(d.x, d.y)) }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AeText(title.uppercase(), style = Ae.type.panelHeader, color = Ae.colors.textTertiary)
        content()
    }
}

/** Brush Settings panel: tip, dynamics curves, scatter, smoothing; save as a preset. */
@Composable
fun BrushSettingsPanelContent(ui: BrushUi) {
    val s = ui.settings
    val set = ui.onSettings
    val preview = ui.onSettingsPreview
    var saveDialog by remember { mutableStateOf(false) }
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.fillMaxWidth().height(72.dp).background(Ae.colors.canvas, Ae.shapes.chip)) {
            BrushStrokePreview(s, Modifier.fillMaxWidth().height(72.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AeText(
                (ui.preset?.name ?: "Unsaved brush") + if (ui.modified && ui.preset != null) " (edited)" else "",
                style = Ae.type.label,
                modifier = Modifier.weight(1f),
            )
            if (ui.modified && ui.preset?.builtIn == false) AeButton("Update", ui.onUpdatePreset, style = AeButtonStyle.Ghost)
            AeButton("Save as New…", {
                saveDialog = true
                ui.onActionCompleted("Open Save Brush dialog")
            })
        }
        Section("Tip") {
            AeSlider("Size", s.size, {
                preview(s.copy(size = it))
            }, range = 1f..1000f, defaultValue = 45f, unit = "px", onValueChangeFinished = ui.onSettingsFinished)
            AeSlider("Hardness", s.hardness * 100f, {
                preview(s.copy(hardness = it / 100f))
            }, range = 0f..100f, defaultValue = 80f, unit = "%", onValueChangeFinished = ui.onSettingsFinished)
            AeSlider("Spacing", s.spacing * 100f, {
                preview(s.copy(spacing = it / 100f))
            }, range = 1f..300f, defaultValue = 25f, unit = "%", onValueChangeFinished = ui.onSettingsFinished)
            AeSlider("Angle", s.angle, {
                preview(s.copy(angle = it))
            }, range = -180f..180f, defaultValue = 0f, unit = "°", onValueChangeFinished = ui.onSettingsFinished)
            AeSlider("Roundness", s.roundness * 100f, {
                preview(s.copy(roundness = it / 100f))
            }, range = 1f..100f, defaultValue = 100f, unit = "%", onValueChangeFinished = ui.onSettingsFinished)
        }
        Section("Dynamics") {
            DynamicsRow("Size", s.sizeDynamics, onActionCompleted = ui.onActionCompleted, onChange = {
                set(s.copy(sizeDynamics = it))
            }, onPreview = { preview(s.copy(sizeDynamics = it)) }, onFinished = ui.onSettingsFinished)
            DynamicsRow("Opacity", s.opacityDynamics, onActionCompleted = ui.onActionCompleted, onChange = {
                set(s.copy(opacityDynamics = it))
            }, onPreview = { preview(s.copy(opacityDynamics = it)) }, onFinished = ui.onSettingsFinished)
            DynamicsRow("Flow", s.flowDynamics, onActionCompleted = ui.onActionCompleted, onChange = {
                set(s.copy(flowDynamics = it))
            }, onPreview = { preview(s.copy(flowDynamics = it)) }, onFinished = ui.onSettingsFinished)
            AeSlider("Size jitter", s.sizeJitter * 100f, {
                preview(s.copy(sizeJitter = it / 100f))
            }, range = 0f..100f, unit = "%", onValueChangeFinished = ui.onSettingsFinished)
            AeSlider("Angle jitter", s.angleJitter * 100f, {
                preview(s.copy(angleJitter = it / 100f))
            }, range = 0f..100f, unit = "%", onValueChangeFinished = ui.onSettingsFinished)
        }
        Section("Scatter") {
            AeSlider("Scatter", s.scatter * 100f, {
                preview(s.copy(scatter = it / 100f))
            }, range = 0f..1000f, unit = "%", onValueChangeFinished = ui.onSettingsFinished)
            AeSlider("Count", s.count.toFloat(), {
                preview(s.copy(count = it.toInt()))
            }, range = 1f..16f, defaultValue = 1f, onValueChangeFinished = ui.onSettingsFinished)
            AeCheckbox(s.scatterBothAxes, { set(s.copy(scatterBothAxes = it)) }, "Both axes")
        }
        Section("Smoothing") {
            AeSegmented(SmoothingMode.entries, s.smoothingMode, { set(s.copy(smoothingMode = it)) }, { it.label })
            AeSlider("Smoothing", s.smoothing * 100f, {
                preview(s.copy(smoothing = it / 100f))
            }, range = 0f..100f, defaultValue = 10f, unit = "%", onValueChangeFinished = ui.onSettingsFinished)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            AeCheckbox(s.buildUp, { set(s.copy(buildUp = it)) }, "Build-up")
            AeCheckbox(s.wetEdges, { set(s.copy(wetEdges = it)) }, "Wet edges")
        }
    }
    if (saveDialog) {
        NameDialog(
            title = "Save Brush",
            initial = ui.preset?.name?.let { "$it copy" } ?: "",
            confirm = "Save",
            onDone = {
                ui.onSaveNew(it)
                saveDialog = false
            },
            onCancel = {
                saveDialog = false
                ui.onActionCompleted("Cancel Save Brush")
            },
        )
    }
}

/** Input + minimum, and the response curve when an input is chosen. */
@Composable
private fun DynamicsRow(
    label: String,
    d: Dynamics,
    onActionCompleted: (String) -> Unit,
    onChange: (Dynamics) -> Unit,
    onPreview: (Dynamics) -> Unit,
    onFinished: () -> Unit,
) {
    var curve by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AeText(label, style = Ae.type.caption, color = Ae.colors.textSecondary, modifier = Modifier.width(56.dp))
            AeDropdown(DynamicsInput.entries, d.input, { onChange(d.copy(input = it)) }, { it.label }, Modifier.weight(1f))
            if (d.input != DynamicsInput.OFF) {
                AeToggleIconButton(AeIcons.Histogram, "Edit $label response curve", curve, {
                    curve = it
                    onActionCompleted("${if (it) "Open" else "Close"} $label response curve")
                })
            }
        }
        if (d.input != DynamicsInput.OFF) {
            AeSlider("Minimum", d.minimum * 100f, {
                onPreview(d.copy(minimum = it / 100f))
            }, range = 0f..100f, unit = "%", onValueChangeFinished = onFinished)
            if (curve) {
                AeCurveEditor(
                    d.curve,
                    { onPreview(d.copy(curve = it)) },
                    Modifier.fillMaxWidth().height(180.dp),
                    onValueChangeFinished = onFinished,
                )
            }
        }
    }
}

private enum class BrushFilter(val label: String) { ALL("All"), FAVORITES("Favorites"), MINE("Mine") }

/** Brushes panel: search, favorites, categories, a stroke thumbnail per preset; import/export. */
@Composable
fun BrushesPanelContent(ui: BrushUi, onChosen: () -> Unit = {}, maxListHeight: androidx.compose.ui.unit.Dp = 420.dp) {
    val c = Ae.colors
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(BrushFilter.ALL) }
    var renaming by remember { mutableStateOf<BrushPreset?>(null) }
    val shown = ui.presets.filter { p ->
        (query.isBlank() || p.name.contains(query.trim(), ignoreCase = true)) &&
            when (filter) {
                BrushFilter.ALL -> true
                BrushFilter.FAVORITES -> p.favorite
                BrushFilter.MINE -> !p.builtIn
            }
    }
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.fillMaxWidth().height(Ae.density.controlHeight).background(c.surface3, Ae.shapes.control)
                .border(1.dp, c.lineHairline, Ae.shapes.control).padding(horizontal = 8.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (query.isEmpty()) AeText("Search brushes", color = c.textTertiary)
            BasicTextField(
                query,
                { query = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { ui.onActionCompleted("Search brush presets") }),
                textStyle = Ae.type.body.copy(color = c.textPrimary),
                cursorBrush = SolidColor(c.accent),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search brushes" },
            )
        }
        AeSegmented(BrushFilter.entries, filter, {
            filter = it
            ui.onActionCompleted("Filter brushes: ${it.label}")
        }, { it.label })
        if (shown.isEmpty()) {
            AeText(
                when {
                    query.isNotBlank() -> "No brushes match “$query”."
                    filter == BrushFilter.FAVORITES -> "Star a brush to keep it here."
                    else -> "Brushes you save or import appear here."
                },
                style = Ae.type.caption,
                color = c.textTertiary,
                maxLines = 2,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            LazyColumn(Modifier.heightIn(max = maxListHeight)) {
                // Group by category, in the category order, when showing everything.
                val groups = shown.groupBy { it.category }.toSortedMap(compareBy(BrushCategory::ordinal))
                groups.forEach { (cat, list) ->
                    if (filter == BrushFilter.ALL && query.isBlank()) {
                        item(key = "h" + cat.name) {
                            AeText(
                                cat.label.uppercase(),
                                style = Ae.type.panelHeader,
                                color = c.textTertiary,
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                            )
                        }
                    }
                    items(list, key = { it.id }) { p ->
                        AeContextMenuArea(
                            entries = {
                                buildList {
                                    add(AeMenuEntry.Item(if (p.favorite) "Remove from Favorites" else "Add to Favorites", { ui.onFavorite(p) }))
                                    if (!p.builtIn) {
                                        add(
                                            AeMenuEntry.Item("Rename…", {
                                                renaming = p
                                                ui.onActionCompleted("Open Rename Brush")
                                            }),
                                        )
                                        add(AeMenuEntry.Divider)
                                        add(AeMenuEntry.Item("Delete Brush", { ui.onDelete(p) }, icon = AeIcons.Delete))
                                    }
                                }
                            },
                        ) {
                            BrushRow(p, selected = p.id == ui.presetId, onClick = {
                                ui.onSelect(p)
                                onChosen()
                            }, onFavorite = { ui.onFavorite(p) })
                        }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AeButton("Import…", ui.onImport, style = AeButtonStyle.Ghost)
            AeButton("Export…", ui.onExport, style = AeButtonStyle.Ghost)
        }
        ui.message?.let { AeText(it, style = Ae.type.caption, color = c.textSecondary, maxLines = 2) }
    }
    renaming?.let { p ->
        NameDialog("Rename Brush", p.name, "Rename", onDone = {
            ui.onRename(p, it)
            renaming = null
        }, onCancel = {
            renaming = null
            ui.onActionCompleted("Cancel Rename Brush")
        })
    }
}

@Composable
private fun BrushRow(p: BrushPreset, selected: Boolean, onClick: () -> Unit, onFavorite: () -> Unit) {
    val c = Ae.colors
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(if (selected) c.accentMuted else Color.Transparent, Ae.shapes.control)
            .clickable(onClick = onClick)
            .semantics { this.selected = selected }
            .padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BrushStrokePreview(p.settings, Modifier.width(72.dp).height(32.dp))
        AeText(p.name, modifier = Modifier.weight(1f))
        AeIconButton(
            if (p.favorite) AeIcons.StarFilled else AeIcons.Star,
            if (p.favorite) "Remove ${p.name} from favorites" else "Add ${p.name} to favorites",
            onFavorite,
            tint = if (p.favorite) c.warning else c.textTertiary,
        )
    }
}

/** Brush / Eraser options bar: preset, size, hardness, opacity, flow, smoothing. */
@Composable
fun BrushOptions(ui: BrushUi) {
    val s = ui.settings
    val preview = ui.onSettingsPreview
    var picker by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box {
            Row(
                Modifier
                    .height(Ae.density.controlHeight)
                    .background(Ae.colors.surface3, Ae.shapes.control)
                    .border(1.dp, if (picker) Ae.colors.accent else Ae.colors.lineHairline, Ae.shapes.control)
                    .clickable {
                        picker = true
                        ui.onActionCompleted("Open brush preset picker")
                    }
                    .semantics { contentDescription = "Brush preset, ${ui.preset?.name ?: "unsaved"}" }
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                BrushStrokePreview(s, Modifier.width(40.dp).height(20.dp))
                androidx.compose.material3.Icon(AeIcons.ChevronDown, null, tint = Ae.colors.textSecondary, modifier = Modifier.size(16.dp))
            }
            if (picker) {
                Popup(
                    offset = IntOffset(0, 40),
                    onDismissRequest = {
                        picker = false
                        ui.onActionCompleted("Close brush preset picker")
                    },
                    properties = PopupProperties(focusable = true),
                ) {
                    Box(
                        Modifier.width(300.dp).background(Ae.colors.surface2, Ae.shapes.popover)
                            .border(1.dp, Ae.colors.lineStrong, Ae.shapes.popover),
                    ) {
                        BrushesPanelContent(ui, onChosen = { picker = false }, maxListHeight = 360.dp)
                    }
                }
            }
        }
        val set = ui.onSettings
        AeInlineSlider("Size", s.size, {
            preview(s.copy(size = it))
        }, range = 1f..1000f, defaultValue = 45f, unit = "px", trackWidth = 96.dp, onValueChangeFinished = { ui.onSettingsFinished() }, curve = 2.5f)
        Percent("Hardness", s.hardness, 0.8f, 0f, onFinished = ui.onSettingsFinished) { preview(s.copy(hardness = it)) }
        Percent("Opacity", s.opacity, 1f, onFinished = ui.onSettingsFinished) { preview(s.copy(opacity = it)) }
        Percent("Flow", s.flow, 1f, onFinished = ui.onSettingsFinished) { preview(s.copy(flow = it)) }
        Percent("Smoothing", s.smoothing, 0.1f, 0f, onFinished = ui.onSettingsFinished) { preview(s.copy(smoothing = it)) }
        AeToggleIconButton(
            AeIcons.Pen,
            "Pen pressure controls brush size",
            s.sizeDynamics.input == DynamicsInput.PRESSURE,
            { enabled -> set(s.copy(sizeDynamics = s.sizeDynamics.copy(input = if (enabled) DynamicsInput.PRESSURE else DynamicsInput.OFF))) },
        )
        AeToggleIconButton(
            AeIcons.Pen,
            "Pen pressure controls opacity",
            s.opacityDynamics.input == DynamicsInput.PRESSURE,
            { enabled -> set(s.copy(opacityDynamics = s.opacityDynamics.copy(input = if (enabled) DynamicsInput.PRESSURE else DynamicsInput.OFF))) },
        )
    }
}

/** Options-bar slider for a 0–1 value shown as a percentage. */
@Composable
private fun Percent(
    label: String,
    value: Float,
    default: Float,
    min: Float = 0.01f,
    onFinished: () -> Unit = {},
    onChange: (Float) -> Unit,
) {
    AeInlineSlider(
        label,
        value * 100f,
        { onChange(it / 100f) },
        range = min * 100f..100f,
        defaultValue = default * 100f,
        unit = "%",
        trackWidth = 72.dp,
        onValueChangeFinished = { onFinished() },
    )
}

@Composable
private fun NameDialog(title: String, initial: String, confirm: String, onDone: (String) -> Unit, onCancel: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val valid = name.isNotBlank()
    fun done() = if (valid) onDone(name.trim()) else Unit
    val c = Ae.colors
    AeDialog(
        title = title,
        onDismiss = onCancel,
        onConfirm = ::done,
        width = 400.dp,
        actions = {
            AeButton("Cancel", onCancel)
            AeButton(confirm, ::done, style = AeButtonStyle.Primary, enabled = valid)
        },
    ) {
        BasicTextField(
            value = name,
            onValueChange = { name = it.take(60) },
            singleLine = true,
            textStyle = Ae.type.body.copy(color = c.textPrimary),
            cursorBrush = SolidColor(c.accent),
            modifier = Modifier
                .fillMaxWidth()
                .height(Ae.density.controlHeight)
                .background(c.surface3, Ae.shapes.control)
                .border(1.dp, c.accent, Ae.shapes.control)
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .focusRequester(focus)
                .semantics { contentDescription = "Brush name" },
        )
    }
}
