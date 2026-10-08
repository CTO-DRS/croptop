package app.croptop.feature.editor.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.component.AeButton
import app.croptop.core.designsystem.component.AeButtonStyle
import app.croptop.core.designsystem.component.AeCheckbox
import app.croptop.core.designsystem.component.AeDialog
import app.croptop.core.designsystem.component.AeDropdown
import app.croptop.core.designsystem.component.AeNumberField
import app.croptop.core.designsystem.component.AeText
import app.croptop.core.designsystem.component.AeToggleIconButton
import app.croptop.core.designsystem.icon.AeIcons
import app.croptop.core.designsystem.theme.Ae
import app.croptop.core.designsystem.theme.AeDensity
import app.croptop.core.model.Anchor
import app.croptop.core.model.BackgroundContents
import app.croptop.core.model.CanvasResize
import app.croptop.core.model.DocSize
import app.croptop.core.model.ImageSizing
import app.croptop.core.model.Resample
import app.croptop.core.model.SizeUnit
import app.croptop.core.model.fromPixels
import app.croptop.core.model.toPixels

private fun megabytes(bytes: Long) = if (bytes >= 1L shl 30) "%.1f GB".format(bytes / 1_073_741_824.0) else "%.1f MB".format(bytes / 1_048_576.0)

@Composable
private fun Field(label: String, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AeText(label, color = Ae.colors.textSecondary, modifier = Modifier.width(96.dp))
        content()
    }
}

/**
 * Image ▸ Image Size: width/height in px, %, in, cm or mm with a link toggle, resolution,
 * and resampling. With Resample off only the print size / resolution change (pixels stay).
 */
@Composable
fun ImageSizeDialog(
    size: DocSize,
    ppi: Float,
    bytesPerChannel: Int,
    onApply: (ImageSizing, Resample) -> Unit,
    onDismiss: () -> Unit,
    onActionCompleted: (String) -> Unit = {},
) {
    var s by remember { mutableStateOf(ImageSizing(size, ppi)) }
    var unit by remember { mutableStateOf(SizeUnit.PX) }
    var method by remember { mutableStateOf(Resample.AUTOMATIC) }
    val decimals = if (unit == SizeUnit.PX) 0 else 2
    val apply = { if (s.changed) onApply(s, method) else onDismiss() }
    AeDialog(
        "Image Size",
        onDismiss = onDismiss,
        onConfirm = apply,
        width = 460.dp,
        actions = {
            AeButton("Reset", {
                s = s.reset()
                onActionCompleted("Reset image size")
            }, style = AeButtonStyle.Ghost, enabled = s.changed)
            Box(Modifier.weight(1f))
            AeButton("Cancel", onDismiss)
            AeButton("OK", apply, style = AeButtonStyle.Primary)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AeText(
                "${size.width} × ${size.height} px (${megabytes(ImageSizing(size, ppi).estimatedBytes(bytesPerChannel))})" +
                    if (s.size != size) "  →  ${s.size.width} × ${s.size.height} px (${megabytes(s.estimatedBytes(bytesPerChannel))})" else "",
                style = Ae.type.caption,
                color = Ae.colors.textSecondary,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field("Width") {
                        AeNumberField(
                            fromPixels(s.size.width, unit, s.ppi, size.width),
                            { s = s.withWidth(it, unit) },
                            decimals = decimals,
                            width = 96.dp,
                            enabled = s.resample || unit !in listOf(SizeUnit.PX, SizeUnit.PERCENT),
                            contentDescription = "Width",
                            onCommit = { onActionCompleted("Set image width") },
                        )
                        AeDropdown(SizeUnit.entries, unit, {
                            unit = it
                            onActionCompleted("Set image size unit to ${it.label}")
                        }, { it.label })
                    }
                    Field("Height") {
                        AeNumberField(
                            fromPixels(s.size.height, unit, s.ppi, size.height),
                            { s = s.withHeight(it, unit) },
                            decimals = decimals,
                            width = 96.dp,
                            enabled = s.resample || unit !in listOf(SizeUnit.PX, SizeUnit.PERCENT),
                            contentDescription = "Height",
                            onCommit = { onActionCompleted("Set image height") },
                        )
                    }
                }
                AeToggleIconButton(AeIcons.Link, "Keep proportions", checked = s.linked, onCheckedChange = {
                    s = s.copy(linked = it)
                    onActionCompleted(if (it) "Keep image proportions" else "Unlock image proportions")
                })
            }
            Field("Resolution") {
                AeNumberField(s.ppi, {
                    s = s.withPpi(it)
                }, range = 1f..30_000f, decimals = 0, unit = "ppi", width = 96.dp, contentDescription = "Resolution", onCommit = {
                    onActionCompleted("Set image resolution")
                })
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AeCheckbox(s.resample, {
                    s = s.copy(resample = it, size = if (it) s.size else size)
                    onActionCompleted(if (it) "Enable image resampling" else "Disable image resampling")
                }, "Resample")
                AeDropdown(Resample.entries, method, {
                    method = it
                    onActionCompleted("Set resampling method to ${it.label}")
                }, { it.label }, enabled = s.resample)
            }
        }
    }
}

/** Image ▸ Canvas Size: absolute or relative size, anchor grid, fill for the new area. */
@Composable
fun CanvasSizeDialog(
    size: DocSize,
    ppi: Float,
    onApply: (CanvasResize, BackgroundContents) -> Unit,
    onDismiss: () -> Unit,
    onActionCompleted: (String) -> Unit = {},
) {
    var unit by remember { mutableStateOf(SizeUnit.PX) }
    var relative by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf(size) }
    var anchor by remember { mutableStateOf(Anchor.CENTER) }
    var fill by remember { mutableStateOf(BackgroundContents.BACKGROUND_COLOR) }
    val resize = CanvasResize(size, target, anchor)
    val decimals = if (unit == SizeUnit.PX) 0 else 2
    val apply = { if (target != size) onApply(resize, fill) else onDismiss() }

    // In relative mode the fields show the change (Δ), not the final size.
    fun shown(px: Int, base: Int) = if (relative && unit != SizeUnit.PERCENT) fromPixels(px - base, unit, ppi) else fromPixels(px, unit, ppi, base)

    fun parse(v: Float, base: Int): Int = if (relative && unit != SizeUnit.PERCENT) {
        (base + if (unit == SizeUnit.PX) v.toInt() else toPixels(kotlin.math.abs(v), unit, ppi) * (if (v < 0) -1 else 1)).coerceAtLeast(1)
    } else {
        toPixels(v, unit, ppi, base)
    }
    AeDialog(
        "Canvas Size",
        onDismiss = onDismiss,
        onConfirm = apply,
        width = 460.dp,
        actions = {
            AeButton("Cancel", onDismiss)
            AeButton("OK", apply, style = AeButtonStyle.Primary)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AeText("Current: ${size.width} × ${size.height} px", style = Ae.type.caption, color = Ae.colors.textSecondary)
            Field("Width") {
                AeNumberField(shown(target.width, size.width), {
                    target = target.copy(width = parse(it, size.width))
                }, decimals = decimals, width = 96.dp, contentDescription = "Canvas width")
                AeDropdown(SizeUnit.entries, unit, {
                    unit = it
                    onActionCompleted("Set canvas size unit to ${it.label}")
                }, { it.label })
            }
            Field("Height") {
                AeNumberField(shown(target.height, size.height), {
                    target = target.copy(height = parse(it, size.height))
                }, decimals = decimals, width = 96.dp, contentDescription = "Canvas height")
            }
            AeCheckbox(relative, {
                relative = it
                onActionCompleted(if (it) "Enable relative canvas size" else "Disable relative canvas size")
            }, "Relative (enter how much to add)")
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                AeText("Anchor", color = Ae.colors.textSecondary, modifier = Modifier.width(96.dp))
                AnchorGrid(anchor) {
                    anchor = it
                    onActionCompleted("Set canvas anchor to ${it.name.lowercase().replace('_', ' ')}")
                }
            }
            Field("Extend with") {
                AeDropdown(
                    listOf(BackgroundContents.BACKGROUND_COLOR, BackgroundContents.WHITE, BackgroundContents.BLACK, BackgroundContents.TRANSPARENT),
                    fill,
                    {
                        fill = it
                        onActionCompleted(
                            "Set canvas extension fill to ${when (it) {
                                BackgroundContents.BACKGROUND_COLOR -> "background color"
                                BackgroundContents.WHITE -> "white"
                                BackgroundContents.BLACK -> "black"
                                BackgroundContents.TRANSPARENT -> "transparent"
                            }}",
                        )
                    },
                    {
                        when (it) {
                            BackgroundContents.BACKGROUND_COLOR -> "Background color"
                            BackgroundContents.WHITE -> "White"
                            BackgroundContents.BLACK -> "Black"
                            BackgroundContents.TRANSPARENT -> "Transparent"
                        }
                    },
                )
            }
            AeText(
                "New: ${target.width} × ${target.height} px" + if (resize.clips) " — the image will be clipped" else "",
                style = Ae.type.caption,
                color = if (resize.clips) Ae.colors.warning else Ae.colors.textSecondary,
            )
        }
    }
}

/** 3×3 anchor picker; arrow keys are not needed, every cell is a labelled button. */
@Composable
fun AnchorGrid(selected: Anchor, onSelect: (Anchor) -> Unit) {
    val c = Ae.colors
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Anchor.entries.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { a ->
                    val on = a == selected
                    Box(
                        Modifier
                            .sizeIn(
                                minWidth = if (Ae.density == AeDensity.Touch) 40.dp else 24.dp,
                                minHeight = if (Ae.density == AeDensity.Touch) 40.dp else 24.dp,
                            )
                            .clickable { onSelect(a) }
                            .semantics {
                                contentDescription = "Anchor ${a.name.lowercase().replace('_', ' ')}"
                                this.selected = on
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .size(24.dp)
                                .background(if (on) c.accent else c.surface3, Ae.shapes.chip)
                                .border(1.dp, c.lineStrong, Ae.shapes.chip),
                        )
                    }
                }
            }
        }
    }
}

/** Select ▸ Modify ▸ Expand/Contract/Border/Smooth/Feather: one pixel amount. */
@Composable
fun ModifySelectionDialog(
    kind: app.croptop.core.model.SelectionModify,
    onApply: (Float) -> Unit,
    onDismiss: () -> Unit,
    onActionCompleted: (String) -> Unit = {},
    /** Shows the result of [px] on the canvas without a history step (live preview); null when previews are unsupported. */
    onPreview: ((Float) -> Unit)? = null,
    /** Discards the preview and restores the original selection. */
    onCancelPreview: () -> Unit = {},
) {
    var px by remember { mutableStateOf(if (kind == app.croptop.core.model.SelectionModify.FEATHER) 10f else 5f) }
    val latestPreview by androidx.compose.runtime.rememberUpdatedState(onPreview)
    // Slider bursts collapse into one engine preview once the value settles.
    androidx.compose.runtime.LaunchedEffect(px) {
        kotlinx.coroutines.delay(120)
        latestPreview?.invoke(px)
    }
    val cancel = {
        onCancelPreview()
        onDismiss()
    }
    val label = when (kind) {
        app.croptop.core.model.SelectionModify.EXPAND -> "Expand By"
        app.croptop.core.model.SelectionModify.CONTRACT -> "Contract By"
        app.croptop.core.model.SelectionModify.BORDER -> "Width"
        app.croptop.core.model.SelectionModify.SMOOTH -> "Sample Radius"
        app.croptop.core.model.SelectionModify.FEATHER -> "Feather Radius"
    }
    val apply = { onApply(px) }
    AeDialog(
        title = "${kind.label} Selection",
        onDismiss = cancel,
        onConfirm = apply,
        width = 360.dp,
        actions = {
            AeButton("Cancel", cancel)
            AeButton("OK", apply, style = AeButtonStyle.Primary)
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            app.croptop.core.designsystem.component.AeBarSlider(
                label,
                px,
                { px = it },
                range = 1f..kind.maxPx,
                defaultValue = 1f,
                decimals = if (kind == app.croptop.core.model.SelectionModify.FEATHER) 1 else 0,
                unit = "px",
                width = 260.dp,
                curve = 3f,
                contentDescription = label,
                onValueChangeFinished = { onActionCompleted("Set ${kind.label.lowercase()} amount") },
            )
        }
    }
}
