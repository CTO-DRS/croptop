package app.croptop.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.component.AeButton
import app.croptop.core.designsystem.component.AeButtonStyle
import app.croptop.core.designsystem.component.AeDialog
import app.croptop.core.designsystem.component.AeDropdown
import app.croptop.core.designsystem.component.AeIconButton
import app.croptop.core.designsystem.component.AeNumberField
import app.croptop.core.designsystem.component.AeSegmented
import app.croptop.core.designsystem.component.AeText
import app.croptop.core.designsystem.icon.AeIcons
import app.croptop.core.designsystem.theme.Ae
import app.croptop.core.model.BackgroundContents
import app.croptop.core.model.ColorDepth
import app.croptop.core.model.ColorProfileRef
import app.croptop.core.model.DocSize
import app.croptop.core.model.MAX_DIMENSION
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.SizeUnit
import app.croptop.core.model.WorkingSpace
import app.croptop.core.model.fromPixels
import app.croptop.core.model.toPixels
import kotlin.math.roundToInt

private val BackgroundContents.label: String
    get() = when (this) {
        BackgroundContents.WHITE -> "White"
        BackgroundContents.BLACK -> "Black"
        BackgroundContents.TRANSPARENT -> "Transparent"
        BackgroundContents.BACKGROUND_COLOR -> "Background color"
    }

/**
 * New Document. Presets on top, then name, size, resolution, depth, profile,
 * background. 16/32-bit carry a PRO badge but stay selectable (Pro preview).
 */
@Composable
fun NewDocumentDialog(
    onCreate: (NewDocumentSpec) -> Unit,
    onCancel: () -> Unit,
    initial: NewDocumentSpec = DefaultPresets.first().spec,
    savedPresets: List<NewDocumentSpec> = emptyList(),
    onSavePreset: ((NewDocumentSpec) -> Unit)? = null,
) {
    var name by rememberSaveable { mutableStateOf("Untitled") }
    var widthPx by rememberSaveable { mutableStateOf(initial.size.width) }
    var heightPx by rememberSaveable { mutableStateOf(initial.size.height) }
    var ppi by rememberSaveable { mutableFloatStateOf(initial.resolutionPpi) }
    var unit by rememberSaveable { mutableStateOf(SizeUnit.PX) }
    var depth by rememberSaveable { mutableStateOf(initial.depth) }
    var space by rememberSaveable { mutableStateOf((initial.profile as? ColorProfileRef.BuiltIn)?.space ?: WorkingSpace.DISPLAY_P3) }
    var background by rememberSaveable { mutableStateOf(initial.background) }
    var linked by rememberSaveable { mutableStateOf(false) }

    fun spec() = NewDocumentSpec(
        name = name.trim().ifEmpty { "Untitled" },
        size = DocSize(widthPx, heightPx),
        resolutionPpi = ppi,
        depth = depth,
        profile = ColorProfileRef.BuiltIn(space),
        background = background,
    )
    val decimals = if (unit == SizeUnit.PX) 0 else 2
    val c = Ae.colors

    AeDialog(
        title = "New Document",
        onDismiss = onCancel,
        onConfirm = { onCreate(spec()) },
        width = 520.dp,
        actions = {
            if (onSavePreset != null) {
                AeButton("Save Preset", { onSavePreset(spec()) }, style = AeButtonStyle.Ghost)
                Spacer(Modifier.weight(1f))
            }
            AeButton("Cancel", onCancel)
            AeButton("Create", { onCreate(spec()) }, style = AeButtonStyle.Primary)
        },
    ) {
        val all = DefaultPresets + savedPresets.map { DocumentPreset(it.name, "${it.size.width} × ${it.size.height}", it) }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            all.forEach { p ->
                val selected = widthPx == p.spec.size.width && heightPx == p.spec.size.height
                AeButton(
                    p.label,
                    onClick = {
                        widthPx = p.spec.size.width
                        heightPx = p.spec.size.height
                        ppi = p.spec.resolutionPpi
                        depth = p.spec.depth
                        background = p.spec.background
                        (p.spec.profile as? ColorProfileRef.BuiltIn)?.let { space = it.space }
                        if (p !in DefaultPresets) name = p.spec.name
                    },
                    style = if (selected) AeButtonStyle.Secondary else AeButtonStyle.Ghost,
                )
            }
        }
        Field("Name") {
            BasicTextField(
                value = name,
                onValueChange = { name = it.take(120) },
                singleLine = true,
                textStyle = Ae.type.body.copy(color = c.textPrimary),
                cursorBrush = SolidColor(c.accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Ae.density.controlHeight)
                    .background(c.surface3, Ae.shapes.control)
                    .border(1.dp, c.lineHairline, Ae.shapes.control)
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .semantics { contentDescription = "Document name" },
            )
        }
        Field("Size") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AeNumberField(
                    fromPixels(widthPx, unit, ppi),
                    { v ->
                        val w = toPixels(v, unit, ppi)
                        if (linked) heightPx = (heightPx.toLong() * w / widthPx).toInt().coerceIn(1, MAX_DIMENSION)
                        widthPx = w
                    },
                    width = 96.dp,
                    decimals = decimals,
                    contentDescription = "Width",
                )
                AeIconButton(
                    AeIcons.Link,
                    if (linked) "Unlink width and height" else "Link width and height",
                    { linked = !linked },
                    tint = if (linked) c.accent else c.textTertiary,
                )
                AeNumberField(
                    fromPixels(heightPx, unit, ppi),
                    { v ->
                        val h = toPixels(v, unit, ppi)
                        if (linked) widthPx = (widthPx.toLong() * h / heightPx).toInt().coerceIn(1, MAX_DIMENSION)
                        heightPx = h
                    },
                    width = 96.dp,
                    decimals = decimals,
                    contentDescription = "Height",
                )
                AeDropdown(SizeUnit.Absolute, unit, { unit = it }, { it.label })
            }
        }
        Field("Resolution") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AeNumberField(ppi, { ppi = it }, range = 1f..3000f, width = 96.dp, unit = "ppi", contentDescription = "Resolution")
            }
        }
        Field("Bit depth") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AeSegmented(ColorDepth.entries, depth, { depth = it }, { it.label })
            }
        }
        Field("Color profile") {
            AeDropdown(WorkingSpace.entries, space, { space = it }, { it.label })
        }
        Field("Background") {
            AeSegmented(
                listOf(BackgroundContents.WHITE, BackgroundContents.BLACK, BackgroundContents.TRANSPARENT),
                background,
                { background = it },
                { it.label },
            )
        }
        val mp = widthPx.toLong() * heightPx / 1_000_000f
        AeText("$widthPx × $heightPx px · ${"%.1f".format(mp)} MP", style = Ae.type.caption, color = c.textSecondary)
    }
}

@Composable
private fun Field(label: String, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        AeText(label, color = Ae.colors.textSecondary, modifier = Modifier.width(112.dp))
        Column(Modifier.weight(1f)) { content() }
    }
}
