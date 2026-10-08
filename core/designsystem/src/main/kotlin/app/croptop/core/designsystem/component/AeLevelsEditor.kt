package app.croptop.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.icon.AeIcons
import app.croptop.core.designsystem.theme.Ae
import app.croptop.core.model.Levels
import kotlin.math.abs
import kotlin.math.roundToInt

/** Input-level point that should be picked from the canvas by the hosting editor. */
enum class LevelsPickTarget(val label: String) {
    BLACK("Black"),
    GRAY("Gray"),
    WHITE("White"),
}

/** Auto levels trims half a percent at either tail and leaves output levels unchanged. */
internal fun Levels.autoFromHistogram(histogram: FloatArray?): Levels {
    if (histogram == null || histogram.size < 2) return this
    val counts = histogram.map { value -> if (value.isFinite()) value.coerceAtLeast(0f).toDouble() else 0.0 }
    val total = counts.sum()
    if (total <= 0.0) return this

    fun quantileIndex(fraction: Double): Int {
        val threshold = total * fraction
        var cumulative = 0.0
        counts.forEachIndexed { index, count ->
            cumulative += count
            if (cumulative >= threshold) return index
        }
        return counts.lastIndex
    }

    val blackIndex = quantileIndex(0.005)
    val whiteIndex = quantileIndex(0.995)
    if (whiteIndex - blackIndex < 1) return this
    val denominator = (counts.size - 1).toFloat()
    return copy(inBlack = blackIndex / denominator, inWhite = whiteIndex / denominator, gamma = 1f)
}

private fun DrawScope.triangle(x: Float, fill: Color, outline: Color) {
    val h = 9.dp.toPx()
    val w = 6.dp.toPx()
    val p = Path().apply {
        moveTo(x, 0f)
        lineTo(x + w, h)
        lineTo(x - w, h)
        close()
    }
    drawPath(p, fill)
    drawPath(p, outline, style = Stroke(1.dp.toPx()))
}

/** Levels editor: histogram, input black/gray/white triangles, output range, numeric fields. */
@Composable
fun AeLevelsEditor(
    levels: Levels,
    onLevelsChange: (Levels) -> Unit,
    modifier: Modifier = Modifier,
    histogram: FloatArray? = null,
    onPickSample: ((LevelsPickTarget) -> Unit)? = null,
) {
    val current by rememberUpdatedState(levels)
    val change by rememberUpdatedState(onLevelsChange)
    val c = Ae.colors
    val hasHistogram = histogram?.any { it.isFinite() && it > 0f } == true
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AeButton("Auto", { onLevelsChange(levels.autoFromHistogram(histogram)) }, modifier = Modifier.fillMaxWidth(), enabled = hasHistogram)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            LevelsPickTarget.entries.forEach { target ->
                AeButton(
                    text = target.label,
                    onClick = { onPickSample?.invoke(target) },
                    modifier = Modifier.weight(1f),
                    icon = AeIcons.Eyedropper,
                    enabled = onPickSample != null,
                )
            }
        }
        AeText("Input levels", style = Ae.type.caption, color = c.textSecondary)
        Canvas(Modifier.fillMaxWidth().height(96.dp).semantics { contentDescription = "Histogram" }) {
            drawRect(c.surface2)
            histogramBackdrop(histogram, c.textSecondary)
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(14.dp)
                .semantics { contentDescription = "Input black, gray and white points" }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val w = size.width.toFloat()
                        val l0 = current
                        // Grab the nearest handle.
                        val targets = listOf(l0.inBlack, l0.grayPosition, l0.inWhite).map { it * w }
                        val which = targets.indices.minBy { abs(targets[it] - down.position.x) }
                        while (true) {
                            val ch = awaitPointerEvent().changes.firstOrNull() ?: break
                            if (!ch.pressed) break
                            val v = (ch.position.x / w).coerceIn(0f, 1f)
                            val l = current
                            change(
                                when (which) {
                                    0 -> l.copy(inBlack = v.coerceAtMost(l.inWhite - 0.01f))
                                    2 -> l.copy(inWhite = v.coerceAtLeast(l.inBlack + 0.01f))
                                    else -> l.withGrayPosition(v.coerceIn(l.inBlack + 0.005f, l.inWhite - 0.005f))
                                },
                            )
                            ch.consume()
                        }
                    }
                },
        ) {
            triangle(levels.inBlack * size.width, Color.Black, c.lineStrong)
            triangle(levels.grayPosition * size.width, Color(0xFF808080), c.lineStrong)
            triangle(levels.inWhite * size.width, Color.White, c.lineStrong)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            AeNumberField((levels.inBlack * 255).roundToInt().toFloat(), {
                onLevelsChange(levels.copy(inBlack = (it / 255f).coerceAtMost(levels.inWhite - 0.01f)))
            }, range = 0f..253f, contentDescription = "Input black")
            Spacer(Modifier.weight(1f))
            AeNumberField(levels.gamma, {
                onLevelsChange(levels.copy(gamma = it))
            }, range = 0.1f..9.99f, step = 0.01f, decimals = 2, contentDescription = "Gamma")
            Spacer(Modifier.weight(1f))
            AeNumberField((levels.inWhite * 255).roundToInt().toFloat(), {
                onLevelsChange(levels.copy(inWhite = (it / 255f).coerceAtLeast(levels.inBlack + 0.01f)))
            }, range = 2f..255f, contentDescription = "Input white")
        }
        AeText("Output levels", style = Ae.type.caption, color = c.textSecondary)
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(24.dp)
                .semantics { contentDescription = "Output black and white" }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val w = size.width.toFloat()
                        val black = abs(current.outBlack * w - down.position.x) < abs(current.outWhite * w - down.position.x)
                        while (true) {
                            val ch = awaitPointerEvent().changes.firstOrNull() ?: break
                            if (!ch.pressed) break
                            val v = (ch.position.x / w).coerceIn(0f, 1f)
                            change(if (black) current.copy(outBlack = v) else current.copy(outWhite = v))
                            ch.consume()
                        }
                    }
                },
        ) {
            drawRect(Brush.horizontalGradient(listOf(Color.Black, Color.White)), size = size.copy(height = 8.dp.toPx()))
            translate(top = 10.dp.toPx()) {
                triangle(levels.outBlack * size.width, Color.Black, c.lineStrong)
                triangle(levels.outWhite * size.width, Color.White, c.lineStrong)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            AeNumberField((levels.outBlack * 255).roundToInt().toFloat(), {
                onLevelsChange(levels.copy(outBlack = it / 255f))
            }, range = 0f..255f, contentDescription = "Output black")
            Spacer(Modifier.weight(1f))
            AeNumberField((levels.outWhite * 255).roundToInt().toFloat(), {
                onLevelsChange(levels.copy(outWhite = it / 255f))
            }, range = 0f..255f, contentDescription = "Output white")
        }
    }
}

private inline fun DrawScope.translate(top: Float, block: DrawScope.() -> Unit) {
    drawContext.transform.translate(0f, top)
    block()
    drawContext.transform.translate(0f, -top)
}
