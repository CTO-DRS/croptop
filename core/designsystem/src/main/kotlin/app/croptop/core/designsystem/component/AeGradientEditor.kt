package app.croptop.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.icon.AeIcons
import app.croptop.core.designsystem.theme.Ae
import app.croptop.core.designsystem.theme.AeDensity
import app.croptop.core.model.ColorGradient
import app.croptop.core.model.GradientStop
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Gradient editor (/): preview bar, color stops below (click the strip to add,
 * drag to move, select to edit color and location, delete button). Two stops minimum.
 */
@Composable
fun AeGradientEditor(
    gradient: ColorGradient,
    onGradientChange: (ColorGradient) -> Unit,
    modifier: Modifier = Modifier,
    onValueChangeFinished: () -> Unit = {},
) {
    var selected by remember { mutableIntStateOf(0) }
    var editingColor by remember { mutableStateOf(false) }
    val current by rememberUpdatedState(gradient)
    val change by rememberUpdatedState(onGradientChange)
    val finish by rememberUpdatedState(onValueChangeFinished)
    val c = Ae.colors
    val stops = gradient.stops
    val selectedIndex = selected.coerceIn(0, stops.lastIndex)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(Modifier.fillMaxWidth().height(28.dp).semantics { contentDescription = "Gradient preview" }) {
            val sorted = stops.sortedBy { it.position }
            drawRect(
                Brush.horizontalGradient(
                    *sorted.map { it.position to it.color.toCompose() }.toTypedArray(),
                ),
            )
            drawRect(c.lineStrong, style = Stroke(1.dp.toPx()))
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(16.dp)
                .semantics { contentDescription = "Color stops. Click to add a stop." }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val before = current
                        var edited = before
                        val w = size.width.toFloat()
                        val pos = (down.position.x / w).coerceIn(0f, 1f)
                        // Keep the stop list in its original order while dragging. Sorting it on each
                        // pointer event made the captured index refer to a different stop as soon as
                        // the handle crossed a neighbor.
                        val hit = current.stops.indices.minByOrNull { abs(current.stops[it].position - pos) }
                            ?.takeIf { abs(current.stops[it].position - pos) * w < 8.dp.toPx() }
                        val index = hit ?: run {
                            val next = current.stops + GradientStop(pos, current.colorAt(pos))
                            edited = ColorGradient(next)
                            change(edited)
                            next.lastIndex
                        }
                        selected = index
                        while (true) {
                            val ch = awaitPointerEvent().changes.firstOrNull() ?: break
                            if (!ch.pressed) break
                            val p = (ch.position.x / w).coerceIn(0f, 1f)
                            edited = ColorGradient(edited.stops.toMutableList().also { it[index] = it[index].copy(position = p) })
                            change(edited)
                            ch.consume()
                        }
                        if (edited != before) finish()
                    }
                },
        ) {
            stops.forEachIndexed { i, s ->
                val x = s.position * size.width
                val w = 6.dp.toPx()
                val tri = Path().apply {
                    moveTo(x, 0f)
                    lineTo(x + w, 6.dp.toPx())
                    lineTo(x - w, 6.dp.toPx())
                    close()
                }
                drawPath(tri, if (i == selectedIndex) c.accent else c.textSecondary)
                drawRect(s.color.toCompose(), Offset(x - w, 6.dp.toPx()), Size(w * 2, size.height - 6.dp.toPx()))
                drawRect(
                    if (i ==
                        selectedIndex
                    ) {
                        c.accent
                    } else {
                        c.lineStrong
                    },
                    Offset(x - w, 6.dp.toPx()),
                    Size(w * 2, size.height - 6.dp.toPx()),
                    style = Stroke(1.dp.toPx()),
                )
            }
        }
        // Standard focusable actions provide the same stop editing path without pointer gestures.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            stops.forEachIndexed { index, stop ->
                AeButton(
                    "Stop ${index + 1}",
                    onClick = { selected = index },
                    style = if (index == selectedIndex) AeButtonStyle.Primary else AeButtonStyle.Secondary,
                    modifier = Modifier.semantics {
                        this.selected = index == selectedIndex
                        contentDescription = "Gradient stop ${index + 1}, location ${(stop.position * 100f).roundToInt()} percent"
                    },
                    minimumTouchTargetSize = if (Ae.density == AeDensity.Touch) 48.dp else null,
                )
            }
            AeButton(
                "Add stop",
                onClick = {
                    val positions = (listOf(0f, 1f) + current.stops.map { it.position }).sorted()
                    val gap = positions.zipWithNext().maxBy { (left, right) -> right - left }
                    val position = (gap.first + gap.second) / 2f
                    val next = current.stops + GradientStop(position, current.colorAt(position))
                    selected = next.lastIndex
                    change(ColorGradient(next))
                    finish()
                },
                minimumTouchTargetSize = if (Ae.density == AeDensity.Touch) 48.dp else null,
            )
        }
        val sel = stops.getOrNull(selectedIndex)
        if (sel != null) {
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                AeText("Color", style = Ae.type.caption, color = c.textSecondary)
                AeColorWell(
                    sel.color,
                    "Stop color",
                    size = if (Ae.density == AeDensity.Touch) 48 else Ae.density.controlHeight.value.toInt(),
                ) { editingColor = !editingColor }
                AeText("Location", style = Ae.type.caption, color = c.textSecondary)
                AeNumberField(
                    (sel.position * 100f).roundToInt().toFloat(),
                    { v ->
                        onGradientChange(ColorGradient(gradient.stops.toMutableList().also { it[selectedIndex] = sel.copy(position = v / 100f) }))
                    },
                    range = 0f..100f,
                    unit = "%",
                    contentDescription = "Stop location",
                    onCommit = { finish() },
                    minimumHeight = if (Ae.density == AeDensity.Touch) 48.dp else null,
                )
                AeIconButton(
                    AeIcons.Delete,
                    "Delete stop",
                    {
                        onGradientChange(ColorGradient(gradient.stops.filterIndexed { i, _ -> i != selectedIndex }))
                        selected = (selectedIndex - 1).coerceAtLeast(0)
                        finish()
                    },
                    enabled = stops.size > 2,
                    minimumTouchTargetSize = if (Ae.density == AeDensity.Touch) 48.dp else null,
                )
            }
            if (editingColor) {
                AeColorPicker(sel.color, { col ->
                    onGradientChange(ColorGradient(gradient.stops.toMutableList().also { it[selectedIndex] = sel.copy(color = col) }))
                }, onEditComplete = { finish() })
            }
        }
    }
}
