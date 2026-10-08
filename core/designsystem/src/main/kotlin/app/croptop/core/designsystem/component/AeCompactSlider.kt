package app.croptop.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.croptop.core.designsystem.icon.AeIcons
import app.croptop.core.designsystem.theme.Ae
import app.croptop.core.designsystem.theme.AeColors

/**
 * True inside a tool options bar: value bars there take Photoshop's options-bar form, `Size: [30 px ▾]` — the label is a scrubby
 * slider (drag it sideways), the box takes typing, and the arrow opens the full bar in a popover.
 */
val LocalAeCompactSliders = staticCompositionLocalOf { false }

/**
 * The one slider handle used everywhere: a slim square-edged fader cap standing across the track, outlined so it reads on any
 * fill, with an accent notch; [highlighted] adds a focus halo.
 */
internal fun DrawScope.drawFaderThumb(cx: Float, cy: Float, colors: AeColors, highlighted: Boolean, height: Float = 18.dp.toPx()) {
    val w = 6.dp.toPx()
    val topLeft = Offset(cx - w / 2, cy - height / 2)
    if (highlighted) {
        val pad = 3.dp.toPx()
        drawRect(colors.accent.copy(alpha = 0.3f), Offset(topLeft.x - pad, topLeft.y - pad), Size(w + 2 * pad, height + 2 * pad))
    }
    val edge = 1.dp.toPx()
    drawRect(Color.Black.copy(alpha = 0.55f), Offset(topLeft.x - edge, topLeft.y - edge), Size(w + 2 * edge, height + 2 * edge))
    drawRect(colors.textPrimary, topLeft, Size(w, height))
    val notch = height * 0.55f
    drawRect(colors.accent, Offset(cx - 0.75.dp.toPx(), cy - notch / 2), Size(1.5.dp.toPx(), notch))
}

@Composable
internal fun AeCompactSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier,
    range: ClosedFloatingPointRange<Float>,
    defaultValue: Float,
    step: Float,
    decimals: Int,
    unit: String?,
    enabled: Boolean,
    curve: Float,
    trackBrush: Brush?,
    contentDescription: String,
    onValueChangeFinished: () -> Unit,
) {
    val c = Ae.colors
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onValueChange)
    val finished by rememberUpdatedState(onValueChangeFinished)
    var open by remember { mutableStateOf(false) }
    val span = range.endInclusive - range.start
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        // Scrubby label: drag sideways to change the value (the whole range over ~240 dp), double-tap to reset.
        Row(
            if (!enabled) Modifier else Modifier
                .pointerInput(range) {
                    var acc = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { acc = current },
                        onDragEnd = { finished() },
                        onDragCancel = { finished() },
                    ) { ch, dx ->
                        ch.consume()
                        acc = (acc + dx / 240.dp.toPx() * span).coerceIn(range)
                        val snapped = if (step > 0f) (Math.round((acc - range.start) / step) * step + range.start).coerceIn(range) else acc
                        change(snapped)
                    }
                }
                .pointerInput(defaultValue) {
                    detectTapGestures(onDoubleTap = {
                        change(defaultValue)
                        finished()
                    })
                },
        ) {
            // The label stays exactly the setting's name (for reading aloud and finding it); the colon is separate.
            val tone = if (enabled) c.textSecondary else c.textTertiary
            AeText(label, style = Ae.type.label, color = tone)
            AeText(":", style = Ae.type.label, color = tone)
        }
        Box {
            Row(
                Modifier.background(c.surface3, Ae.shapes.control).border(1.dp, if (open) c.accent else c.lineHairline, Ae.shapes.control),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AeNumberField(
                    value,
                    { change(it) },
                    range = range,
                    step = step,
                    decimals = decimals,
                    unit = unit,
                    width = 64.dp,
                    enabled = enabled,
                    contentDescription = contentDescription,
                    onCommit = { finished() },
                )
                AeIconButton(AeIcons.ChevronDown, "Show $label slider", { open = !open }, enabled = enabled)
            }
            if (open) {
                Popup(
                    offset = IntOffset(0, 36),
                    onDismissRequest = { open = false },
                    properties = PopupProperties(focusable = true),
                ) {
                    Box(
                        Modifier.width(240.dp).background(c.surface2, Ae.shapes.popover)
                            .border(1.dp, c.lineStrong, Ae.shapes.popover).padding(8.dp),
                    ) {
                        CompositionLocalProvider(LocalAeCompactSliders provides false) {
                            AeBarSlider(
                                label, value, change, range = range, defaultValue = defaultValue, step = step, decimals = decimals, unit = unit,
                                enabled = enabled, curve = curve, trackBrush = trackBrush, contentDescription = contentDescription,
                                onValueChangeFinished = { finished() },
                            )
                        }
                    }
                }
            }
        }
    }
}
