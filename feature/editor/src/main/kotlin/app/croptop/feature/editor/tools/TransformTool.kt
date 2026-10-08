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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.component.AeButton
import app.croptop.core.designsystem.component.AeButtonStyle
import app.croptop.core.designsystem.component.AeIconButton
import app.croptop.core.designsystem.component.AeNumberField
import app.croptop.core.designsystem.component.AeText
import app.croptop.core.designsystem.icon.AeIcons
import app.croptop.core.designsystem.theme.Ae
import app.croptop.core.engine.api.TransformSession
import app.croptop.core.input.Modifiers
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocRect
import app.croptop.core.model.DocRectF
import app.croptop.core.model.Matrix3
import app.croptop.core.model.ToolId
import app.croptop.core.model.ViewAids
import app.croptop.core.model.ViewPoint
import app.croptop.core.model.ViewportState
import kotlin.math.atan2
import kotlin.math.roundToInt

/**
 * Free Transform state as flip (about [original]'s center), scale-into-[local], then rotate by [angle]
 * about [local]'s center (pure, unit-tested). [original] is the box when the transform began.
 */
data class TransformBox(
    val original: DocRectF,
    val local: DocRectF = original,
    val angle: Float = 0f,
    val flipX: Boolean = false,
    val flipY: Boolean = false,
    /**
     * Distort/Perspective: where [original]'s corners (clockwise from top-left) end up. When set, it
     * replaces scale/rotate/flip and [matrix] is the projective map onto it.
     */
    val quad: List<DocPoint>? = null,
    /** Optional rotation pivot in document coordinates; null keeps it centered on the current box. */
    val referencePoint: DocPoint? = null,
) {
    init {
        require(quad == null || quad.size == 4) { "A distort quad has four corners" }
    }

    private val originalCorners: List<DocPoint> get() = listOf(
        DocPoint(original.left, original.top),
        DocPoint(original.right, original.top),
        DocPoint(original.right, original.bottom),
        DocPoint(original.left, original.bottom),
    )

    val matrix: Matrix3
        get() {
            quad?.let { q -> Homography.between(originalCorners, q)?.let { return it } }
            val kx = local.width / original.width
            val ky = local.height / original.height
            val scale = Matrix3(listOf(kx, 0f, local.left - original.left * kx, 0f, ky, local.top - original.top * ky, 0f, 0f, 1f))
            val flip = Matrix3.scale(if (flipX) -1f else 1f, if (flipY) -1f else 1f, original.center)
            return Matrix3.rotate(angle, referencePoint ?: local.center) * scale * flip
        }

    /** Corners of the transformed box, clockwise from top-left. */
    val corners: List<DocPoint> get() = quad ?: originalCorners.map(matrix::map)

    /** Ctrl-drag corner [i] by ([dx], [dy]); [perspective] (Ctrl+Alt+Shift) mirrors it on the same edge. */
    fun distorted(i: Int, dx: Float, dy: Float, perspective: Boolean): TransformBox {
        val q = corners.toMutableList()
        q[i] = DocPoint(q[i].x + dx, q[i].y + dy)
        if (perspective) {
            // The corner sharing the top or bottom edge moves the opposite way horizontally (keystone).
            val partner = i xor 1
            q[partner] = DocPoint(q[partner].x - dx, q[partner].y + dy)
        }
        return copy(quad = q)
    }

    /**
     * Ctrl-drag the midpoint of edge [e] (corner e → e+1): both corners slide along that edge by the
     * drag's component in the edge direction (skew).
     */
    fun skewed(e: Int, dx: Float, dy: Float): TransformBox {
        val q = corners.toMutableList()
        val a = q[e]
        val b = q[(e + 1) % 4]
        val len = kotlin.math.hypot(b.x - a.x, b.y - a.y).takeIf { it > 0f } ?: return this
        val ux = (b.x - a.x) / len
        val uy = (b.y - a.y) / len
        val t = dx * ux + dy * uy
        q[e] = DocPoint(a.x + ux * t, a.y + uy * t)
        q[(e + 1) % 4] = DocPoint(b.x + ux * t, b.y + uy * t)
        return copy(quad = q)
    }

    val isDistorted: Boolean get() = quad != null

    /** A document point in the box's unrotated frame. */
    fun toLocal(p: DocPoint): DocPoint = Matrix3.rotate(-angle, referencePoint ?: local.center).map(p)

    val widthPercent: Float get() = local.width / original.width * 100f
    val heightPercent: Float get() = local.height / original.height * 100f
    val isIdentity: Boolean get() = if (quad != null) quad == originalCorners else local == original && angle == 0f && !flipX && !flipY

    /**
     * Transform Again: the same scale, offset and rotation applied to [box], the current bounds of what
     * is being transformed (Photoshop repeats relative values, not the absolute matrix).
     */
    fun repeatOn(box: DocRectF): TransformBox {
        val kx = local.width / original.width
        val ky = local.height / original.height
        val left = box.left + (local.left - original.left)
        val top = box.top + (local.top - original.top)
        // A distortion repeats as the same corner positions relative to the box.
        val q = quad?.map { p ->
            DocPoint(box.left + (p.x - original.left) * box.width / original.width, box.top + (p.y - original.top) * box.height / original.height)
        }
        return TransformBox(box, DocRectF(left, top, left + box.width * kx, top + box.height * ky), angle, flipX, flipY, q)
    }

    /** Rotate 90° (Edit ▸ Transform ▸ Rotate 90°), normalized to −180…180. */
    fun rotated(degrees: Float): TransformBox = copy(angle = ((angle + degrees + 180f).mod(360f)) - 180f)

    /**
     * Alt-drag: [dragged] (the handle drag result) mirrored about [local]'s center so the opposite side
     * moves too. With [keepRatio], the larger of the two scales wins for both axes.
     */
    internal fun scaledFromCenter(dragged: DocRectF, handle: CropHandle, keepRatio: Boolean): DocRectF {
        val c = local.center
        var hw = when {
            handle.left -> c.x - dragged.left
            handle.right -> dragged.right - c.x
            else -> dragged.width / 2f
        }.coerceAtLeast(MIN_HALF)
        var hh = when {
            handle.top -> c.y - dragged.top
            handle.bottom -> dragged.bottom - c.y
            else -> dragged.height / 2f
        }.coerceAtLeast(MIN_HALF)
        if (keepRatio) {
            val k = maxOf(hw / (local.width / 2f), hh / (local.height / 2f))
            hw = local.width / 2f * k
            hh = local.height / 2f * k
        }
        return DocRectF(c.x - hw, c.y - hh, c.x + hw, c.y + hh)
    }

    private companion object {
        const val MIN_HALF = 0.5f
    }
}

/**
 * Free Transform (Ctrl+T) / Transform Selection. Corners scale proportionally (Shift frees them),
 * edges stretch, inside moves, outside rotates (Shift snaps to 15°). Enter / Apply commits one
 * history step through the engine's [TransformSession]; Esc / Cancel restores.
 */
class TransformTool(
    private val session: TransformSession,
    start: DocRectF,
    private val label: String,
    private val onCommit: (TransformSession) -> Boolean,
    private val onEnd: () -> Unit,
    private val onCommitted: (TransformBox) -> Unit = {},
    /** Whole-canvas size; with [onWarpCommit] it enables Warp mode (a draggable lattice that re-renders the layer on Apply). */
    private val canvas: app.croptop.core.model.DocSize? = null,
    private val onWarpCommit: ((app.croptop.core.model.WarpMesh) -> Boolean)? = null,
    startInWarp: Boolean = false,
) : ToolController {
    override val tool = ToolId.MOVE
    var onActionCompleted: (String) -> Unit = {}
    var viewAidsForSnap: () -> ViewAids = { ViewAids.Default }
    var otherLayerBounds: () -> List<DocRect> = { emptyList() }
    var selectionBoundsForSnap: () -> DocRect? = { null }
    private var box by mutableStateOf(TransformBox(start))
    private var snapX by mutableStateOf<Float?>(null)
    private var snapY by mutableStateOf<Float?>(null)
    private var grab: CropHandle? = null
    private var rotating = false
    private var grabLocal = DocPoint.Zero
    private var grabDoc = DocPoint.Zero
    private var grabBox = box
    private var distortCorner: Int? = null
    private var skewEdge: Int? = null
    private var perspective = false
    private var movingReferencePoint = false
    private var warp by mutableStateOf<WarpGrid?>(
        if (startInWarp && canvas != null && onWarpCommit != null) WarpGrid.identity(canvas) else null,
    )
    private var warpGrab: Int? = null
    private var lastDensity = 1f

    override fun cursor(modifiers: Modifiers): PointerIcon = PointerIcon.Crosshair

    /** Scale handles show the (rotated) resize direction, the inside moves, and outside the box rotates (grab hand). */
    override fun cursorAt(point: ViewPoint, modifiers: Modifiers, viewport: ViewportState): PointerIcon? {
        if (warp != null) return PointerIcon(android.view.PointerIcon.TYPE_HAND)
        val doc = viewport.viewToDoc(point)
        val slop = POINTER_HANDLE_RADIUS_DP * lastDensity / viewport.zoom.coerceAtLeast(1e-4f)
        if (box.isDistorted) {
            return PointerIcon(
                if (insideQuad(box.corners, doc)) android.view.PointerIcon.TYPE_ALL_SCROLL else android.view.PointerIcon.TYPE_GRAB,
            )
        }
        val handle = CropMath.handleAt(box.local, box.toLocal(doc), slop)
        return PointerIcon(transformCursorType(handle, box.angle))
    }


    private fun set(next: TransformBox) {
        box = next
        session.update(next.matrix)
    }

    private fun snapTranslation(dx: Float, dy: Float, source: TransformBox, ctx: ToolContext): DocPoint {
        val aids = viewAidsForSnap()
        if (!aids.snap) {
            snapX = null
            snapY = null
            return DocPoint(dx, dy)
        }
        val corners = source.corners
        val bounds = DocRectF(
            corners.minOf { it.x },
            corners.minOf { it.y },
            corners.maxOf { it.x },
            corners.maxOf { it.y },
        )
        val selection = selectionBoundsForSnap()
        val extras = otherLayerBounds().flatMap { rect ->
            listOf(rect.left.toFloat(), (rect.left + rect.right) / 2f, rect.right.toFloat())
                .map { app.croptop.core.model.SnapTarget(it, app.croptop.core.model.SnapKind.LAYER) }
        } + selection?.let { rect ->
            listOf(rect.left.toFloat(), (rect.left + rect.right) / 2f, rect.right.toFloat())
                .map { app.croptop.core.model.SnapTarget(it, app.croptop.core.model.SnapKind.SELECTION) }
        }.orEmpty()
        val extraY = otherLayerBounds().flatMap { rect ->
            listOf(rect.top.toFloat(), (rect.top + rect.bottom) / 2f, rect.bottom.toFloat())
                .map { app.croptop.core.model.SnapTarget(it, app.croptop.core.model.SnapKind.LAYER) }
        } + selection?.let { rect ->
            listOf(rect.top.toFloat(), (rect.top + rect.bottom) / 2f, rect.bottom.toFloat())
                .map { app.croptop.core.model.SnapTarget(it, app.croptop.core.model.SnapKind.SELECTION) }
        }.orEmpty()
        val xTargets = aids.snapTargets(
            app.croptop.core.model.GuideAxis.VERTICAL,
            ctx.document,
            bounds.center.x + dx,
            extras,
        )
        val yTargets = aids.snapTargets(
            app.croptop.core.model.GuideAxis.HORIZONTAL,
            ctx.document,
            bounds.center.y + dy,
            extraY,
        )
        val threshold = app.croptop.core.model.Snapping.THRESHOLD_VIEW_PX / ctx.viewport.zoom.coerceAtLeast(1e-4f)
        val (snapDx, targetX) = app.croptop.core.model.Snapping.snapSpan(
            listOf(bounds.left + dx, bounds.center.x + dx, bounds.right + dx),
            xTargets,
            threshold,
        )
        val (snapDy, targetY) = app.croptop.core.model.Snapping.snapSpan(
            listOf(bounds.top + dy, bounds.center.y + dy, bounds.bottom + dy),
            yTargets,
            threshold,
        )
        snapX = targetX?.position
        snapY = targetY?.position
        return DocPoint(dx + snapDx, dy + snapDy)
    }

    private fun onWarpPointer(grid: WarpGrid, event: ToolPointerEvent, ctx: ToolContext): Boolean {
        when (event.phase) {
            PointerPhase.DOWN -> warpGrab = grid.nodeAt(event.doc, handleHitRadiusPx(ctx.density, event.pointerKind) / ctx.viewport.zoom)
            PointerPhase.MOVE -> warpGrab?.let { warp = grid.moved(it, event.doc) }
            PointerPhase.UP, PointerPhase.CANCEL -> warpGrab = null
        }
        return true
    }

    override fun onPointer(event: ToolPointerEvent, ctx: ToolContext): Boolean {
        lastDensity = ctx.density
        warp?.let { return onWarpPointer(it, event, ctx) }
        when (event.phase) {
            PointerPhase.DOWN -> {
                grabBox = box
                grabDoc = event.doc
                grabLocal = box.toLocal(event.doc)
                snapX = null
                snapY = null
                val slop = handleHitRadiusPx(ctx.density, event.pointerKind) / ctx.viewport.zoom
                val m = event.modifiers
                val reference = box.referencePoint ?: box.local.center
                movingReferencePoint = !box.isDistorted && kotlin.math.hypot(reference.x - event.doc.x, reference.y - event.doc.y) <= slop * 1.5f
                if (movingReferencePoint) {
                    grab = null
                    rotating = false
                    distortCorner = null
                    skewEdge = null
                    return true
                }
                // Ctrl-drag a corner distorts; Ctrl+Alt+Shift is perspective (Photoshop).
                distortCorner = if (m.ctrl || m.meta) {
                    box.corners.indexOfFirst { kotlin.math.hypot(it.x - event.doc.x, it.y - event.doc.y) <= slop }.takeIf { it >= 0 }
                } else {
                    null
                }
                perspective = distortCorner != null && m.alt && m.shift
                // Ctrl-drag an edge midpoint skews.
                skewEdge = if ((m.ctrl || m.meta) && distortCorner == null) {
                    val q = box.corners
                    q.indices.firstOrNull { i ->
                        val mid = DocPoint((q[i].x + q[(i + 1) % 4].x) / 2f, (q[i].y + q[(i + 1) % 4].y) / 2f)
                        kotlin.math.hypot(mid.x - event.doc.x, mid.y - event.doc.y) <= slop
                    }
                } else {
                    null
                }
                grab = if (box.isDistorted) {
                    if (insideQuad(box.corners, event.doc)) CropHandle.MOVE else null
                } else {
                    CropMath.handleAt(box.local, grabLocal, slop)
                }
                rotating = distortCorner == null && skewEdge == null && grab == null
            }

            PointerPhase.MOVE -> {
                val b = grabBox
                if (movingReferencePoint) {
                    val reference = b.referencePoint ?: b.local.center
                    set(b.copy(referencePoint = DocPoint(reference.x + event.doc.x - grabDoc.x, reference.y + event.doc.y - grabDoc.y)))
                    return true
                }
                distortCorner?.let { i ->
                    set(b.distorted(i, event.doc.x - grabDoc.x, event.doc.y - grabDoc.y, perspective))
                    return true
                }
                skewEdge?.let { e ->
                    set(b.skewed(e, event.doc.x - grabDoc.x, event.doc.y - grabDoc.y))
                    return true
                }
                if (b.isDistorted) {
                    // A distorted box moves or rotates as a whole; scale handles don't apply.
                    val q = b.corners
                    val next = if (rotating) {
                        val c = DocPoint(q.map { it.x }.average().toFloat(), q.map { it.y }.average().toFloat())
                        val a0 = atan2(grabDoc.y - c.y, grabDoc.x - c.x)
                        val a1 = atan2(event.doc.y - c.y, event.doc.x - c.x)
                        val r = Matrix3.rotate(Math.toDegrees((a1 - a0).toDouble()).toFloat(), c)
                        q.map(r::map)
                    } else {
                        val delta = snapTranslation(event.doc.x - grabDoc.x, event.doc.y - grabDoc.y, b, ctx)
                        q.map { DocPoint(it.x + delta.x, it.y + delta.y) }
                    }
                    set(b.copy(quad = next))
                    return true
                }
                if (rotating) {
                    val c = b.referencePoint ?: b.local.center
                    val a0 = Math.toDegrees(atan2((grabDoc.y - c.y).toDouble(), (grabDoc.x - c.x).toDouble()))
                    val a1 = Math.toDegrees(atan2((event.doc.y - c.y).toDouble(), (event.doc.x - c.x).toDouble()))
                    var angle = (b.angle + (a1 - a0)).toFloat()
                    if (event.modifiers.shift) angle = (angle / SNAP_DEGREES).roundToInt() * SNAP_DEGREES
                    set(b.copy(angle = normalize(angle)))
                    return true
                }
                val h = grab ?: return true
                if (h == CropHandle.MOVE) {
                    val delta = snapTranslation(event.doc.x - grabDoc.x, event.doc.y - grabDoc.y, b, ctx)
                    set(b.copy(local = b.local.translate(delta.x, delta.y)))
                } else {
                    val corner = (h.left || h.right) && (h.top || h.bottom)
                    // Photoshop CC: corners keep proportions unless Shift is held; Alt scales from the center.
                    val ratio = if (corner && !event.modifiers.shift) b.local.width / b.local.height else null
                    val dragged = CropMath.drag(b.local, h, b.toLocal(event.doc), ratio)
                    set(b.copy(local = if (event.modifiers.alt) b.scaledFromCenter(dragged, h, keepRatio = ratio != null) else dragged))
                }
            }

            PointerPhase.UP, PointerPhase.CANCEL -> {
                grab = null
                rotating = false
                movingReferencePoint = false
                distortCorner = null
                skewEdge = null
                snapX = null
                snapY = null
            }
        }
        return true
    }

    fun commit(): Boolean {
        warp?.let { grid ->
            // The transform session only held the baseline; the warp is its own engine command.
            session.cancel()
            if (grid.isIdentity) {
                onEnd()
                return true
            }
            val ok = onWarpCommit?.invoke(grid.mesh) == true
            onEnd()
            return ok
        }
        if (box.isIdentity) {
            cancel()
            return true
        }
        val ok = onCommit(session)
        if (ok) {
            onCommitted(box)
            onEnd()
        }
        return ok
    }

    fun cancel() {
        session.cancel()
        onEnd()
    }

    /** Enter applies, Esc cancels. */
    fun onKey(enter: Boolean): Boolean {
        if (enter) commit() else cancel()
        return true
    }

    @Composable
    override fun Overlay(viewport: ViewportState, hover: ViewPoint?) {
        val corners = box.corners
        val accent = Ae.colors.accent
        warp?.let { grid ->
            Canvas(Modifier.fillMaxSize()) {
                val columns = grid.mesh.columns
                val rows = grid.mesh.rows
                fun at(i: Int, j: Int) = viewport.docToView(grid.mesh.nodes[i * columns + j]).let { Offset(it.x, it.y) }
                for (i in 0 until rows) for (j in 0 until columns) {
                    if (j + 1 < columns) drawLine(accent, at(i, j), at(i, j + 1), 1.dp.toPx())
                    if (i + 1 < rows) drawLine(accent, at(i, j), at(i + 1, j), 1.dp.toPx())
                }
                val r = 4.dp.toPx()
                for (i in 0 until rows) for (j in 0 until columns) {
                    val c = at(i, j)
                    drawCircle(Color.White, r, c)
                    drawCircle(accent, r, c, style = Stroke(1.dp.toPx()))
                }
            }
            return
        }
        Canvas(Modifier.fillMaxSize()) {
            val v = corners.map { viewport.docToView(it).let { p -> Offset(p.x, p.y) } }
            val path = Path().apply {
                moveTo(v[0].x, v[0].y)
                v.drop(1).forEach { lineTo(it.x, it.y) }
                close()
            }
            drawPath(path, accent, style = Stroke(1.dp.toPx()))
            val mids = v.indices.map { i -> Offset((v[i].x + v[(i + 1) % 4].x) / 2f, (v[i].y + v[(i + 1) % 4].y) / 2f) }
            val hs = 4.dp.toPx()
            (v + mids).forEach { c ->
                drawRect(Color.White, Offset(c.x - hs, c.y - hs), androidx.compose.ui.geometry.Size(hs * 2, hs * 2))
                drawRect(accent, Offset(c.x - hs, c.y - hs), androidx.compose.ui.geometry.Size(hs * 2, hs * 2), style = Stroke(1.dp.toPx()))
            }
            val center = viewport.docToView(
                if (box.isDistorted) {
                    DocPoint(
                        corners.map {
                            it.x
                        }.average().toFloat(),
                        corners.map { it.y }.average().toFloat(),
                    )
                } else {
                    box.referencePoint ?: box.local.center
                },
            )
            drawCircle(accent, 3.dp.toPx(), Offset(center.x, center.y), style = Stroke(1.dp.toPx()))
            val arm = 7.dp.toPx()
            drawLine(Color.White, Offset(center.x - arm, center.y), Offset(center.x + arm, center.y), 3.dp.toPx())
            drawLine(Color.White, Offset(center.x, center.y - arm), Offset(center.x, center.y + arm), 3.dp.toPx())
            drawLine(accent, Offset(center.x - arm, center.y), Offset(center.x + arm, center.y), 1.dp.toPx())
            drawLine(accent, Offset(center.x, center.y - arm), Offset(center.x, center.y + arm), 1.dp.toPx())
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
        val b = box
        warp?.let { grid ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AeText(label, style = Ae.type.label)
                AeText("Warp", style = Ae.type.caption, color = Ae.colors.textSecondary)
                WarpGrid.DENSITIES.forEach { (c, r) ->
                    AeButton("$c×$r", {
                        warp = grid.withDensity(c, r)
                        onActionCompleted("Set warp grid to $c by $r")
                    }, style = if (grid.mesh.columns == c && grid.mesh.rows == r) AeButtonStyle.Secondary else AeButtonStyle.Ghost)
                }
                AeButton("Reset warp", {
                    warp = grid.reset()
                    onActionCompleted("Reset warp")
                }, style = AeButtonStyle.Ghost)
                AeButton("Free transform", {
                    warp = null
                    onActionCompleted("Leave warp mode")
                }, style = AeButtonStyle.Ghost)
                AeButton("Cancel", ::cancel, style = AeButtonStyle.Ghost)
                AeButton("Apply", { commit() }, style = AeButtonStyle.Primary)
                AeText("Drag a point to bend the layer", style = Ae.type.caption, color = Ae.colors.textTertiary)
            }
            return
        }
        if (b.isDistorted) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AeText(label, style = Ae.type.label)
                AeText("Distort", style = Ae.type.caption, color = Ae.colors.textSecondary)
                AeButton("Reset corners", {
                    set(b.copy(quad = null))
                    onActionCompleted("Reset transform corners")
                }, style = AeButtonStyle.Ghost)
                AeButton("Cancel", ::cancel, style = AeButtonStyle.Ghost)
                AeButton("Apply", { commit() }, style = AeButtonStyle.Primary)
                AeText("Ctrl-drag a corner or edge · Ctrl+Alt+Shift for perspective", style = Ae.type.caption, color = Ae.colors.textTertiary)
            }
            return
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AeText(label, style = Ae.type.label)
            AeText("W", style = Ae.type.caption, color = Ae.colors.textSecondary)
            AeNumberField(b.widthPercent, { w ->
                set(b.copy(local = scaledLocal(b, w / 100f, b.heightPercent / 100f)))
            }, range = 1f..10000f, decimals = 1, unit = "%", width = 76.dp, contentDescription = "Width percent", onCommit = {
                onActionCompleted("Set transform width")
            })
            AeText("H", style = Ae.type.caption, color = Ae.colors.textSecondary)
            AeNumberField(b.heightPercent, { h ->
                set(b.copy(local = scaledLocal(b, b.widthPercent / 100f, h / 100f)))
            }, range = 1f..10000f, decimals = 1, unit = "%", width = 76.dp, contentDescription = "Height percent", onCommit = {
                onActionCompleted("Set transform height")
            })
            AeText("X", style = Ae.type.caption, color = Ae.colors.textSecondary)
            AeNumberField(b.local.center.x, { x ->
                set(b.copy(local = b.local.translate(x - b.local.center.x, 0f)))
            }, range = -100000f..100000f, decimals = 0, unit = "px", width = 80.dp, contentDescription = "Center X", onCommit = {
                onActionCompleted("Set transform X")
            })
            AeText("Y", style = Ae.type.caption, color = Ae.colors.textSecondary)
            AeNumberField(b.local.center.y, { y ->
                set(b.copy(local = b.local.translate(0f, y - b.local.center.y)))
            }, range = -100000f..100000f, decimals = 0, unit = "px", width = 80.dp, contentDescription = "Center Y", onCommit = {
                onActionCompleted("Set transform Y")
            })
            app.croptop.core.designsystem.component.AeBarSlider(
                "Angle",
                b.angle,
                { a -> set(b.copy(angle = normalize(a))) },
                range = -180f..180f,
                defaultValue = 0f,
                decimals = 1,
                unit = "°",
                width = 176.dp,
                contentDescription = "Angle",
                onValueChangeFinished = { onActionCompleted("Set transform angle") },
            )
            AeIconButton(AeIcons.RotateCcw, "Rotate 90° counterclockwise", {
                set(b.rotated(-90f))
                onActionCompleted("Rotate transform counterclockwise")
            })
            AeIconButton(AeIcons.RotateCw, "Rotate 90° clockwise", {
                set(b.rotated(90f))
                onActionCompleted("Rotate transform clockwise")
            })
            AeIconButton(AeIcons.FlipHorizontal, "Flip horizontal", {
                set(b.copy(flipX = !b.flipX))
                onActionCompleted(if (b.flipX) "Unflip transform horizontally" else "Flip transform horizontally")
            })
            AeIconButton(AeIcons.FlipVertical, "Flip vertical", {
                set(b.copy(flipY = !b.flipY))
                onActionCompleted(if (b.flipY) "Unflip transform vertically" else "Flip transform vertically")
            })
            if (canvas != null && onWarpCommit != null) {
                AeButton("Warp", {
                    warp = WarpGrid.identity(canvas)
                    onActionCompleted("Enter warp mode")
                }, style = AeButtonStyle.Ghost)
            }
            AeButton("Cancel", ::cancel, style = AeButtonStyle.Ghost)
            AeButton("Apply", { commit() }, style = AeButtonStyle.Primary)
            AeText(
                "Ctrl+corner distorts · Alt from center",
                style = Ae.type.caption,
                color = Ae.colors.textTertiary,
            )
        }
    }

    /** Scale factors about the current center (options-bar fields). */
    private fun scaledLocal(b: TransformBox, kx: Float, ky: Float): DocRectF {
        val c = b.local.center
        val w = b.original.width * kx / 2f
        val h = b.original.height * ky / 2f
        return DocRectF(c.x - w, c.y - h, c.x + w, c.y + h)
    }

    private fun normalize(a: Float): Float = ((a + 180f).mod(360f)) - 180f

    private companion object {
        const val HANDLE_SLOP_PX = 10f
        const val SNAP_DEGREES = 15f
    }
}

/** Projective maps between quadrilaterals (Distort/Perspective). Pure; null when degenerate. */
internal object Homography {
    /** The matrix taking each [src] corner onto the matching [dst] corner. */
    fun between(src: List<DocPoint>, dst: List<DocPoint>): Matrix3? {
        val a = squareTo(src) ?: return null
        val b = squareTo(dst) ?: return null
        val inv = invert(a) ?: return null
        return b * inv
    }

    /** Unit square (0,0), (1,0), (1,1), (0,1) → quad [q] (Heckbert's closed form). */
    private fun squareTo(q: List<DocPoint>): Matrix3? {
        val (p0, p1, p2, p3) = q
        val dx1 = p1.x - p2.x
        val dx2 = p3.x - p2.x
        val dy1 = p1.y - p2.y
        val dy2 = p3.y - p2.y
        val sx = p0.x - p1.x + p2.x - p3.x
        val sy = p0.y - p1.y + p2.y - p3.y
        val det = dx1 * dy2 - dx2 * dy1
        if (kotlin.math.abs(det) < 1e-9f) return null
        val g = (sx * dy2 - dx2 * sy) / det
        val h = (dx1 * sy - sx * dy1) / det
        return Matrix3(
            listOf(
                p1.x - p0.x + g * p1.x, p3.x - p0.x + h * p3.x, p0.x,
                p1.y - p0.y + g * p1.y, p3.y - p0.y + h * p3.y, p0.y,
                g, h, 1f,
            ),
        )
    }

    private fun invert(m: Matrix3): Matrix3? {
        val a = m.values
        val c00 = a[4] * a[8] - a[5] * a[7]
        val c01 = a[5] * a[6] - a[3] * a[8]
        val c02 = a[3] * a[7] - a[4] * a[6]
        val det = a[0] * c00 + a[1] * c01 + a[2] * c02
        if (kotlin.math.abs(det) < 1e-12f) return null
        val d = 1f / det
        return Matrix3(
            listOf(
                c00 * d, (a[2] * a[7] - a[1] * a[8]) * d, (a[1] * a[5] - a[2] * a[4]) * d,
                c01 * d, (a[0] * a[8] - a[2] * a[6]) * d, (a[2] * a[3] - a[0] * a[5]) * d,
                c02 * d, (a[1] * a[6] - a[0] * a[7]) * d, (a[0] * a[4] - a[1] * a[3]) * d,
            ),
        )
    }
}

/** Point-in-quad (convex or not) by ray casting; used to move a distorted box. */
private fun insideQuad(q: List<DocPoint>, p: DocPoint): Boolean {
    var inside = false
    var j = q.lastIndex
    for (i in q.indices) {
        val a = q[i]
        val b = q[j]
        if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) inside = !inside
        j = i
    }
    return inside
}

/**
 * Android pointer-icon type for hovering [handle] of a box rotated by [angleDegrees]: a double arrow along the handle's
 * direction (rounded to the nearest of the four arrow orientations), the move cursor inside, and the grab hand outside (rotate).
 */
internal fun transformCursorType(handle: CropHandle?, angleDegrees: Float): Int {
    if (handle == null) return android.view.PointerIcon.TYPE_GRAB
    if (handle == CropHandle.MOVE) return android.view.PointerIcon.TYPE_ALL_SCROLL
    val x = (if (handle.right) 1f else 0f) - (if (handle.left) 1f else 0f)
    val y = (if (handle.bottom) 1f else 0f) - (if (handle.top) 1f else 0f)
    // Screen angle of the handle direction after the box rotation, folded to 0..180 (a double arrow is symmetric).
    var degrees = Math.toDegrees(atan2(y.toDouble(), x.toDouble())) + angleDegrees
    degrees = ((degrees % 180.0) + 180.0) % 180.0
    return when (((degrees + 22.5) / 45.0).toInt() % 4) {
        0 -> android.view.PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW
        1 -> android.view.PointerIcon.TYPE_TOP_LEFT_DIAGONAL_DOUBLE_ARROW
        2 -> android.view.PointerIcon.TYPE_VERTICAL_DOUBLE_ARROW
        else -> android.view.PointerIcon.TYPE_TOP_RIGHT_DIAGONAL_DOUBLE_ARROW
    }
}
