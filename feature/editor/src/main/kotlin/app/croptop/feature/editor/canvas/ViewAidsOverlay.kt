package app.croptop.feature.editor.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.croptop.core.designsystem.theme.Ae
import app.croptop.core.input.Modifiers
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocSize
import app.croptop.core.model.Guide
import app.croptop.core.model.GuideAxis
import app.croptop.core.model.RulerScale
import app.croptop.core.model.SnapTarget
import app.croptop.core.model.Snapping
import app.croptop.core.model.ToolId
import app.croptop.core.model.ViewAids
import app.croptop.core.model.ViewPoint
import app.croptop.core.model.ViewportState
import app.croptop.feature.editor.tools.PointerPhase
import app.croptop.feature.editor.tools.ToolContext
import app.croptop.feature.editor.tools.ToolPointerEvent

/** On-canvas colors are fixed, not themed: they sit on the image, not on chrome (Photoshop defaults). */
object OverlayColors {
    val Guide = Color(0xFF26C6F2)
    val Snap = Color(0xFFFF3FD2)
    val Grid = Color(0xB3707070)
    val GridMinor = Color(0x59707070)
    val PixelGrid = Color(0x33808080)
}

/** Takes pointer gestures before the active tool (guides, later transform handles). */
interface CanvasInterceptor {
    fun hoverCursor(p: ViewPoint, modifiers: Modifiers, viewport: ViewportState): PointerIcon?

    /** DOWN returning true captures the rest of the gesture. */
    fun onPointer(event: ToolPointerEvent, ctx: ToolContext): Boolean
}

/** A snap line to draw while dragging (doc units). */
data class SnapIndicator(val axis: GuideAxis, val position: Float)

private val ResizeHorizontal = PointerIcon(android.view.PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW)
private val ResizeVertical = PointerIcon(android.view.PointerIcon.TYPE_VERTICAL_DOUBLE_ARROW)

/**
 * Drags guides with the Move tool (or Ctrl + any tool), creates guides dragged out of the rulers,
 * deletes a guide dropped back on a ruler, and snaps while dragging (Shift = snap to ruler ticks).
 */
@Stable
class GuideDragger(
    private val aids: () -> ViewAids,
    private val update: (ViewAids) -> Unit,
    private val tool: () -> ToolId,
    private val document: () -> DocSize,
) : CanvasInterceptor {
    var indicator by mutableStateOf<SnapIndicator?>(null)
        private set
    private var dragging: Int? = null

    private fun grabbable(m: Modifiers) = aids().showGuides && !aids().lockGuides && (tool() == ToolId.MOVE || m.ctrl)

    private fun hit(p: ViewPoint, vp: ViewportState): Int? = aids().guideAt(vp.viewToDoc(p), GRAB_VIEW_PX / vp.zoom)

    override fun hoverCursor(p: ViewPoint, modifiers: Modifiers, viewport: ViewportState): PointerIcon? {
        if (!grabbable(modifiers)) return null
        val i = hit(p, viewport) ?: return null
        return if (aids().guides[i].axis == GuideAxis.VERTICAL) ResizeHorizontal else ResizeVertical
    }

    override fun onPointer(event: ToolPointerEvent, ctx: ToolContext): Boolean {
        when (event.phase) {
            PointerPhase.DOWN -> {
                if (!grabbable(event.modifiers)) return false
                dragging = hit(event.view, ctx.viewport) ?: return false
            }

            PointerPhase.MOVE -> drag(event.view, ctx.viewport, event.modifiers.shift)

            PointerPhase.UP -> end(event.view, ctx.viewport)

            PointerPhase.CANCEL -> {
                dragging = null
                indicator = null
            }
        }
        return true
    }

    /** Ruler drag started: a new guide follows the pointer. */
    fun beginNew(axis: GuideAxis, at: ViewPoint, viewport: ViewportState) {
        val a = aids()
        update(a.copy(showGuides = true).addGuide(Guide(axis, 0f)))
        dragging = a.guides.size
        drag(at, viewport, snapToTicks = false)
    }

    fun drag(at: ViewPoint, viewport: ViewportState, snapToTicks: Boolean) {
        val i = dragging ?: return
        val a = aids()
        val g = a.guides.getOrNull(i) ?: return
        val d = viewport.viewToDoc(at)
        val raw = if (g.axis == GuideAxis.VERTICAL) d.x else d.y
        val threshold = Snapping.THRESHOLD_VIEW_PX / viewport.zoom
        val targets = if (snapToTicks) {
            val step = RulerScale.majorStep(viewport.zoom) / RulerScale.minorCount(RulerScale.majorStep(viewport.zoom))
            listOf(SnapTarget(Math.round(raw / step) * step, app.croptop.core.model.SnapKind.GRID))
        } else {
            a.removeGuide(i).snapTargets(g.axis, document(), raw)
        }
        val s = Snapping.snap(raw, targets, if (snapToTicks) Float.MAX_VALUE else threshold)
        indicator = s.target?.let { SnapIndicator(g.axis, s.value) }
        update(a.moveGuide(i, Math.round(s.value * 10f) / 10f))
    }

    fun end(at: ViewPoint, viewport: ViewportState) {
        val i = dragging ?: return
        dragging = null
        indicator = null
        // Dropped outside the canvas (on a ruler or off-window): remove, like Photoshop.
        if (at.x < 0f || at.y < 0f || at.x > viewport.viewWidth || at.y > viewport.viewHeight) update(aids().removeGuide(i))
    }

    companion object {
        const val GRAB_VIEW_PX = 4f
    }
}

/** Grid, guides, pixel grid and snap feedback over the canvas; draws only, never takes input. */
@Composable
fun ViewAidsOverlay(viewport: ViewportState, document: DocSize, aids: ViewAids, indicator: SnapIndicator?, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize()) {
        if (viewport.viewWidth == 0) return@Canvas
        if (viewport.zoom >= PIXEL_GRID_ZOOM && viewport.rotationDegrees == 0f) {
            docLines(viewport, document, 1f, OverlayColors.PixelGrid)
        }
        if (aids.grid) {
            val g = aids.gridSettings
            val minor = g.spacing / g.subdivisions
            if (g.subdivisions > 1 && minor * viewport.zoom >= 6f) docLines(viewport, document, minor, OverlayColors.GridMinor)
            if (g.spacing * viewport.zoom >= 4f) docLines(viewport, document, g.spacing, OverlayColors.Grid)
        }
        if (aids.showGuides) {
            aids.guides.forEach { guideLine(viewport, document, it.axis, it.position, OverlayColors.Guide, extend = true) }
        }
        indicator?.let { guideLine(viewport, document, it.axis, it.position, OverlayColors.Snap, extend = true, dashed = true) }
    }
}

private const val PIXEL_GRID_ZOOM = 8f

private fun DrawScope.guideLine(
    vp: ViewportState,
    doc: DocSize,
    axis: GuideAxis,
    pos: Float,
    color: Color,
    extend: Boolean,
    dashed: Boolean = false,
) {
    // Guides span the whole view, like Photoshop, so they stay visible past the document edges.
    val far = if (extend) (vp.viewWidth + vp.viewHeight) / vp.zoom else 0f
    val (a, b) = if (axis == GuideAxis.VERTICAL) {
        DocPoint(pos, -far) to DocPoint(pos, doc.height + far)
    } else {
        DocPoint(-far, pos) to DocPoint(doc.width + far, pos)
    }
    val va = vp.docToView(a)
    val vb = vp.docToView(b)
    drawLine(
        color,
        Offset(va.x, va.y),
        Offset(vb.x, vb.y),
        strokeWidth = 1f,
        pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(6f, 4f)) else null,
    )
}

/** Lines every [step] doc units across the document, clipped to what's visible. */
private fun DrawScope.docLines(vp: ViewportState, doc: DocSize, step: Float, color: Color) {
    val corners = listOf(Offset.Zero, Offset(size.width, 0f), Offset(0f, size.height), Offset(size.width, size.height))
        .map { vp.viewToDoc(ViewPoint(it.x, it.y)) }
    val x0 = maxOf(0f, corners.minOf { it.x })
    val x1 = minOf(doc.width.toFloat(), corners.maxOf { it.x })
    val y0 = maxOf(0f, corners.minOf { it.y })
    val y1 = minOf(doc.height.toFloat(), corners.maxOf { it.y })
    if (x1 <= x0 || y1 <= y0) return
    RulerScale.ticks(x0, x1, step).forEach { x ->
        val a = vp.docToView(DocPoint(x, y0))
        val b = vp.docToView(DocPoint(x, y1))
        drawLine(color, Offset(a.x, a.y), Offset(b.x, b.y), 1f)
    }
    RulerScale.ticks(y0, y1, step).forEach { y ->
        val a = vp.docToView(DocPoint(x0, y))
        val b = vp.docToView(DocPoint(x1, y))
        drawLine(color, Offset(a.x, a.y), Offset(b.x, b.y), 1f)
    }
}

val RulerThickness: Dp = 20.dp

/**
 * Rulers around the canvas (Ctrl+R). Drag from the top ruler for a horizontal guide, from the left
 * ruler for a vertical one. Ticks follow the view; with a rotated view the rulers stay blank.
 */
@Composable
fun WithRulers(
    show: Boolean,
    viewport: ViewportState,
    hover: () -> ViewPoint?,
    dragger: GuideDragger,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (!show) {
        Box(modifier) { content() }
        return
    }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(RulerThickness)) {
            Box(Modifier.size(RulerThickness).background(Ae.colors.surface1))
            Ruler(GuideAxis.HORIZONTAL, viewport, hover, dragger, Modifier.weight(1f).fillMaxHeight())
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Ruler(GuideAxis.VERTICAL, viewport, hover, dragger, Modifier.width(RulerThickness).fillMaxHeight())
            Box(Modifier.weight(1f).fillMaxHeight()) { content() }
        }
    }
}

/** [guideAxis] is the axis of guides dragged out of this ruler: the top ruler makes HORIZONTAL guides. */
@Composable
private fun Ruler(guideAxis: GuideAxis, vp: ViewportState, hover: () -> ViewPoint?, dragger: GuideDragger, modifier: Modifier) {
    val c = Ae.colors
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = c.textTertiary, fontSize = 9.sp)
    val top = guideAxis == GuideAxis.HORIZONTAL
    val current by rememberUpdatedState(vp)
    Canvas(
        modifier
            .background(c.surface1)
            .semantics { contentDescription = if (top) "Horizontal ruler" else "Vertical ruler" }
            .pointerHoverIcon(
                PointerIcon(if (top) android.view.PointerIcon.TYPE_VERTICAL_DOUBLE_ARROW else android.view.PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW),
            )
            .pointerInput(guideAxis) {
                val thick = RulerThickness.toPx()

                // Pointer in canvas coordinates: the ruler sits [thick] before the canvas on its own axis.
                fun toCanvas(o: Offset) = if (top) ViewPoint(o.x, o.y - thick) else ViewPoint(o.x - thick, o.y)
                awaitEachGesture {
                    val down = awaitFirstDown()
                    dragger.beginNew(guideAxis, toCanvas(down.position), current)
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) {
                            dragger.end(toCanvas(ch.position), current)
                            return@awaitEachGesture
                        }
                        dragger.drag(toCanvas(ch.position), current, snapToTicks = ev.keyboardModifiers.isShiftPressed)
                        ch.consume()
                    }
                    dragger.end(ViewPoint(-1f, -1f), current)
                }
            },
    ) {
        val edge = if (top) size.height else size.width
        if (top) {
            drawLine(c.lineHairline, Offset(0f, edge - 0.5f), Offset(size.width, edge - 0.5f), 1f)
        } else {
            drawLine(c.lineHairline, Offset(edge - 0.5f, 0f), Offset(edge - 0.5f, size.height), 1f)
        }
        if (vp.rotationDegrees != 0f || vp.viewWidth == 0) return@Canvas
        val length = if (top) size.width else size.height

        // Along this ruler the doc coordinate is x for the top ruler, y for the left one.
        fun docAt(v: Float) = if (top) vp.viewToDoc(ViewPoint(v, 0f)).x else vp.viewToDoc(ViewPoint(0f, v)).y
        fun viewAt(d: Float) = if (top) vp.docToView(DocPoint(d, 0f)).x else vp.docToView(DocPoint(0f, d)).y
        val step = RulerScale.majorStep(vp.zoom)
        val minor = step / RulerScale.minorCount(step)
        RulerScale.ticks(docAt(0f), docAt(length), minor).forEach { d ->
            val v = viewAt(d)
            val major = kotlin.math.abs(d / step - Math.round(d / step)) < 1e-3f
            val tick = if (major) edge else edge * 0.3f
            if (top) {
                drawLine(
                    c.lineStrong,
                    Offset(v, edge - tick),
                    Offset(v, edge),
                    1f,
                )
            } else {
                drawLine(c.lineStrong, Offset(edge - tick, v), Offset(edge, v), 1f)
            }
            if (major) {
                val text = measurer.measure(RulerScale.label(d), labelStyle)
                if (top) {
                    drawText(text, topLeft = Offset(v + 3f, 1f))
                } else {
                    // Vertical ruler labels read bottom-to-top like Photoshop's.
                    val px = 1f
                    val py = v + 3f + text.size.width
                    drawContext.canvas.save()
                    drawContext.canvas.translate(px, py)
                    drawContext.canvas.rotate(-90f)
                    drawText(text, topLeft = Offset.Zero)
                    drawContext.canvas.restore()
                }
            }
        }
        // Read in the draw phase: hover moves only redraw the ruler.
        hover()?.let { h ->
            val v = if (top) h.x else h.y
            if (top) {
                drawLine(
                    OverlayColors.Guide,
                    Offset(v, 0f),
                    Offset(v, edge),
                    1f,
                )
            } else {
                drawLine(OverlayColors.Guide, Offset(0f, v), Offset(edge, v), 1f)
            }
        }
    }
}
