package app.croptop.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.theme.Ae
import app.croptop.core.model.BlendIfRange
import kotlin.math.abs

/** The four Blend If handles of one slider, left to right. */
enum class BlendIfThumb { BLACK_LO, BLACK_HI, WHITE_LO, WHITE_HI }

object BlendIfSliderMath {
    fun valueOf(range: BlendIfRange, thumb: BlendIfThumb): Int = when (thumb) {
        BlendIfThumb.BLACK_LO -> range.blackLo
        BlendIfThumb.BLACK_HI -> range.blackHi
        BlendIfThumb.WHITE_LO -> range.whiteLo
        BlendIfThumb.WHITE_HI -> range.whiteHi
    }

    /** The thumb to grab for a press at [value]; among equally close ones the outer half of a pair wins on the dragged side. */
    fun nearest(range: BlendIfRange, value: Int): BlendIfThumb {
        val candidates = BlendIfThumb.entries
        val best = candidates.minOf { abs(valueOf(range, it) - value) }
        val tied = candidates.filter { abs(valueOf(range, it) - value) == best }
        if (tied.size == 1) return tied.single()
        // Stacked thumbs: pick the half that can move toward the press.
        val first = tied.first()
        return if (value < valueOf(range, first)) first else tied.last()
    }

    /**
     * Moves [thumb] to [target]. A pair that is not split moves together unless [split] (Alt) is held; a split thumb only
     * travels between its neighbours so the order black.lo ≤ black.hi ≤ white.lo ≤ white.hi always holds.
     */
    fun move(range: BlendIfRange, thumb: BlendIfThumb, target: Int, split: Boolean): BlendIfRange {
        val v = target.coerceIn(0, 255)
        return when (thumb) {
            BlendIfThumb.BLACK_LO, BlendIfThumb.BLACK_HI -> {
                if (range.blackLo == range.blackHi && !split) {
                    val moved = v.coerceAtMost(range.whiteLo)
                    range.copy(blackLo = moved, blackHi = moved)
                } else if (thumb == BlendIfThumb.BLACK_LO) {
                    range.copy(blackLo = v.coerceAtMost(range.blackHi))
                } else {
                    range.copy(blackHi = v.coerceIn(range.blackLo, range.whiteLo))
                }
            }

            BlendIfThumb.WHITE_LO, BlendIfThumb.WHITE_HI -> {
                if (range.whiteLo == range.whiteHi && !split) {
                    val moved = v.coerceAtLeast(range.blackHi)
                    range.copy(whiteLo = moved, whiteHi = moved)
                } else if (thumb == BlendIfThumb.WHITE_HI) {
                    range.copy(whiteHi = v.coerceAtLeast(range.whiteLo))
                } else {
                    range.copy(whiteLo = v.coerceIn(range.blackHi, range.whiteHi))
                }
            }
        }
    }

    /** Sets one boundary from a number field; the other values stay and the result is clamped into a valid range. */
    fun set(range: BlendIfRange, thumb: BlendIfThumb, value: Int): BlendIfRange = move(range, thumb, value, split = true)
}

/**
 * Blend If gray slider (Photoshop Layer Style ▸ Blending Options): drag a triangle, hold Alt to split a pair into a soft ramp,
 * or type exact values. Four number fields mirror the handles so every value is reachable by keyboard and screen reader.
 */
@Composable
fun AeBlendIfSlider(
    label: String,
    range: BlendIfRange,
    onChange: (BlendIfRange) -> Unit,
    modifier: Modifier = Modifier,
    onChangeFinished: () -> Unit = {},
) {
    val c = Ae.colors
    val current by rememberUpdatedState(range)
    val change by rememberUpdatedState(onChange)
    val finished by rememberUpdatedState(onChangeFinished)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AeText(label, style = Ae.type.label, color = c.textSecondary)
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .semantics { contentDescription = "$label: ${range.blackLo}/${range.blackHi} to ${range.whiteLo}/${range.whiteHi}" }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        fun valueAt(x: Float) = (x / size.width * 255f).toInt().coerceIn(0, 255)
                        val thumb = BlendIfSliderMath.nearest(current, valueAt(down.position.x))
                        while (true) {
                            val event = awaitPointerEvent()
                            val change0 = event.changes.firstOrNull() ?: break
                            if (!change0.pressed) break
                            change(BlendIfSliderMath.move(current, thumb, valueAt(change0.position.x), event.keyboardModifiers.isAltPressed))
                            change0.consume()
                        }
                        finished()
                    }
                },
        ) {
            val trackTop = 4.dp.toPx()
            val trackHeight = 12.dp.toPx()
            drawRect(
                Brush.horizontalGradient(listOf(Color.Black, Color.White)),
                topLeft = Offset(0f, trackTop),
                size = androidx.compose.ui.geometry.Size(size.width, trackHeight),
            )
            for (thumb in BlendIfThumb.entries) {
                val x = BlendIfSliderMath.valueOf(range, thumb) / 255f * size.width
                val half = 6.dp.toPx()
                // Lower halves of a split pair point left, upper halves right, so a split reads as a wedge.
                val lean = if (thumb == BlendIfThumb.BLACK_LO || thumb == BlendIfThumb.WHITE_LO) -half / 2 else half / 2
                val path = Path().apply {
                    moveTo(x, trackTop + trackHeight)
                    lineTo(x - half + lean, trackTop + trackHeight + 14.dp.toPx())
                    lineTo(x + half + lean, trackTop + trackHeight + 14.dp.toPx())
                    close()
                }
                drawPath(path, if (thumb == BlendIfThumb.BLACK_LO || thumb == BlendIfThumb.BLACK_HI) c.textSecondary else c.textPrimary)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            for ((thumb, name) in listOf(
                BlendIfThumb.BLACK_LO to "Black start",
                BlendIfThumb.BLACK_HI to "Black end",
                BlendIfThumb.WHITE_LO to "White start",
                BlendIfThumb.WHITE_HI to "White end",
            )) {
                AeNumberField(
                    BlendIfSliderMath.valueOf(range, thumb).toFloat(),
                    { onChange(BlendIfSliderMath.set(range, thumb, it.toInt())) },
                    range = 0f..255f,
                    width = 52.dp,
                    contentDescription = "$label $name",
                    onCommit = onChangeFinished,
                )
            }
        }
    }
}
