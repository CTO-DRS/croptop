package app.croptop.core.designsystem.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.component.AeButton
import app.croptop.core.designsystem.component.AeButtonStyle
import app.croptop.core.designsystem.component.AeCheckbox
import app.croptop.core.designsystem.component.AeColorPicker
import app.croptop.core.designsystem.component.AeContextMenuArea
import app.croptop.core.designsystem.component.AeCurveEditor
import app.croptop.core.designsystem.component.AeDropdown
import app.croptop.core.designsystem.component.AeGradientEditor
import app.croptop.core.designsystem.component.AeIconButton
import app.croptop.core.designsystem.component.AeKbd
import app.croptop.core.designsystem.component.AeLevelsEditor
import app.croptop.core.designsystem.component.AeMenuEntry
import app.croptop.core.designsystem.component.AeNumberField
import app.croptop.core.designsystem.component.AePanel
import app.croptop.core.designsystem.component.AeSegmented
import app.croptop.core.designsystem.component.AeSlider
import app.croptop.core.designsystem.component.AeSwitch
import app.croptop.core.designsystem.component.AeText
import app.croptop.core.designsystem.component.AeToggleIconButton
import app.croptop.core.designsystem.component.AeTooltipBubble
import app.croptop.core.designsystem.component.ChannelCurves
import app.croptop.core.designsystem.icon.AeIcons
import app.croptop.core.designsystem.theme.Ae
import app.croptop.core.designsystem.theme.AeColors

/** Debug-only component catalog: every component and state, tokens and icon sheet. */
@Composable
fun AeCatalog(modifier: Modifier = Modifier, scrollable: Boolean = true) {
    val c = Ae.colors
    Column(
        modifier
            .fillMaxSize()
            .background(c.app)
            .let { if (scrollable) it.verticalScroll(rememberScrollState()) else it }
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        AeText("CropTop design system", style = Ae.type.display)
        CatalogSection("Color tokens") { ColorTokens(c) }
        CatalogSection("Type") { TypeScale() }
        CatalogSection("Buttons") { Buttons() }
        CatalogSection("Icon buttons & tools") { IconButtons() }
        CatalogSection("Inputs") { Inputs() }
        CatalogSection("Menus, tooltips, shortcuts") { Overlays() }
        CatalogSection("Adjustment editors") { AdjustmentEditors() }
        CatalogSection("Panel") {
            AePanel("Layers", Modifier.width(280.dp), actions = { AeIconButton(AeIcons.Add, "New layer", {}) }) {
                AeText("Panel content", modifier = Modifier.padding(8.dp), color = c.textSecondary)
            }
        }
        CatalogSection("Icons (${AeIcons.all.size})") { IconSheet() }
    }
}

@Composable
private fun CatalogSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AeText(title.uppercase(), style = Ae.type.panelHeader, color = Ae.colors.textSecondary)
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorTokens(c: AeColors) {
    val tokens = listOf(
        "canvas" to c.canvas, "app" to c.app, "surface1" to c.surface1, "surface2" to c.surface2, "surface3" to c.surface3,
        "surface4" to c.surface4, "textPrimary" to c.textPrimary, "textSecondary" to c.textSecondary, "textTertiary" to c.textTertiary,
        "accent" to c.accent, "danger" to c.danger, "warning" to c.warning, "success" to c.success,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tokens.forEach { (name, color) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(56.dp, 36.dp).background(color, Ae.shapes.control).border(1.dp, c.lineStrong, Ae.shapes.control))
                AeText(name, style = Ae.type.micro, color = c.textSecondary)
            }
        }
    }
}

@Composable
private fun TypeScale() {
    val t = Ae.type
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AeText("Display 28 — CropTop", style = t.display)
        AeText("Title 17 — Layer properties", style = t.title)
        AeText("Body 13 — The canvas is sacred. 0123456789", style = t.body)
        AeText("Label 12 — Opacity 100%", style = t.label)
        AeText("Caption 11 — 6000 × 4000 · 16-bit", style = t.caption, color = Ae.colors.textSecondary)
        AeText("MICRO 10 — PRO", style = t.micro)
        AeText("Mono 12 — #4C8DFF", style = t.mono)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Buttons() {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AeButton("Export", {}, style = AeButtonStyle.Primary, icon = AeIcons.Export)
        AeButton("Cancel", {})
        AeButton("Reset", {}, style = AeButtonStyle.Ghost)
        AeButton("Delete layer", {}, style = AeButtonStyle.Danger, icon = AeIcons.Delete)
        AeButton("Disabled", {}, enabled = false)
        AeButton("Disabled", {}, style = AeButtonStyle.Primary, enabled = false)
    }
}

@Composable
private fun IconButtons() {
    var tool by remember { mutableStateOf(1) }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        AeIconButton(AeIcons.Undo, "Undo", {}, shortcut = "Ctrl+Z")
        AeIconButton(AeIcons.Redo, "Redo", {}, shortcut = "Ctrl+Shift+Z")
        AeIconButton(AeIcons.Delete, "Delete", {}, enabled = false)
        Box(Modifier.width(12.dp))
        listOf(AeIcons.Move to "V", AeIcons.Brush to "B", AeIcons.Eraser to "E", AeIcons.Crop to "C").forEachIndexed { i, (icon, key) ->
            AeToggleIconButton(icon, "Tool $key", checked = tool == i, onCheckedChange = { tool = i }, shortcut = key)
        }
    }
}

@Composable
private fun Inputs() {
    var slider by remember { mutableFloatStateOf(60f) }
    var bipolar by remember { mutableFloatStateOf(-0.35f) }
    var num by remember { mutableFloatStateOf(4000f) }
    var mode by remember { mutableStateOf("Pointer") }
    var check by remember { mutableStateOf(true) }
    var sw by remember { mutableStateOf(true) }
    var drop by remember { mutableStateOf("Bicubic") }
    Column(Modifier.width(320.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AeSlider("Flow", slider, { slider = it }, defaultValue = 100f, unit = "%")
        AeSlider("Exposure", bipolar, { bipolar = it }, range = -5f..5f, defaultValue = 0f, step = 0.01f, decimals = 2)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AeNumberField(num, { num = it }, range = 1f..300000f, unit = "px", width = 96.dp)
            AeDropdown(listOf("Nearest", "Bilinear", "Bicubic", "Lanczos3"), drop, { drop = it }, { it })
        }
        AeSegmented(listOf("Pointer", "Touch"), mode, { mode = it }, { it })
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            AeCheckbox(check, { check = it }, "Sample all layers")
            AeCheckbox(false, {}, "Disabled", enabled = false)
            AeSwitch(sw, { sw = it }, contentDescription = "Snap")
        }
    }
}

@Composable
private fun Overlays() {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        AeTooltipBubble("Brush", "B")
        AeTooltipBubble("Stamp visible", "Ctrl+Alt+Shift+E")
        AeKbd("Ctrl+K")
        AeContextMenuArea(
            entries = {
                listOf(
                    AeMenuEntry.Header("Layer"),
                    AeMenuEntry.Item("Duplicate", {}, "Ctrl+J", AeIcons.Duplicate),
                    AeMenuEntry.Item("Convert to Smart Object", {}),
                    AeMenuEntry.Divider,
                    AeMenuEntry.Item("Delete", {}, "Del", AeIcons.Delete),
                )
            },
        ) {
            AeText(
                "Right-click me",
                modifier = Modifier.border(1.dp, Ae.colors.lineStrong, Ae.shapes.control).padding(8.dp),
                color = Ae.colors.textSecondary,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IconSheet() {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AeIcons.all.forEach { (name, icon) ->
            Column(
                Modifier.width(72.dp).background(Ae.colors.surface1, Ae.shapes.control).padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(icon, name, tint = Ae.colors.textPrimary, modifier = Modifier.size(20.dp))
                AeText(name, style = Ae.type.micro, color = Ae.colors.textTertiary)
            }
        }
    }
}

@Composable
private fun AdjustmentEditors() {
    var curves by remember {
        mutableStateOf(
            ChannelCurves(
                rgb = app.croptop.core.model.Curve.Identity.withPoint(app.croptop.core.model.CurvePoint(0.25f, 0.18f))
                    .first.withPoint(app.croptop.core.model.CurvePoint(0.75f, 0.84f)).first,
                red = app.croptop.core.model.Curve.Identity.withPoint(app.croptop.core.model.CurvePoint(0.45f, 0.65f)).first,
            ),
        )
    }
    var levels by remember { mutableStateOf(app.croptop.core.model.Levels(inBlack = 0.06f, inWhite = 0.94f, gamma = 1.15f)) }
    var gradient by remember {
        mutableStateOf(
            app.croptop.core.model.ColorGradient(
                listOf(
                    app.croptop.core.model.GradientStop(0f, app.croptop.core.model.RgbaColor(0.1f, 0.1f, 0.35f)),
                    app.croptop.core.model.GradientStop(0.55f, app.croptop.core.model.RgbaColor(0.9f, 0.35f, 0.3f)),
                    app.croptop.core.model.GradientStop(1f, app.croptop.core.model.RgbaColor(1f, 0.9f, 0.6f)),
                ),
            ),
        )
    }
    val hist = FloatArray(256) { i -> (kotlin.math.exp(-((i - 90) * (i - 90)) / 1800f) + 0.6f * kotlin.math.exp(-((i - 190) * (i - 190)) / 900f)) }
    var color by remember { mutableStateOf(app.croptop.core.model.RgbaColor.parseHex("#4C8DFF")!!) }
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        AeCurveEditor(curves, { curves = it }, Modifier.width(240.dp), histogram = hist)
        AeLevelsEditor(levels, { levels = it }, Modifier.width(260.dp), histogram = hist)
        Column(Modifier.width(260.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            AeGradientEditor(gradient, { gradient = it })
            AeColorPicker(color, { color = it })
        }
    }
    var hueSat by remember {
        mutableStateOf(
            app.croptop.core.model.HueSaturation().withShift(
                app.croptop.core.model.HueChannel.MASTER,
                app.croptop.core.model.HueShift(hue = 25f, saturation = 15f),
            ),
        )
    }
    var balance by remember {
        mutableStateOf(app.croptop.core.model.ColorBalance(midtones = app.croptop.core.model.ColorBalanceShift(cyanRed = 18f, yellowBlue = -12f)))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        app.croptop.core.designsystem.component.AeHueSaturationEditor(hueSat, { hueSat = it }, Modifier.width(280.dp))
        app.croptop.core.designsystem.component.AeColorBalanceEditor(balance, { balance = it }, Modifier.width(280.dp))
        app.croptop.core.designsystem.component.AeHueRangeBar(
            app.croptop.core.model.HueChannel.REDS.defaultRange,
            hueShift = 40f,
            onRangeChange = {},
            modifier = Modifier.width(260.dp),
        )
    }
}
