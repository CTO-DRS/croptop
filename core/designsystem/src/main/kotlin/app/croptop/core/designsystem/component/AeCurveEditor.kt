package app.croptop.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.theme.Ae
import app.croptop.core.model.Curve
import app.croptop.core.model.CurvePoint
import kotlin.math.hypot
import kotlin.math.roundToInt

private enum class CurveEditMode(val label: String) { POINTS("Points"), FREEHAND("Freehand") }

/** Converts a pointer stroke into a bounded, ordered set of curve points. */
internal fun curveFromFreehand(current: Curve, stroke: List<CurvePoint>, bins: Int = 64): Curve {
    require(bins >= 2)
    val captured = mutableMapOf<Int, Float>()
    stroke.filter { it.x.isFinite() && it.y.isFinite() }.forEach { point ->
        captured[(point.x.coerceIn(0f, 1f) * bins).roundToInt().coerceIn(0, bins)] = point.y.coerceIn(0f, 1f)
    }
    if (captured.isEmpty()) return current

    val samples = captured.toSortedMap().toList()
    val first = samples.first().first
    val last = samples.last().first
    val result = (0..bins).map { index ->
        val y = captured[index] ?: if (index !in first..last) {
            current.evaluate(index / bins.toFloat())
        } else {
            val lower = samples.lastOrNull { it.first < index }
            val upper = samples.firstOrNull { it.first > index }
            when {
                lower == null -> upper?.second ?: current.evaluate(index / bins.toFloat())

                upper == null -> lower.second

                else -> {
                    val fraction = (index - lower.first).toFloat() / (upper.first - lower.first)
                    lower.second + (upper.second - lower.second) * fraction
                }
            }
        }
        CurvePoint(index / bins.toFloat(), y)
    }
    return Curve(result)
}

@Composable
private fun CurveChannel.displayColor(): Color = when (this) {
    CurveChannel.RGB -> Ae.colors.textPrimary
    CurveChannel.RED -> Color(0xFFE14B4B)
    CurveChannel.GREEN -> Color(0xFF3DAA68)
    CurveChannel.BLUE -> Color(0xFF4D8FEA)
}

/** Channel-aware editor; each channel retains its own curve while sharing the same editing tools. */
@Composable
fun AeCurveEditor(
    curves: ChannelCurves,
    onCurvesChange: (ChannelCurves) -> Unit,
    modifier: Modifier = Modifier,
    histogram: FloatArray? = null,
    onValueChangeFinished: () -> Unit = {},
) {
    var channel by remember { mutableStateOf(CurveChannel.RGB) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AeSegmented(CurveChannel.entries, channel, { channel = it }, CurveChannel::label, Modifier.fillMaxWidth())
        key(channel) {
            AeCurveEditor(
                curve = curves[channel],
                onCurveChange = { onCurvesChange(curves.withCurve(channel, it)) },
                modifier = Modifier.fillMaxWidth(),
                histogram = histogram,
                curveColor = channel.displayColor(),
                onValueChangeFinished = onValueChangeFinished,
            )
        }
    }
}

/** Draws a 256-bin histogram (any scale) as a filled silhouette. */
internal fun DrawScope.histogramBackdrop(histogram: FloatArray?, color: Color) {
    if (histogram == null || histogram.isEmpty()) return
    val max = histogram.max().takeIf { it > 0f } ?: return
    val path = Path().apply {
        moveTo(0f, size.height)
        histogram.forEachIndexed { i, v -> lineTo(i / (histogram.size - 1f) * size.width, size.height - v / max * size.height) }
        lineTo(size.width, size.height)
        close()
    }
    drawPath(path, color)
}

/**
 * Curves editor: click to add or drag to move a point, drag an interior
 * point off the graph or press Delete to remove it, and use arrows (Shift ×10) to nudge. Tab and
 * Shift+Tab select points; Freehand draws a bounded resampled curve.
 */
@Composable
fun AeCurveEditor(
    curve: Curve,
    onCurveChange: (Curve) -> Unit,
    modifier: Modifier = Modifier,
    histogram: FloatArray? = null,
    curveColor: Color = Ae.colors.textPrimary,
    onValueChangeFinished: () -> Unit = {},
    onTabExit: (() -> Unit)? = null,
) {
    var selected by remember { mutableIntStateOf(-1) }
    var mode by remember { mutableStateOf(CurveEditMode.POINTS) }
    val current by rememberUpdatedState(curve)
    val change by rememberUpdatedState(onCurveChange)
    val c = Ae.colors
    val selectedPoint = curve.points.getOrNull(selected)
    val graphDescription = buildString {
        append("Curve editor graph with ${curve.points.size} points")
        selectedPoint?.let {
            append("; selected point ${selected + 1}, input ${(it.x * 255f).roundToInt()}, output ${(it.y * 255f).roundToInt()}")
        }
        if (mode == CurveEditMode.FREEHAND) append("; freehand mode")
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AeSegmented(CurveEditMode.entries, mode, { mode = it }, CurveEditMode::label, Modifier.fillMaxWidth())
        Canvas(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .semantics { contentDescription = graphDescription }
                .focusable()
                .onKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                    if (e.key == Key.Tab) {
                        if (current.points.isEmpty()) return@onKeyEvent false
                        if (e.isShiftPressed) {
                            if (selected == -1) {
                                selected = current.points.lastIndex
                                return@onKeyEvent true
                            }
                            if (selected > 0) {
                                selected--
                                return@onKeyEvent true
                            }
                            selected = -1
                            return@onKeyEvent false
                        }
                        if (selected == -1) {
                            selected = 0
                            return@onKeyEvent true
                        }
                        if (selected < current.points.lastIndex) {
                            selected++
                            return@onKeyEvent true
                        }
                        selected = -1
                        onTabExit?.let {
                            it()
                            return@onKeyEvent true
                        }
                        return@onKeyEvent false
                    }
                    if (selected !in current.points.indices) return@onKeyEvent false
                    val step = if (e.isShiftPressed) 10f / 255f else 1f / 255f
                    val p = current.points[selected]
                    when (e.key) {
                        Key.DirectionUp -> {
                            change(current.moved(selected, p.copy(y = p.y + step)))
                            onValueChangeFinished()
                            true
                        }

                        Key.DirectionDown -> {
                            change(current.moved(selected, p.copy(y = p.y - step)))
                            onValueChangeFinished()
                            true
                        }

                        Key.DirectionLeft -> {
                            change(current.moved(selected, p.copy(x = p.x - step)))
                            onValueChangeFinished()
                            true
                        }

                        Key.DirectionRight -> {
                            change(current.moved(selected, p.copy(x = p.x + step)))
                            onValueChangeFinished()
                            true
                        }

                        Key.Delete, Key.Backspace -> {
                            change(current.without(selected))
                            selected = -1
                            onValueChangeFinished()
                            true
                        }

                        else -> false
                    }
                }
                .pointerInput(mode) {
                    awaitEachGesture {
                        fun toCurve(o: Offset) = CurvePoint(o.x / size.width, 1f - o.y / size.height)
                        val down = awaitFirstDown()
                        if (mode == CurveEditMode.FREEHAND) {
                            val stroke = mutableListOf(toCurve(down.position))
                            while (true) {
                                val changeEvent = awaitPointerEvent().changes.firstOrNull() ?: break
                                if (!changeEvent.pressed) break
                                stroke += toCurve(changeEvent.position)
                                changeEvent.consume()
                            }
                            change(curveFromFreehand(current, stroke))
                            selected = -1
                            onValueChangeFinished()
                            return@awaitEachGesture
                        }
                        val hitPx = 10.dp.toPx()
                        val hit = current.points.indexOfFirst { p ->
                            hypot(p.x * size.width - down.position.x, (1f - p.y) * size.height - down.position.y) < hitPx
                        }
                        var index = hit
                        if (index < 0) {
                            val (next, i) = current.withPoint(toCurve(down.position))
                            change(next)
                            index = i
                        }
                        selected = index
                        var removed = false
                        while (true) {
                            val ch = awaitPointerEvent().changes.firstOrNull() ?: break
                            if (!ch.pressed) break
                            val pos = ch.position
                            val outside =
                                pos.x < -hitPx * 2 || pos.x > size.width + hitPx * 2 || pos.y < -hitPx * 2 || pos.y > size.height + hitPx * 2
                            val interior = index in 1 until current.points.lastIndex
                            if (outside && interior) {
                                if (!removed) change(current.without(index))
                                removed = true
                                selected = -1
                            } else if (!removed) {
                                change(current.moved(index, toCurve(pos)))
                            }
                            ch.consume()
                        }
                        onValueChangeFinished()
                    }
                },
        ) {
            drawRect(c.surface2)
            histogramBackdrop(histogram, c.surface4)
            // Quarter grid + diagonal reference.
            for (k in 1..3) {
                val f = k / 4f
                drawLine(c.lineHairline, Offset(f * size.width, 0f), Offset(f * size.width, size.height))
                drawLine(c.lineHairline, Offset(0f, f * size.height), Offset(size.width, f * size.height))
            }
            drawLine(c.lineStrong, Offset(0f, size.height), Offset(size.width, 0f), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            val path = Path()
            val steps = 128
            for (k in 0..steps) {
                val x = k / steps.toFloat()
                val px = x * size.width
                val py = (1f - curve.evaluate(x)) * size.height
                if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            drawPath(path, curveColor, style = Stroke(1.5.dp.toPx()))
            curve.points.forEachIndexed { i, p ->
                val o = Offset(p.x * size.width, (1f - p.y) * size.height)
                if (i == selected) drawCircle(c.accent, 5.dp.toPx(), o) else drawCircle(curveColor, 4.dp.toPx(), o, style = Stroke(1.5.dp.toPx()))
            }
        }
        val sel = curve.points.getOrNull(selected)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AeText("Input", style = Ae.type.caption, color = c.textSecondary)
            AeNumberField(
                ((sel?.x ?: 0f) * 255f).roundToInt().toFloat(),
                { v -> if (sel != null) onCurveChange(curve.moved(selected, sel.copy(x = v / 255f))) },
                range = 0f..255f,
                enabled = sel != null,
                contentDescription = "Input",
                onCommit = onValueChangeFinished,
            )
            AeText("Output", style = Ae.type.caption, color = c.textSecondary)
            AeNumberField(
                ((sel?.y ?: 0f) * 255f).roundToInt().toFloat(),
                { v -> if (sel != null) onCurveChange(curve.moved(selected, sel.copy(y = v / 255f))) },
                range = 0f..255f,
                enabled = sel != null,
                contentDescription = "Output",
                onCommit = onValueChangeFinished,
            )
        }
    }
}
