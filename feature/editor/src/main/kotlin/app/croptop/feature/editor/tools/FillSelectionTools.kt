package app.croptop.feature.editor.tools

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.component.AeButton
import app.croptop.core.designsystem.component.AeCheckbox
import app.croptop.core.designsystem.component.AeDropdown
import app.croptop.core.designsystem.component.AeBarSlider
import app.croptop.core.designsystem.component.AeText
import app.croptop.core.designsystem.theme.Ae
import app.croptop.core.input.Modifiers
import app.croptop.core.model.ColorGradient
import app.croptop.core.model.DocPoint
import app.croptop.core.model.GradientShape
import app.croptop.core.model.RgbaColor
import app.croptop.core.model.SelectionOp
import app.croptop.core.model.ToolId
import app.croptop.core.model.ViewPoint
import app.croptop.core.model.ViewportState
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.round
import kotlin.math.sin

/** Click-to-select colors. The engine owns color comparison and the resulting mask. */
class MagicWandTool(
    private val onActionCompleted: (String) -> Unit = {},
    private val apply: (DocPoint, Int, Boolean, Boolean, SelectionOp) -> Boolean,
) : ToolController {
    override val tool = ToolId.MAGIC_WAND
    var tolerance by mutableStateOf(32)
    var contiguous by mutableStateOf(true)
    var sampleAll by mutableStateOf(false)

    override fun onPointer(event: ToolPointerEvent, ctx: ToolContext): Boolean {
        if (event.phase == PointerPhase.DOWN && event.doc.inside(ctx)) {
            apply(event.doc, tolerance, contiguous, sampleAll, selectionOp(event.modifiers))
        }
        return true
    }

    @Composable override fun Options() {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ToleranceField(tolerance, change = { tolerance = it }) { onActionCompleted("Set selection tolerance") }
            AeCheckbox(contiguous, {
                contiguous = it
                onActionCompleted(if (it) "Enable contiguous selection" else "Disable contiguous selection")
            }, "Contiguous")
            AeCheckbox(sampleAll, {
                sampleAll = it
                onActionCompleted(if (it) "Sample all layers" else "Sample current layer")
            }, "Sample all layers")
            AeText("Shift add · Alt subtract · Shift+Alt intersect", style = Ae.type.caption, color = Ae.colors.textTertiary)
        }
    }
}

/** Click-to-fill with the current foreground color. */
class PaintBucketTool(
    private val foreground: () -> RgbaColor,
    private val onActionCompleted: (String) -> Unit = {},
    private val apply: (DocPoint, RgbaColor, Int, Boolean, Boolean, Float) -> Boolean,
) : ToolController {
    override val tool = ToolId.PAINT_BUCKET
    var tolerance by mutableStateOf(32)
    var contiguous by mutableStateOf(true)
    var sampleAll by mutableStateOf(false)
    var opacity by mutableStateOf(1f)

    override fun onPointer(event: ToolPointerEvent, ctx: ToolContext): Boolean {
        if (event.phase == PointerPhase.DOWN && event.doc.inside(ctx)) {
            apply(event.doc, foreground(), tolerance, contiguous, sampleAll, opacity)
        }
        return true
    }

    @Composable override fun Options() {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ToleranceField(tolerance, change = { tolerance = it }) { onActionCompleted("Set fill tolerance") }
            AeCheckbox(contiguous, {
                contiguous = it
                onActionCompleted(if (it) "Enable contiguous fill" else "Disable contiguous fill")
            }, "Contiguous")
            AeCheckbox(sampleAll, {
                sampleAll = it
                onActionCompleted(if (it) "Sample all layers for fill" else "Sample active layer for fill")
            }, "Sample all layers")
            OpacityField(opacity, change = { opacity = it }) { onActionCompleted("Set bucket opacity") }
        }
    }
}

/** Drag out a gradient; one engine command is sent on pointer up. */
class GradientTool(
    private val colors: () -> Pair<RgbaColor, RgbaColor>,
    private val apply: (DocPoint, DocPoint, ColorGradient, GradientShape, Boolean, Boolean, Float) -> Boolean,
    private val gradient: () -> ColorGradient? = { null },
    private val onEditGradient: () -> Unit = {},
    private val onActionCompleted: (String) -> Unit = {},
) : ToolController {
    override val tool = ToolId.GRADIENT
    var shape by mutableStateOf(GradientShape.LINEAR)
    var reverse by mutableStateOf(false)
    var dither by mutableStateOf(true)
    var opacity by mutableStateOf(1f)
    private var start by mutableStateOf<DocPoint?>(null)
    private var end by mutableStateOf<DocPoint?>(null)

    override fun onPointer(event: ToolPointerEvent, ctx: ToolContext): Boolean {
        when (event.phase) {
            PointerPhase.DOWN -> if (event.doc.inside(ctx)) {
                start = event.doc
                end = event.doc
            }

            PointerPhase.MOVE -> start?.let { end = snapGradientEnd(it, event.doc, event.modifiers.shift) }

            PointerPhase.UP -> {
                val from = start
                if (from != null) {
                    val to = snapGradientEnd(from, event.doc, event.modifiers.shift)
                    if (hypot((to.x - from.x).toDouble(), (to.y - from.y).toDouble()) * ctx.viewport.zoom >= 2.0) {
                        val (fg, bg) = colors()
                        apply(
                            from,
                            to,
                            gradient()
                                ?: ColorGradient(listOf(app.croptop.core.model.GradientStop(0f, fg), app.croptop.core.model.GradientStop(1f, bg))),
                            shape,
                            reverse,
                            dither,
                            opacity,
                        )
                    }
                }
                start = null
                end = null
            }

            PointerPhase.CANCEL -> {
                start = null
                end = null
            }
        }
        return true
    }

    @Composable override fun Options() {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AeText("Shape", style = Ae.type.caption, color = Ae.colors.textSecondary)
            AeDropdown(
                GradientShape.entries,
                shape,
                {
                    shape = it
                    onActionCompleted("Set gradient shape")
                },
                { it.name.lowercase().replaceFirstChar(Char::uppercase) },
            )
            AeCheckbox(reverse, {
                reverse = it
                onActionCompleted(if (it) "Reverse gradient" else "Use gradient direction")
            }, "Reverse")
            AeCheckbox(dither, {
                dither = it
                onActionCompleted(if (it) "Enable gradient dithering" else "Disable gradient dithering")
            }, "Dither")
            OpacityField(opacity, { opacity = it }) { onActionCompleted("Set gradient opacity") }
            AeButton("Edit gradient…", onEditGradient)
            AeText("Shift snaps 45°", style = Ae.type.caption, color = Ae.colors.textTertiary)
        }
    }

    @Composable override fun Overlay(viewport: ViewportState, hover: ViewPoint?) {
        val a = start ?: return
        val b = end ?: return
        val av = viewport.docToView(a)
        val bv = viewport.docToView(b)
        Canvas(Modifier.fillMaxSize()) {
            val from = Offset(av.x, av.y)
            val to = Offset(bv.x, bv.y)
            drawLine(Color.Black, from, to, strokeWidth = 3.dp.toPx())
            drawLine(Color.White, from, to, strokeWidth = 1.dp.toPx())
            drawCircle(Color.White, 5.dp.toPx(), from)
            drawCircle(Color.Black, 5.dp.toPx(), from, style = Stroke(1.dp.toPx()))
            drawCircle(Color.White, 5.dp.toPx(), to)
            drawCircle(Color.Black, 5.dp.toPx(), to, style = Stroke(1.dp.toPx()))
        }
    }
}

internal fun snapGradientEnd(start: DocPoint, end: DocPoint, shift: Boolean): DocPoint {
    if (!shift) return end
    val dx = end.x - start.x
    val dy = end.y - start.y
    val radius = hypot(dx, dy)
    val angle = round(atan2(dy, dx) / (PI / 4)).toInt() * PI / 4
    return DocPoint(start.x + radius * cos(angle).toFloat(), start.y + radius * sin(angle).toFloat())
}

private fun DocPoint.inside(ctx: ToolContext) =
    x.isFinite() && y.isFinite() && x >= 0f && y >= 0f && x < ctx.document.width && y < ctx.document.height

@Composable private fun ToleranceField(value: Int, change: (Int) -> Unit, onCommit: () -> Unit = {}) {
    AeBarSlider(
        "Tolerance",
        value.toFloat(),
        { change(it.toInt()) },
        range = 0f..255f,
        defaultValue = 32f,
        width = 160.dp,
        contentDescription = "Tolerance",
        onValueChangeFinished = onCommit,
    )
}

@Composable private fun OpacityField(value: Float, change: (Float) -> Unit, onCommit: () -> Unit = {}) {
    AeBarSlider(
        "Opacity",
        value * 100f,
        { change(it / 100f) },
        range = 0f..100f,
        defaultValue = 100f,
        unit = "%",
        width = 160.dp,
        contentDescription = "Opacity",
        onValueChangeFinished = onCommit,
    )
}
