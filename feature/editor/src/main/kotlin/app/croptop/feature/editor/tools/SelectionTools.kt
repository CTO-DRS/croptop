package app.croptop.feature.editor.tools

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.component.AeCheckbox
import app.croptop.core.designsystem.component.AeDropdown
import app.croptop.core.designsystem.component.AeBarSlider
import app.croptop.core.designsystem.component.AeNumberField
import app.croptop.core.designsystem.component.AeText
import app.croptop.core.designsystem.theme.Ae
import app.croptop.core.input.Modifiers
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocRect
import app.croptop.core.model.DocRectF
import app.croptop.core.model.LayerId
import app.croptop.core.model.SelectionOp
import app.croptop.core.model.SelectionShape
import app.croptop.core.model.ToolId
import app.croptop.core.model.ViewPoint
import app.croptop.core.model.ViewportState
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Shift adds, Alt subtracts, Shift+Alt intersects (decided when the gesture starts, like Photoshop). */
internal fun selectionOp(m: Modifiers): SelectionOp = when {
    m.shift && m.alt -> SelectionOp.INTERSECT
    m.shift -> SelectionOp.ADD
    m.alt -> SelectionOp.SUBTRACT
    else -> SelectionOp.REPLACE
}

internal fun selectionModeCursorOperation(tool: ToolId?, modifiers: Modifiers): SelectionOp? {
    val selectionTool = when (tool) {
        ToolId.RECT_MARQUEE,
        ToolId.ELLIPSE_MARQUEE,
        ToolId.LASSO,
        ToolId.POLYGONAL_LASSO,
        ToolId.MAGNETIC_LASSO,
        ToolId.OBJECT_SELECT,
        ToolId.MAGIC_WAND,
        -> true

        else -> false
    }
    if (!selectionTool) return null
    return selectionOp(modifiers).takeIf { it != SelectionOp.REPLACE }
}

/** Small additive/subtractive/intersection badge beside selection-tool cursors. */
@Composable
fun SelectionModeCursorOverlay(tool: ToolId?, hover: ViewPoint?, modifiers: Modifiers) {
    val at = hover ?: return
    val op = selectionModeCursorOperation(tool, modifiers) ?: return
    Canvas(Modifier.fillMaxSize()) {
        val center = Offset(at.x + 11.dp.toPx(), at.y + 11.dp.toPx())
        val radius = 6.dp.toPx()
        drawCircle(Color.White, radius, center)
        drawCircle(Color.Black, radius, center, style = Stroke(0.8.dp.toPx()))
        val arm = 2.5.dp.toPx()
        val lines = when (op) {
            SelectionOp.ADD -> listOf(
                Offset(center.x - arm, center.y) to Offset(center.x + arm, center.y),
                Offset(center.x, center.y - arm) to Offset(center.x, center.y + arm),
            )

            SelectionOp.SUBTRACT -> listOf(Offset(center.x - arm, center.y) to Offset(center.x + arm, center.y))

            SelectionOp.INTERSECT -> listOf(
                Offset(center.x - arm, center.y - arm) to Offset(center.x + arm, center.y + arm),
                Offset(center.x + arm, center.y - arm) to Offset(center.x - arm, center.y + arm),
            )

            SelectionOp.REPLACE -> emptyList()
        }
        lines.forEach { (start, end) ->
            drawLine(Color.White, start, end, 2.6.dp.toPx())
            drawLine(Color.Black, start, end, 1.1.dp.toPx())
        }
    }
}

/** A document-space path drawn in view space (rotation/flip aware). */
private fun DrawScope.docPath(vp: ViewportState, points: List<DocPoint>, close: Boolean): Path = Path().apply {
    points.forEachIndexed { i, p ->
        val v = vp.docToView(p)
        if (i == 0) moveTo(v.x, v.y) else lineTo(v.x, v.y)
    }
    if (close) close()
}

private fun rectCorners(r: DocRectF) = listOf(
    DocPoint(r.left, r.top),
    DocPoint(r.right, r.top),
    DocPoint(r.right, r.bottom),
    DocPoint(r.left, r.bottom),
)

private fun ellipsePoints(r: DocRectF, n: Int = 72) = (0 until n).map { i ->
    val a = i * 2.0 * Math.PI / n
    DocPoint((r.center.x + r.width / 2 * kotlin.math.cos(a)).toFloat(), (r.center.y + r.height / 2 * kotlin.math.sin(a)).toFloat())
}

/** Marching ants: black/white dashes whose phase animates, so the edge reads on any image. */
@Composable
fun MarchingAnts(points: List<DocPoint>, viewport: ViewportState, closed: Boolean = true) {
    val phase = if (app.croptop.core.designsystem.theme.Ae.motion.fastMillis == 0) {
        0f
    } else {
        val animatedPhase by rememberInfiniteTransition(label = "ants").animateFloat(
            0f,
            16f,
            infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Restart),
            label = "phase",
        )
        animatedPhase
    }
    Canvas(Modifier.fillMaxSize()) {
        if (points.size < 2) return@Canvas
        val path = docPath(viewport, points, closed)
        drawPath(path, Color.White, style = Stroke(1.dp.toPx()))
        drawPath(
            path,
            Color.Black,
            style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()), phase)),
        )
    }
}

/** The document's selection outline: the exact shape when known, else its bounds. */
@Composable
fun SelectionOverlay(bounds: DocRect?, shape: SelectionShape?, viewport: ViewportState) {
    bounds ?: return
    val points = when {
        shape is SelectionShape.Ellipse && shape.rect.toDocRect() == bounds -> ellipsePoints(shape.rect)
        shape is SelectionShape.Polygon && shape.bounds.toDocRect() == bounds -> shape.points
        else -> rectCorners(DocRectF.of(bounds))
    }
    MarchingAnts(points, viewport)
}

/**
 * Rectangular / Elliptical Marquee (M): drag a box; Shift (after starting) keeps it square, Alt draws
 * from the center. A click without a drag deselects.
 */
class MarqueeTool(
    override val tool: ToolId,
    private val ellipse: Boolean,
    private val hasSelection: () -> Boolean,
    private val apply: (SelectionShape, SelectionOp, Float) -> Boolean,
) : ToolController {
    var feather by mutableStateOf(0f)
    var style by mutableStateOf(MarqueeStyle.NORMAL)
    var onActionCompleted: (String) -> Unit = {}

    /** Width:height for Fixed Ratio, or pixels for Fixed Size. */
    var fixedWidth by mutableStateOf(1f)
    var fixedHeight by mutableStateOf(1f)
    private var start: DocPoint? = null
    private var last: DocPoint? = null
    private var op = SelectionOp.REPLACE
    private var rect by mutableStateOf<DocRectF?>(null)

    /** Space held during a drag moves the box instead of resizing it (Photoshop). */
    var repositioning = false

    val dragging: Boolean get() = start != null

    override fun cursor(modifiers: Modifiers): PointerIcon = PointerIcon.Crosshair

    override fun onFocusLost() {
        repositioning = false
    }

    override fun onPointer(event: ToolPointerEvent, ctx: ToolContext): Boolean {
        when (event.phase) {
            PointerPhase.DOWN -> {
                start = event.doc
                last = event.doc
                op = selectionOp(event.modifiers)
                rect = if (style == MarqueeStyle.FIXED_SIZE) box(event.doc, event.doc, event.modifiers) else null
            }

            PointerPhase.MOVE -> start?.let { s ->
                follow(s, event.doc)
                rect = box(start!!, event.doc, event.modifiers)
            }

            PointerPhase.UP -> {
                start?.let { follow(it, event.doc) }
                val r = start?.let { box(it, event.doc, event.modifiers) }
                // A Fixed Size click places the box; otherwise a click without a drag deselects.
                val dragged = r != null &&
                    (style == MarqueeStyle.FIXED_SIZE || r.width * ctx.viewport.zoom >= 2f || r.height * ctx.viewport.zoom >= 2f)
                when {
                    dragged -> apply(if (ellipse) SelectionShape.Ellipse(r!!) else SelectionShape.Rect(r!!), op, feather)

                    // Click: deselect (only when there is something to deselect).
                    op == SelectionOp.REPLACE && hasSelection() -> apply(SelectionShape.Rect(DocRectF(0f, 0f, 0f, 0f)), op, 0f)
                }
                reset()
            }

            PointerPhase.CANCEL -> reset()
        }
        return true
    }

    private fun reset() {
        start = null
        last = null
        rect = null
        repositioning = false
    }

    /** While repositioning, the anchor travels with the pointer so the box keeps its size. */
    private fun follow(s: DocPoint, e: DocPoint) {
        val prev = last ?: e
        if (repositioning) start = DocPoint(s.x + e.x - prev.x, s.y + e.y - prev.y)
        last = e
    }

    private fun box(s: DocPoint, e: DocPoint, m: Modifiers): DocRectF {
        var dx = e.x - s.x
        var dy = e.y - s.y
        when (style) {
            MarqueeStyle.NORMAL -> Unit

            MarqueeStyle.FIXED_RATIO -> if (fixedWidth > 0f && fixedHeight > 0f) {
                // The longer drag axis wins; the other follows the ratio.
                val w = max(abs(dx), abs(dy) * fixedWidth / fixedHeight)
                dx = if (dx < 0) -w else w
                dy = (if (dy < 0) -w else w) * fixedHeight / fixedWidth
            }

            MarqueeStyle.FIXED_SIZE -> {
                val halfExtent = if (m.alt) 0.5f else 1f
                dx = if (dx < 0) -fixedWidth * halfExtent else fixedWidth * halfExtent
                dy = if (dy < 0) -fixedHeight * halfExtent else fixedHeight * halfExtent
            }
        }
        // Shift constrains only for a fresh selection; with a Shift-started add, it already means "add".
        if (m.shift && op == SelectionOp.REPLACE && style == MarqueeStyle.NORMAL) {
            val d = max(abs(dx), abs(dy))
            dx = if (dx < 0) -d else d
            dy = if (dy < 0) -d else d
        }
        return if (m.alt) {
            DocRectF(s.x - abs(dx), s.y - abs(dy), s.x + abs(dx), s.y + abs(dy))
        } else {
            DocRectF.of(s, DocPoint(s.x + dx, s.y + dy))
        }
    }

    @Composable
    override fun Overlay(viewport: ViewportState, hover: ViewPoint?) {
        val r = rect ?: return
        MarchingAnts(if (ellipse) ellipsePoints(r) else rectCorners(r), viewport)
    }

    @Composable
    override fun Options() {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AeText("Style", style = Ae.type.caption, color = Ae.colors.textSecondary)
            AeDropdown(
                MarqueeStyle.entries,
                style,
                {
                    style = it
                    onActionCompleted("Set marquee style to ${it.label.lowercase()}")
                },
                { it.label },
            )
            if (style != MarqueeStyle.NORMAL) {
                val px = style == MarqueeStyle.FIXED_SIZE
                val range = if (px) 1f..30000f else 0.01f..1000f
                AeNumberField(
                    fixedWidth,
                    { fixedWidth = it },
                    range = range,
                    decimals = if (px) 0 else 2,
                    unit = if (px) "px" else null,
                    width = 72.dp,
                    contentDescription = "Width",
                    onCommit = { onActionCompleted("Set marquee width") },
                )
                AeText(if (px) "×" else ":", style = Ae.type.caption, color = Ae.colors.textSecondary)
                AeNumberField(
                    fixedHeight,
                    { fixedHeight = it },
                    range = range,
                    decimals = if (px) 0 else 2,
                    unit = if (px) "px" else null,
                    width = 72.dp,
                    contentDescription = "Height",
                    onCommit = { onActionCompleted("Set marquee height") },
                )
            }
            SelectionOptions(feather, {
                feather = it
            }, {
                onActionCompleted(if (ellipse) "Set ellipse feather" else "Set marquee feather")
            }, if (ellipse) "Alt draws from the center · Space moves" else "Shift for a square · Alt from the center · Space moves")
        }
    }
}

enum class MarqueeStyle(val label: String) { NORMAL("Normal"), FIXED_RATIO("Fixed Ratio"), FIXED_SIZE("Fixed Size") }

/** Lasso (L): freehand; Polygonal Lasso: click points, double-click or click the first point to close. */
class LassoTool(
    override val tool: ToolId,
    private val polygonal: Boolean,
    private val apply: (SelectionShape, SelectionOp, Float) -> Boolean,
) : ToolController {
    var feather by mutableStateOf(0f)
    var onActionCompleted: (String) -> Unit = {}
    private var points by mutableStateOf<List<DocPoint>>(emptyList())
    private var op = SelectionOp.REPLACE

    override fun cursor(modifiers: Modifiers): PointerIcon = PointerIcon.Crosshair

    override fun onPointer(event: ToolPointerEvent, ctx: ToolContext): Boolean {
        val zoom = ctx.viewport.zoom
        if (polygonal) {
            if (event.phase != PointerPhase.DOWN) return true
            if (points.isEmpty()) op = selectionOp(event.modifiers)
            val first = points.firstOrNull()
            val closes =
                event.clickCount >= 2 || (first != null && points.size >= 3 && hypot(event.doc.x - first.x, event.doc.y - first.y) * zoom < CLOSE_PX)
            if (closes) finish() else points = points + event.doc
            return true
        }
        when (event.phase) {
            PointerPhase.DOWN -> {
                op = selectionOp(event.modifiers)
                points = listOf(event.doc)
            }

            PointerPhase.MOVE -> {
                val last = points.lastOrNull()
                if (last == null || hypot(event.doc.x - last.x, event.doc.y - last.y) * zoom >= MIN_STEP_PX) points = points + event.doc
            }

            PointerPhase.UP -> finish()

            PointerPhase.CANCEL -> points = emptyList()
        }
        return true
    }

    private fun finish() {
        if (points.size >= 3) apply(SelectionShape.Polygon(points), op, feather)
        points = emptyList()
    }

    /** Backspace removes the polygon's last point (Photoshop); false when there is nothing to remove. */
    fun removeLastPoint(): Boolean {
        if (!polygonal || points.isEmpty()) return false
        points = points.dropLast(1)
        return true
    }

    /** Esc abandons a polygon in progress; Enter closes it. */
    fun onKey(enter: Boolean): Boolean {
        if (points.isEmpty()) return false
        if (enter) finish() else points = emptyList()
        return true
    }

    @Composable
    override fun Overlay(viewport: ViewportState, hover: ViewPoint?) {
        if (points.size >= 2) MarchingAnts(points, viewport, closed = !polygonal)
    }

    @Composable
    override fun Options() {
        SelectionOptions(
            feather,
            { feather = it },
            { onActionCompleted(if (polygonal) "Set polygonal lasso feather" else "Set lasso feather") },
            if (polygonal) "Click to add points · double-click or click the start to close · Esc cancels" else "Drag around the area",
        )
    }

    private companion object {
        const val MIN_STEP_PX = 2f
        const val CLOSE_PX = 8f
    }
}

@Composable
private fun SelectionOptions(feather: Float, onFeather: (Float) -> Unit, onFeatherCommit: () -> Unit, hint: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AeBarSlider(
            "Feather",
            feather,
            onFeather,
            range = 0f..1000f,
            defaultValue = 0f,
            curve = 3f,
            decimals = 1,
            unit = "px",
            width = 160.dp,
            contentDescription = "Feather",
            onValueChangeFinished = onFeatherCommit,
        )
        AeText("Shift add · Alt subtract · Shift+Alt intersect · $hint", style = Ae.type.caption, color = Ae.colors.textTertiary)
    }
}

/**
 * Move (V): drag moves the selected layers (one history step per drag); with Auto-Select (or Ctrl held)
 * the press picks the layer under the pointer first. Shift keeps the move horizontal or vertical.
 */
class MoveTool(
    private val layerAt: (DocPoint) -> LayerId?,
    private val selectLayer: (LayerId) -> Unit,
    private val selectedBounds: () -> DocRect?,
    private val viewAids: () -> app.croptop.core.model.ViewAids = { app.croptop.core.model.ViewAids.Default },
    private val otherLayerBounds: () -> List<DocRect> = { emptyList() },
    private val commit: (Float, Float) -> Boolean,
    private val selectionBounds: () -> DocRect? = { null },
) : ToolController {
    constructor(
        layerAt: (DocPoint) -> LayerId?,
        selectLayer: (LayerId) -> Unit,
        selectedBounds: () -> DocRect?,
        commit: (Float, Float) -> Boolean,
    ) : this(layerAt, selectLayer, selectedBounds, { app.croptop.core.model.ViewAids.Default }, { emptyList() }, commit)

    var onActionCompleted: (String) -> Unit = {}
    override val tool = ToolId.MOVE
    var autoSelect by mutableStateOf(true)
    private var start: DocPoint? = null
    private var startBounds: DocRectF? = null
    private var offset by mutableStateOf<DocPoint?>(null)
    private var snapX by mutableStateOf<Float?>(null)
    private var snapY by mutableStateOf<Float?>(null)

    override fun cursor(modifiers: Modifiers): PointerIcon = PointerIcon(android.view.PointerIcon.TYPE_ALL_SCROLL)

    override fun onPointer(event: ToolPointerEvent, ctx: ToolContext): Boolean {
        when (event.phase) {
            PointerPhase.DOWN -> {
                if (autoSelect || event.modifiers.ctrl) layerAt(event.doc)?.let(selectLayer)
                start = event.doc
                startBounds = selectedBounds()?.let { DocRectF.of(it) }
                offset = DocPoint.Zero
                snapX = null
                snapY = null
            }

            PointerPhase.MOVE -> start?.let { s ->
                var dx = event.doc.x - s.x
                var dy = event.doc.y - s.y
                val horizontalLock = event.modifiers.shift && abs(dx) >= abs(dy)
                val verticalLock = event.modifiers.shift && !horizontalLock
                if (horizontalLock) {
                    dy = 0f
                } else if (verticalLock) {
                    dx = 0f
                }
                val bounds = startBounds
                val aids = viewAids()
                if (bounds != null && aids.snap) {
                    val threshold = app.croptop.core.model.Snapping.THRESHOLD_VIEW_PX / ctx.viewport.zoom.coerceAtLeast(1e-4f)
                    val others = otherLayerBounds()
                    val extras = others.flatMap { rect ->
                        listOf(
                            rect.left.toFloat(),
                            (rect.left + rect.right) / 2f,
                            rect.right.toFloat(),
                        ).map { app.croptop.core.model.SnapTarget(it, app.croptop.core.model.SnapKind.LAYER) }
                    }
                    val selection = selectionBounds()
                    val selectionX = selection?.let { rect ->
                        listOf(rect.left.toFloat(), (rect.left + rect.right) / 2f, rect.right.toFloat())
                            .map { app.croptop.core.model.SnapTarget(it, app.croptop.core.model.SnapKind.SELECTION) }
                    }.orEmpty()
                    val xTargets = aids.snapTargets(
                        app.croptop.core.model.GuideAxis.VERTICAL,
                        ctx.document,
                        bounds.center.x + dx,
                        extras + selectionX,
                    )
                    val yExtras = others.flatMap { rect ->
                        listOf(rect.top.toFloat(), (rect.top + rect.bottom) / 2f, rect.bottom.toFloat())
                            .map { app.croptop.core.model.SnapTarget(it, app.croptop.core.model.SnapKind.LAYER) }
                    }
                    val selectionY = selection?.let { rect ->
                        listOf(rect.top.toFloat(), (rect.top + rect.bottom) / 2f, rect.bottom.toFloat())
                            .map { app.croptop.core.model.SnapTarget(it, app.croptop.core.model.SnapKind.SELECTION) }
                    }.orEmpty()
                    val yTargets = aids.snapTargets(
                        app.croptop.core.model.GuideAxis.HORIZONTAL,
                        ctx.document,
                        bounds.center.y + dy,
                        yExtras + selectionY,
                    )
                    val (snapDx, targetX) = if (verticalLock) {
                        0f to null
                    } else {
                        app.croptop.core.model.Snapping.snapSpan(
                            listOf(bounds.left + dx, bounds.center.x + dx, bounds.right + dx),
                            xTargets,
                            threshold,
                        )
                    }
                    val (snapDy, targetY) = if (horizontalLock) {
                        0f to null
                    } else {
                        app.croptop.core.model.Snapping.snapSpan(
                            listOf(bounds.top + dy, bounds.center.y + dy, bounds.bottom + dy),
                            yTargets,
                            threshold,
                        )
                    }
                    dx += snapDx
                    dy += snapDy
                    snapX = targetX?.position
                    snapY = targetY?.position
                } else {
                    snapX = null
                    snapY = null
                }
                offset = DocPoint(dx, dy)
            }

            PointerPhase.UP -> {
                // Sub-pixel moves are real moves (the engine interpolates); only a true no-op is skipped.
                offset?.let { o -> if (abs(o.x) > MOVE_EPSILON || abs(o.y) > MOVE_EPSILON) commit(o.x, o.y) }
                start = null
                startBounds = null
                offset = null
                snapX = null
                snapY = null
            }

            PointerPhase.CANCEL -> {
                start = null
                startBounds = null
                offset = null
                snapX = null
                snapY = null
            }
        }
        return true
    }

    @Composable
    override fun Overlay(viewport: ViewportState, hover: ViewPoint?) {
        val b = selectedBounds() ?: return
        val o = offset ?: DocPoint.Zero
        val r = DocRectF.of(b).translate(o.x, o.y)
        val accent = Ae.colors.accent
        Canvas(Modifier.fillMaxSize()) {
            drawPath(docPath(viewport, rectCorners(r), close = true), accent, style = Stroke(1.dp.toPx()))
            if (offset != null) {
                val a = viewport.docToView(DocPoint(r.left, r.top))
                drawCircle(accent, 3.dp.toPx(), Offset(a.x, a.y))
            }
            val docCorners = listOf(
                ViewPoint(0f, 0f),
                ViewPoint(size.width, 0f),
                ViewPoint(size.width, size.height),
                ViewPoint(0f, size.height),
            ).map(viewport::viewToDoc)
            val minX = docCorners.minOf { it.x }
            val maxX = docCorners.maxOf { it.x }
            val minY = docCorners.minOf { it.y }
            val maxY = docCorners.maxOf { it.y }
            snapX?.let { x ->
                val a = viewport.docToView(DocPoint(x, minY))
                val b = viewport.docToView(DocPoint(x, maxY))
                drawLine(accent, Offset(a.x, a.y), Offset(b.x, b.y), 1.dp.toPx())
            }
            snapY?.let { y ->
                val a = viewport.docToView(DocPoint(minX, y))
                val b = viewport.docToView(DocPoint(maxX, y))
                drawLine(accent, Offset(a.x, a.y), Offset(b.x, b.y), 1.dp.toPx())
            }
        }
    }

    @Composable
    override fun Options() {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AeCheckbox(autoSelect, {
                autoSelect = it
                onActionCompleted(if (it) "Enable auto-select layer" else "Disable auto-select layer")
            }, "Auto-Select Layer")
            AeText(
                "Arrow keys nudge 1 px · Shift+arrow 10 px · Shift-drag keeps it straight",
                style = Ae.type.caption,
                color = Ae.colors.textTertiary,
            )
        }
    }
}

private const val MOVE_EPSILON = 1e-3f

/** Union of the selected layers' bounds (null bounds = the whole canvas). */
fun selectedLayerBounds(bounds: List<DocRect?>, canvas: DocRect): DocRect? {
    if (bounds.isEmpty()) return null
    return bounds.map {
        it ?: canvas
    }.reduce { a, b -> DocRect(min(a.left, b.left), min(a.top, b.top), max(a.right, b.right), max(a.bottom, b.bottom)) }
}
