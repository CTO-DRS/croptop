package app.croptop.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.theme.Ae
import app.croptop.core.model.ColorMath
import app.croptop.core.model.Hsv
import app.croptop.core.model.RgbaColor
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

internal fun RgbaColor.toCompose() = Color(r, g, b, a)

/** Square color swatch; [onClick] opens a picker. */
@Composable
fun AeColorWell(color: RgbaColor, contentDescription: String, modifier: Modifier = Modifier, size: Int = 28, onClick: (() -> Unit)? = null) {
    val targetSize = if (onClick != null) maxOf(size.dp, Ae.density.controlHeight) else size.dp
    Box(
        modifier
            .size(targetSize)
            .background(color.toCompose(), Ae.shapes.chip)
            .border(1.dp, Ae.colors.lineStrong, Ae.shapes.chip)
            .semantics { this.contentDescription = "$contentDescription #${color.hex}" }
            .let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it },
    )
}

enum class ColorFieldMode { RGB, HSB, LAB }
private enum class PickerSurface { SQUARE, WHEEL }

/**
 * Color picker: saturation/brightness square, hue strip, hex, RGB or HSB fields,
 * and a new/previous comparison swatch. HSV is kept internally so hue survives greys.
 */
@Composable
fun AeColorPicker(
    color: RgbaColor,
    onColorChange: (RgbaColor) -> Unit,
    modifier: Modifier = Modifier,
    previous: RgbaColor? = null,
    onEditComplete: () -> Unit = {},
) {
    var hsv by remember { mutableStateOf(ColorMath.toHsv(color)) }
    // Re-sync when the color changes from outside (eyedropper, swatch), keeping hue for greys.
    LaunchedEffect(color) {
        if (ColorMath.fromHsv(hsv).toArgb() != color.toArgb()) {
            val next = ColorMath.toHsv(color)
            hsv = if (next.s == 0f || next.v == 0f) next.copy(h = hsv.h) else next
        }
    }
    val current by rememberUpdatedState(hsv)
    fun set(n: Hsv) {
        hsv = n
        onColorChange(ColorMath.fromHsv(n, color.a))
    }
    var mode by rememberSaveable { mutableStateOf(ColorFieldMode.RGB) }
    var pickerSurface by rememberSaveable { mutableStateOf(PickerSurface.SQUARE) }
    val c = Ae.colors

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AeSegmented(PickerSurface.entries, pickerSurface, { pickerSurface = it }, { if (it == PickerSurface.SQUARE) "Square" else "Wheel" })
        if (pickerSurface == PickerSurface.SQUARE) {
            // Saturation (x) / brightness (y) square.
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .semantics { contentDescription = "Saturation and brightness" }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            fun pick(p: Offset) = set(
                                current.copy(
                                    s = (p.x / size.width).coerceIn(0f, 1f),
                                    v = 1f - (p.y / size.height).coerceIn(0f, 1f),
                                ),
                            )
                            pick(awaitFirstDown().position)
                            while (true) {
                                val ch = awaitPointerEvent().changes.firstOrNull() ?: break
                                if (!ch.pressed) break
                                pick(ch.position)
                                ch.consume()
                            }
                            onEditComplete()
                        }
                    },
            ) {
                val hueColor = ColorMath.fromHsv(Hsv(hsv.h, 1f, 1f)).toCompose()
                drawRect(Brush.horizontalGradient(listOf(Color.White, hueColor)))
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                val p = Offset(hsv.s * size.width, (1f - hsv.v) * size.height)
                drawCircle(Color.Black.copy(alpha = 0.5f), 7.dp.toPx(), p, style = Stroke(3.dp.toPx()))
                drawCircle(Color.White, 7.dp.toPx(), p, style = Stroke(1.5.dp.toPx()))
            }
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .semantics { contentDescription = "Hue" }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            fun pick(x: Float) = set(current.copy(h = (x / size.width).coerceIn(0f, 1f) * 360f))
                            pick(awaitFirstDown().position.x)
                            while (true) {
                                val ch = awaitPointerEvent().changes.firstOrNull() ?: break
                                if (!ch.pressed) break
                                pick(ch.position.x)
                                ch.consume()
                            }
                            onEditComplete()
                        }
                    },
            ) {
                drawRect(Brush.horizontalGradient((0..6).map { ColorMath.fromHsv(Hsv(it * 60f, 1f, 1f)).toCompose() }))
                val x = hsv.h / 360f * size.width
                drawRect(Color.White, Offset(x - 1.5.dp.toPx(), 0f), androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height))
            }
        } else {
            HueSaturationWheel(hsv, ::set, onEditComplete)
            BrightnessSlider(hsv, ::set, onEditComplete)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // New (top) / previous (bottom) comparison, click previous to restore.
            Column {
                Box(Modifier.size(36.dp, 18.dp).background(color.toCompose()))
                Box(
                    Modifier
                        .size(36.dp, 18.dp)
                        .background((previous ?: color).toCompose())
                        .let { m ->
                            if (previous != null) {
                                m.clickable {
                                    onColorChange(previous)
                                    onEditComplete()
                                }
                            } else {
                                m
                            }
                        }
                        .semantics { contentDescription = "Previous color" },
                )
            }
            HexField(color, onColorChange, Modifier.weight(1f), onEditComplete)
            AeSegmented(ColorFieldMode.entries, mode, { mode = it }, { it.name })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            when (mode) {
                ColorFieldMode.RGB -> {
                    Channel("R", color.r * 255f, 0f..255f, onEditComplete) { onColorChange(color.copy(r = it / 255f)) }
                    Channel("G", color.g * 255f, 0f..255f, onEditComplete) { onColorChange(color.copy(g = it / 255f)) }
                    Channel("B", color.b * 255f, 0f..255f, onEditComplete) { onColorChange(color.copy(b = it / 255f)) }
                }

                ColorFieldMode.HSB -> {
                    Channel("H", hsv.h, 0f..360f, onEditComplete) { set(hsv.copy(h = it)) }
                    Channel("S", hsv.s * 100f, 0f..100f, onEditComplete) { set(hsv.copy(s = it / 100f)) }
                    Channel("B", hsv.v * 100f, 0f..100f, onEditComplete) { set(hsv.copy(v = it / 100f)) }
                }

                ColorFieldMode.LAB -> {
                    val lab = ColorMath.toLab(color)
                    Channel("L", lab.l, 0f..100f, onEditComplete) { onColorChange(ColorMath.fromLab(lab.copy(l = it), color.a)) }
                    Channel("a", lab.a, -128f..127f, onEditComplete) { onColorChange(ColorMath.fromLab(lab.copy(a = it), color.a)) }
                    Channel("b", lab.b, -128f..127f, onEditComplete) { onColorChange(ColorMath.fromLab(lab.copy(b = it), color.a)) }
                }
            }
        }
    }
}

@Composable
private fun HueSaturationWheel(hsv: Hsv, onChange: (Hsv) -> Unit, onEditComplete: () -> Unit) {
    val current by rememberUpdatedState(hsv)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(150.dp)
            .semantics { contentDescription = "Hue and saturation wheel" }
            .pointerInput(Unit) {
                awaitEachGesture {
                    fun pick(position: Offset) {
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val radius = (size.height / 2f - 8.dp.toPx()).coerceAtLeast(1f)
                        val dx = position.x - center.x
                        val dy = center.y - position.y
                        val distance = hypot(dx, dy).coerceAtMost(radius)
                        val hue = (Math.toDegrees(atan2(dy, dx).toDouble()).toFloat() + 360f) % 360f
                        onChange(current.copy(h = hue, s = distance / radius))
                    }
                    pick(awaitFirstDown().position)
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        pick(change.position)
                        change.consume()
                    }
                    onEditComplete()
                }
            },
    ) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = (size.height / 2f - 8.dp.toPx()).coerceAtLeast(1f)
        drawCircle(
            Brush.sweepGradient(
                listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red),
                center,
            ),
            radius,
            center,
        )
        drawCircle(Brush.radialGradient(listOf(Color.White, Color.Transparent), center, radius), radius, center)
        val angle = Math.toRadians(hsv.h.toDouble())
        val cursor = Offset(
            center.x + cos(angle).toFloat() * radius * hsv.s,
            center.y - sin(angle).toFloat() * radius * hsv.s,
        )
        drawCircle(Color.Black.copy(alpha = 0.5f), 7.dp.toPx(), cursor, style = Stroke(3.dp.toPx()))
        drawCircle(Color.White, 7.dp.toPx(), cursor, style = Stroke(1.5.dp.toPx()))
    }
}

@Composable
private fun BrightnessSlider(hsv: Hsv, onChange: (Hsv) -> Unit, onEditComplete: () -> Unit) {
    val current by rememberUpdatedState(hsv)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(12.dp)
            .semantics { contentDescription = "Brightness" }
            .pointerInput(Unit) {
                awaitEachGesture {
                    fun pick(x: Float) = onChange(current.copy(v = (1f - x / size.width).coerceIn(0f, 1f)))
                    pick(awaitFirstDown().position.x)
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        pick(change.position.x)
                        change.consume()
                    }
                    onEditComplete()
                }
            },
    ) {
        drawRect(Brush.horizontalGradient(listOf(Color.Black, ColorMath.fromHsv(hsv.copy(v = 1f)).toCompose())))
        val x = (1f - hsv.v) * size.width
        drawRect(Color.White, Offset(x - 1.5.dp.toPx(), 0f), androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height))
    }
}

@Composable
private fun Channel(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onEditComplete: () -> Unit,
    onChange: (Float) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        AeText(label, style = Ae.type.caption, color = Ae.colors.textSecondary)
        AeNumberField(
            value.roundToInt().toFloat(),
            onChange,
            range = range,
            width = 52.dp,
            contentDescription = label,
            onCommit = onEditComplete,
        )
    }
}

@Composable
private fun HexField(
    color: RgbaColor,
    onColorChange: (RgbaColor) -> Unit,
    modifier: Modifier,
    onEditComplete: () -> Unit,
) {
    var text by remember(color) { mutableStateOf(color.hex) }
    val c = Ae.colors
    val valid = RgbaColor.parseHex(text) != null
    Row(
        modifier
            .height(Ae.density.controlHeight)
            .background(c.surface3, Ae.shapes.control)
            .border(1.dp, if (valid) c.lineHairline else c.danger, Ae.shapes.control)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AeText("#", style = Ae.type.mono, color = c.textTertiary)
        BasicTextField(
            value = text,
            onValueChange = { v ->
                text = v.removePrefix("#").take(8)
                if (text.length == 3 || text.length == 6 || text.length == 8) {
                    RgbaColor.parseHex(text)?.let { parsed ->
                        onColorChange(parsed)
                        onEditComplete()
                    }
                }
            },
            singleLine = true,
            textStyle = Ae.type.mono.copy(color = c.textPrimary),
            cursorBrush = SolidColor(c.accent),
            modifier = Modifier.width(72.dp).offset(x = 2.dp).semantics { contentDescription = "Hex color" },
        )
    }
}
