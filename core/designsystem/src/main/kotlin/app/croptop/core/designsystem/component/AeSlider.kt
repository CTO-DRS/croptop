package app.croptop.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.theme.Ae

/** Drag speed multipliers for scrubby controls: Shift = fine, Alt = coarse. */
internal fun scrubMultiplier(shift: Boolean, alt: Boolean): Float = when {
    shift -> 0.1f
    alt -> 10f
    else -> 1f
}

/**
 * Property slider: a value bar ([AeBarSlider]) with the label and number on it. Drag to scrub, tap to type, double-tap to reset to
 * [defaultValue]. With [trackBrush] (a colour range) the bar carries that gradient as a strip and a marker.
 */
@Composable
fun AeSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    range: ClosedFloatingPointRange<Float> = 0f..100f,
    defaultValue: Float = range.start,
    step: Float = 1f,
    decimals: Int = 0,
    unit: String? = null,
    onValueChangeFinished: () -> Unit = {},
    /** Color track (e.g. Cyan→Red, a hue spectrum); replaces the accent fill. */
    trackBrush: Brush? = null,
) {
    AeBarSlider(
        label, value, onValueChange, modifier, range = range, defaultValue = defaultValue, step = step, decimals = decimals, unit = unit,
        trackBrush = trackBrush, onValueChangeFinished = onValueChangeFinished,
    )
}

/** Room a bar needs beside the old track width for its label and number. */
private val BAR_LABEL_ROOM = 70.dp

/**
 * Compact one-row slider for toolbars/options bars: `Label [value unit] ━━●━━`.
 * Same interactions as [AeSlider]: scrubby label (Shift fine, Alt coarse), double-click resets.
 */
@Composable
fun AeInlineSlider(
    label: String?,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    range: ClosedFloatingPointRange<Float> = 0f..100f,
    defaultValue: Float = range.start,
    step: Float = 1f,
    decimals: Int = 0,
    unit: String? = null,
    trackWidth: androidx.compose.ui.unit.Dp = 120.dp,
    showValueField: Boolean = true,
    showTrack: Boolean = true,
    contentDescription: String? = label,
    onValueChangeFinished: ((Float) -> Unit)? = null,
    /** Passed to the value bar: above 1 gives the small end of a wide range (brush sizes) more of the track. */
    curve: Float = 1f,
) {
    // A labelled slider with a number is the value bar (sized by its track width, or by the caller when there is no track); the bare variants keep the compact form.
    if (label != null && showValueField) {
        var latest by remember { mutableFloatStateOf(value) }
        AeBarSlider(
            label, value, { latest = it; onValueChange(it) }, modifier, range = range, defaultValue = defaultValue, step = step, decimals = decimals,
            unit = unit, width = if (showTrack) trackWidth + BAR_LABEL_ROOM else null, curve = curve, contentDescription = contentDescription ?: label,
            onValueChangeFinished = { onValueChangeFinished?.invoke(latest) },
        )
        return
    }
    val current by rememberUpdatedState(value)
    val finishedCallback by rememberUpdatedState(onValueChangeFinished)
    var latestSent by remember { mutableFloatStateOf(value) }
    val span = range.endInclusive - range.start
    val c = Ae.colors

    fun set(v: Float) {
        val next = v.coerceIn(range)
        latestSent = next
        onValueChange(next)
    }

    fun finish() {
        finishedCallback?.invoke(latestSent)
    }

    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (label != null) {
            AeText(
                label,
                style = Ae.type.label,
                color = c.textSecondary,
                modifier = Modifier
                    .pointerHoverIcon(PointerIcon(android.view.PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW))
                    .pointerInput(range) {
                        detectTapGestures(
                            onDoubleTap = {
                                set(defaultValue)
                                finish()
                            },
                        )
                    }
                    .pointerInput(range, step) {
                        awaitEachGesture {
                            var last = awaitFirstDown().position.x
                            var acc = current
                            var changed = false
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull() ?: break
                                if (!ch.pressed) break
                                val dx = ch.position.x - last
                                last = ch.position.x
                                if (dx != 0f) {
                                    acc += dx * step * scrubMultiplier(ev.keyboardModifiers.isShiftPressed, ev.keyboardModifiers.isAltPressed)
                                    set(acc)
                                    changed = true
                                    ch.consume()
                                }
                            }
                            if (changed) finish()
                        }
                    },
            )
        }
        if (showValueField) {
            AeNumberField(
                value,
                ::set,
                range = range,
                step = step,
                decimals = decimals,
                unit = unit,
                contentDescription = contentDescription,
                onCommit = ::finish,
            )
        }
        if (showTrack) {
            Box(
                Modifier
                    .width(trackWidth)
                    .height(Ae.density.controlHeight)
                    .semantics {
                        if (contentDescription != null) this.contentDescription = contentDescription
                        progressBarRangeInfo = ProgressBarRangeInfo(value, range)
                        setProgress {
                            set(it)
                            finish()
                            true
                        }
                    }
                    .pointerInput(range) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            set(range.start + span * (down.position.x / size.width))
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull() ?: break
                                if (!ch.pressed) break
                                set(range.start + span * (ch.position.x / size.width).coerceIn(0f, 1f))
                                ch.consume()
                            }
                            finish()
                        }
                    },
            ) {
                val fraction = if (span == 0f) 0f else ((value - range.start) / span).coerceIn(0f, 1f)
                val track = c.surface4
                val fill = c.accent
                Canvas(Modifier.width(trackWidth).height(Ae.density.controlHeight)) {
                    val h = 3.dp.toPx()
                    val y = size.height / 2
                    val r = 4.dp.toPx()
                    val w = size.width - 2 * r
                    drawRect(track, Offset(r, y - h / 2), Size(w, h))
                    drawRect(fill, Offset(r, y - h / 2), Size(w * fraction, h))
                    drawFaderThumb(r + w * fraction, y, c, highlighted = false, height = minOf(16.dp.toPx(), size.height - 2.dp.toPx()))
                }
            }
        }
    }
}
