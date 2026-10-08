package app.croptop.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.theme.Ae
import app.croptop.core.model.ColorBalance
import app.croptop.core.model.ColorBalanceShift
import app.croptop.core.model.ColorMath
import app.croptop.core.model.Hsv
import app.croptop.core.model.HueChannel
import app.croptop.core.model.HueRange
import app.croptop.core.model.HueSaturation
import app.croptop.core.model.HueShift
import app.croptop.core.model.ToneRange
import kotlin.math.abs
import kotlin.math.roundToInt

private fun hueColor(h: Float) = ColorMath.fromHsv(Hsv(((h % 360f) + 360f) % 360f, 1f, 1f)).let { Color(it.r, it.g, it.b) }

private fun spectrum(shift: Float = 0f) = Brush.horizontalGradient((0..12).map { hueColor(it * 30f + shift) })

/**
 * Hue/Saturation editor: Master or a color channel, Hue/Saturation/Lightness sliders with
 * color tracks, the two-spectrum range bar for color channels, and Colorize.
 */
@Composable
fun AeHueSaturationEditor(
    value: HueSaturation,
    onChange: (HueSaturation) -> Unit,
    modifier: Modifier = Modifier,
    targetedChannel: HueChannel? = null,
) {
    var channel by rememberSaveable { mutableStateOf(HueChannel.MASTER) }
    LaunchedEffect(targetedChannel) { if (targetedChannel != null) channel = targetedChannel }
    val s = value.shift(channel)
    val range = value.range(channel)
    val base = range?.center ?: 0f
    fun set(n: HueShift) = onChange(value.withShift(channel, n))
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AeDropdown(HueChannel.entries, channel, { channel = it }, { it.label }, enabled = !value.colorize)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                AeCheckbox(value.colorize, { onChange(value.copy(colorize = it)) }, "Colorize")
            }
        }
        AeSlider(
            "Hue",
            s.hue,
            { set(s.copy(hue = it)) },
            range = if (value.colorize) 0f..360f else -180f..180f,
            defaultValue = 0f,
            trackBrush = if (value.colorize) spectrum() else Brush.horizontalGradient((0..12).map { hueColor(base - 180f + it * 30f) }),
        )
        val tint = hueColor(if (value.colorize) s.hue else base + s.hue)
        AeSlider(
            "Saturation",
            s.saturation,
            { set(s.copy(saturation = it)) },
            range = if (value.colorize) 0f..100f else -100f..100f,
            defaultValue = if (value.colorize) 25f else 0f,
            trackBrush = Brush.horizontalGradient(listOf(Color(0xFF808080), tint)),
        )
        AeSlider(
            "Lightness",
            s.lightness,
            { set(s.copy(lightness = it)) },
            range = -100f..100f,
            defaultValue = 0f,
            trackBrush = Brush.horizontalGradient(listOf(Color.Black, tint, Color.White)),
        )
        if (!value.colorize) {
            AeHueRangeBar(
                range,
                hueShift = s.hue,
                onRangeChange = { r -> onChange(value.withRange(channel, r)) },
            )
        }
    }
}

/**
 * Two spectrum bars (input above, result below) with the edited range between them: drag the inner
 * block to move the range, the inner handles to widen it, the outer handles to set the falloff.
 * ←/→ nudge the whole range by 1° (Shift: 10°). With no range (Master) it only shows the hue shift.
 */
@Composable
fun AeHueRangeBar(range: HueRange?, hueShift: Float, onRangeChange: (HueRange) -> Unit, modifier: Modifier = Modifier) {
    val c = Ae.colors
    val current by rememberUpdatedState(range)
    val change by rememberUpdatedState(onRangeChange)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(52.dp)
            .semantics {
                contentDescription = "Hue range"
                stateDescription = range?.let {
                    "Selected ${it.innerStart.roundToInt()}° to ${it.innerEnd.roundToInt()}°; " +
                        "soft edges ${it.outerStart.roundToInt()}° and ${it.outerEnd.roundToInt()}°"
                } ?: "All colors"
                customActions = if (range == null) {
                    emptyList()
                } else {
                    listOf(
                        CustomAccessibilityAction("Move hue range left") {
                            current?.let { change(it.rotate(-1f)) }
                            true
                        },
                        CustomAccessibilityAction("Move hue range right") {
                            current?.let { change(it.rotate(1f)) }
                            true
                        },
                    )
                }
            }
            .focusable(enabled = range != null)
            .onKeyEvent { e ->
                val r = current ?: return@onKeyEvent false
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                val d = if (e.isShiftPressed) 10f else 1f
                when (e.key) {
                    Key.DirectionRight -> change(r.rotate(d)).let { true }
                    Key.DirectionLeft -> change(r.rotate(-d)).let { true }
                    else -> false
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val start = current ?: return@awaitEachGesture
                    val w = size.width.toFloat()
                    fun deg(x: Float) = x / w * 360f
                    // Pick the nearest handle within 8px (in any wrap copy), else the whole range.
                    val points = listOf(start.outerStart, start.innerStart, start.innerEnd, start.outerEnd)
                    val px = down.position.x
                    val handle = points.indices.minByOrNull { i ->
                        listOf(-360f, 0f, 360f).minOf { k -> abs((points[i] + k) / 360f * w - px) }
                    }?.takeIf { i -> listOf(-360f, 0f, 360f).minOf { k -> abs((points[i] + k) / 360f * w - px) } <= 8.dp.toPx() }
                    val origin = deg(px)
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull() ?: break
                        if (!ch.pressed) break
                        val rawDelta = deg(ch.position.x) - origin
                        val d = ((rawDelta + 540f) % 360f) - 180f
                        change(if (handle == null) start.rotate(d) else moveHandle(start, handle, d))
                        ch.consume()
                    }
                }
            },
    ) {
        val barH = 12.dp.toPx()
        drawRect(spectrum(), Offset.Zero, Size(size.width, barH))
        drawRect(spectrum(hueShift), Offset(0f, size.height - barH), Size(size.width, barH))
        val r = range ?: return@Canvas
        val top = barH + 4.dp.toPx()
        val bottom = size.height - barH - 4.dp.toPx()
        clipRect(0f, top, size.width, bottom) {
            listOf(-360f, 0f, 360f).forEach { k -> rangeShape(r, k, top, bottom, c.textSecondary, c.textPrimary) }
        }
    }
}

private fun moveHandle(r: HueRange, i: Int, d: Float): HueRange {
    fun n(v: Float) = ((v % 360f) + 360f) % 360f
    fun span(a: Float, b: Float) = ((b - a) % 360f + 360f) % 360f
    // Keep order outerStart ≤ innerStart ≤ innerEnd ≤ outerEnd along the circle.
    return when (i) {
        0 -> r.copy(outerStart = n(r.outerStart + d.coerceIn(-(359f - span(r.outerStart, r.outerEnd)), span(r.outerStart, r.innerStart))))
        1 -> r.copy(innerStart = n(r.innerStart + d.coerceIn(-span(r.outerStart, r.innerStart), span(r.innerStart, r.innerEnd))))
        2 -> r.copy(innerEnd = n(r.innerEnd + d.coerceIn(-span(r.innerStart, r.innerEnd), span(r.innerEnd, r.outerEnd))))
        else -> r.copy(outerEnd = n(r.outerEnd + d.coerceIn(-span(r.innerEnd, r.outerEnd), 359f - span(r.outerStart, r.outerEnd))))
    }
}

private fun DrawScope.rangeShape(r: HueRange, offsetDeg: Float, top: Float, bottom: Float, outline: Color, fill: Color) {
    fun span(a: Float, b: Float) = ((b - a) % 360f + 360f) % 360f
    val x0 = (r.outerStart + offsetDeg) / 360f * size.width
    fun x(p: Float) = x0 + span(r.outerStart, p) / 360f * size.width
    val xi0 = x(r.innerStart)
    val xi1 = x(r.innerEnd)
    val xo1 = x(r.outerEnd)
    val mid = (top + bottom) / 2
    val path = Path().apply {
        moveTo(x0, bottom)
        lineTo(xi0, mid)
        lineTo(xi1, mid)
        lineTo(xo1, bottom)
    }
    drawPath(path, outline, style = Stroke(1.dp.toPx()))
    drawRect(fill.copy(alpha = 0.35f), Offset(xi0, mid - 3.dp.toPx()), Size(xi1 - xi0, 6.dp.toPx()))
    listOf(x0, xi0, xi1, xo1).forEachIndexed { i, hx ->
        val hy = if (i == 0 || i == 3) bottom else mid
        drawRect(fill, Offset(hx - 2.dp.toPx(), hy - 4.dp.toPx()), Size(4.dp.toPx(), 8.dp.toPx()))
    }
}

/** Color Balance editor: tone selector, three color-track sliders, Preserve Luminosity. */
@Composable
fun AeColorBalanceEditor(value: ColorBalance, onChange: (ColorBalance) -> Unit, modifier: Modifier = Modifier) {
    var tone by rememberSaveable { mutableStateOf(ToneRange.MIDTONES) }
    val s = value.of(tone)
    fun set(n: ColorBalanceShift) = onChange(value.with(tone, n))
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AeSegmented(ToneRange.entries, tone, { tone = it }, { it.label })
        AeSlider(
            "Cyan – Red",
            s.cyanRed,
            { set(s.copy(cyanRed = it)) },
            range = -100f..100f,
            defaultValue = 0f,
            trackBrush = Brush.horizontalGradient(listOf(Color(0xFF00B8D9), Color(0xFF7A7A7A), Color(0xFFE5484D))),
        )
        AeSlider(
            "Magenta – Green",
            s.magentaGreen,
            { set(s.copy(magentaGreen = it)) },
            range = -100f..100f,
            defaultValue = 0f,
            trackBrush = Brush.horizontalGradient(listOf(Color(0xFFD63CC4), Color(0xFF7A7A7A), Color(0xFF30A46C))),
        )
        AeSlider(
            "Yellow – Blue",
            s.yellowBlue,
            { set(s.copy(yellowBlue = it)) },
            range = -100f..100f,
            defaultValue = 0f,
            trackBrush = Brush.horizontalGradient(listOf(Color(0xFFE5C33B), Color(0xFF7A7A7A), Color(0xFF3E63DD))),
        )
        AeCheckbox(value.preserveLuminosity, { onChange(value.copy(preserveLuminosity = it)) }, "Preserve Luminosity")
    }
}
