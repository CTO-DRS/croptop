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
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.component.AeButton
import app.croptop.core.designsystem.component.AeButtonStyle
import app.croptop.core.designsystem.component.AeCheckbox
import app.croptop.core.designsystem.component.AeDropdown
import app.croptop.core.designsystem.component.AeNumberField
import app.croptop.core.designsystem.component.AeText
import app.croptop.core.designsystem.theme.Ae
import app.croptop.core.input.Modifiers
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocRectF
import app.croptop.core.model.DocSize
import app.croptop.core.model.CropQuad
import app.croptop.core.model.PerspectiveCropGeometry
import app.croptop.core.model.ToolId
import app.croptop.core.model.ViewPoint
import app.croptop.core.model.ViewportState
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Crop aspect presets. [ratio] = width / height; null = free, 0 = the image's own. */
enum class CropAspect(val label: String, val ratio: Float?) {
    FREE("Free", null),
    ORIGINAL("Original ratio", 0f),
    CUSTOM("Custom", null),
    SQUARE("1 : 1 (Square)", 1f),
    PORTRAIT_4_5("4 : 5 (Portrait)", 4f / 5f),
    LANDSCAPE_4_3("4 : 3", 4f / 3f),
    WIDE_16_9("16 : 9", 16f / 9f),
}

enum class CropOverlay(val label: String) {
    THIRDS("Thirds"),
    GOLDEN_RATIO("Golden ratio"),
    DIAGONAL("Diagonal"),
}

/** Which part of the crop box a press grabbed. */
internal enum class CropHandle(val left: Boolean, val top: Boolean, val right: Boolean, val bottom: Boolean) {
    TOP_LEFT(true, true, false, false),
    TOP(false, true, false, false),
    TOP_RIGHT(false, true, true, false),
    RIGHT(false, false, true, false),
    BOTTOM_RIGHT(false, false, true, true),
    BOTTOM(false, false, false, true),
    BOTTOM_LEFT(true, false, false, true),
    LEFT(true, false, false, false),
    MOVE(false, false, false, false),
}

/** Pure crop-box geometry (unit-tested). All values in document pixels. */
internal object CropMath {
    const val MIN_SIZE = 1f

    /** Returns the image rotation that levels a user-drawn line, normalized to the shortest rotation. */
    fun straightenAngle(start: DocPoint, end: DocPoint): Float {
        val dx = end.x - start.x
        val dy = end.y - start.y
        if (kotlin.math.hypot(dx.toDouble(), dy.toDouble()) < 1e-3) return 0f
        var angle = -Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        if (angle > 90f) angle -= 180f
        if (angle < -90f) angle += 180f
        return angle
    }

    /** Resizes [box] by dragging [handle] to [p]; keeps [ratio] (w/h) when given. */
    fun drag(box: DocRectF, handle: CropHandle, p: DocPoint, ratio: Float?): DocRectF {
        var l = if (handle.left) min(p.x, box.right - MIN_SIZE) else box.left
        var t = if (handle.top) min(p.y, box.bottom - MIN_SIZE) else box.top
        var r = if (handle.right) max(p.x, box.left + MIN_SIZE) else box.right
        var b = if (handle.bottom) max(p.y, box.top + MIN_SIZE) else box.bottom
        if (ratio != null && ratio > 0f) {
            val horizontalEdge = (handle.top || handle.bottom) && !(handle.left || handle.right)
            if (horizontalEdge) {
                // Height drives; width follows, centered.
                val w = (b - t) * ratio
                val cx = (box.left + box.right) / 2f
                l = cx - w / 2f
                r = cx + w / 2f
            } else {
                // Width drives; height follows from the anchored edge.
                val h = (r - l) / ratio
                if (handle.top) t = b - h else b = t + h
                if (!handle.top && !handle.bottom) {
                    val cy = (box.top + box.bottom) / 2f
                    t = cy - h / 2f
                    b = cy + h / 2f
                }
            }
        }
        return DocRectF(min(l, r), min(t, b), max(l, r), max(t, b))
    }

    /** Largest box of [ratio] centered in the canvas (the starting box for a preset). */
    fun fit(canvas: DocSize, ratio: Float?): DocRectF {
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()
        if (ratio == null || ratio <= 0f) return DocRectF(0f, 0f, w, h)
        return if (w / h > ratio) {
            val nw = h * ratio
            DocRectF((w - nw) / 2f, 0f, (w + nw) / 2f, h)
        } else {
            val nh = w / ratio
            DocRectF(0f, (h - nh) / 2f, w, (h + nh) / 2f)
        }
    }

    fun handleAt(box: DocRectF, p: DocPoint, slopDoc: Float): CropHandle? {
        val nearL = abs(p.x - box.left) <= slopDoc
        val nearR = abs(p.x - box.right) <= slopDoc
        val nearT = abs(p.y - box.top) <= slopDoc
        val nearB = abs(p.y - box.bottom) <= slopDoc
        val inX = p.x in (box.left - slopDoc)..(box.right + slopDoc)
        val inY = p.y in (box.top - slopDoc)..(box.bottom + slopDoc)
        return when {
            nearL && nearT -> CropHandle.TOP_LEFT
            nearR && nearT -> CropHandle.TOP_RIGHT
            nearR && nearB -> CropHandle.BOTTOM_RIGHT
            nearL && nearB -> CropHandle.BOTTOM_LEFT
            nearT && inX -> CropHandle.TOP
            nearB && inX -> CropHandle.BOTTOM
            nearL && inY -> CropHandle.LEFT
            nearR && inY -> CropHandle.RIGHT
            box.contains(p) -> CropHandle.MOVE
            else -> null
        }
    }
}

/**
 * Crop (C): the box starts at the whole canvas; drag handles or the inside, Enter or Commit crops (one
 * history step), Esc resets. Shift keeps the current proportions while dragging a corner.
 */
class CropTool(
    private val document: () -> DocSize?,
    private val commit: (DocRectF, Boolean) -> Boolean,
    private val commitPerspective: ((CropQuad, Boolean) -> Boolean)? = null,
    private val commitAngled: ((DocRectF, Float, Boolean) -> Boolean)? = null,
    /** [ToolId.CROP], or [ToolId.PERSPECTIVE_CROP] for the dedicated tool that starts (and stays) in perspective mode. */
    override val tool: ToolId = ToolId.CROP,
) : ToolController {
    var onActionCompleted: (String) -> Unit = {}
    var aspect by mutableStateOf(CropAspect.FREE)
    var customWidth by mutableStateOf(16f)
        private set
    var customHeight by mutableStateOf(9f)
        private set
    var overlay by mutableStateOf(CropOverlay.THIRDS)
    var deleteCropped by mutableStateOf(true)
    var perspective by mutableStateOf(tool == ToolId.PERSPECTIVE_CROP)
        private set
    var straightenEnabled by mutableStateOf(false)
        private set
    var straightenAngle by mutableStateOf(0f)
        private set
    private var straightenStart by mutableStateOf<DocPoint?>(null)
    private var straightenEnd by mutableStateOf<DocPoint?>(null)
    private var box by mutableStateOf<DocRectF?>(null)
    private var quad by mutableStateOf<CropQuad?>(null)
    private var boxFor: DocSize? = null
    private var swappedOrientation by mutableStateOf(false)
    private var grabbed: CropHandle? = null
    private var grabAt = DocPoint.Zero
    private var grabBox: DocRectF? = null
    private var grabbedCorner: Int? = null

    private fun current(): DocRectF? {
        val size = document() ?: return null
        // A new canvas size (after a crop, undo, or Image Size) restarts the box.
        if (box == null || boxFor != size) {
            box = CropMath.fit(size, ratioFor(size))
            quad = fullQuad(size)
            boxFor = size
        }
        return box
    }

    internal fun ratioFor(size: DocSize): Float? {
        val ratio = when {
            aspect == CropAspect.CUSTOM -> customWidth / customHeight
            aspect == CropAspect.FREE -> null
            aspect == CropAspect.ORIGINAL -> size.width.toFloat() / size.height
            else -> aspect.ratio
        }
        return if (swappedOrientation && ratio != null && ratio > 0f) 1f / ratio else ratio
    }

    fun chooseAspect(a: CropAspect) {
        aspect = a
        swappedOrientation = false
        document()?.let { box = CropMath.fit(it, ratioFor(it)) }
    }

    fun updateCustomWidth(value: Float) {
        customWidth = value.coerceIn(1f, 10000f)
        document()?.let { box = CropMath.fit(it, ratioFor(it)) }
    }

    fun updateCustomHeight(value: Float) {
        customHeight = value.coerceIn(1f, 10000f)
        document()?.let { box = CropMath.fit(it, ratioFor(it)) }
    }

    /** X swaps portrait/landscape while keeping the selected ratio. */
    fun swapOrientation() {
        swappedOrientation = !swappedOrientation
        document()?.let { box = CropMath.fit(it, ratioFor(it)) }
    }

    fun cycleOverlay() {
        overlay = CropOverlay.entries[(overlay.ordinal + 1) % CropOverlay.entries.size]
    }

    fun setPerspectiveEnabled(enabled: Boolean) {
        if (!enabled && tool == ToolId.PERSPECTIVE_CROP) return  // the dedicated tool is always perspective
        if (perspective == enabled) return
        perspective = enabled
        if (enabled) setStraightenMode(false)
        document()?.let { size ->
            if (boxFor != size) boxFor = size
            if (enabled) quad = box?.let(::quadFor) ?: fullQuad(size)
            else quad = fullQuad(size)
        }
    }

    fun setStraightenMode(enabled: Boolean) {
        straightenEnabled = enabled
        straightenStart = null
        straightenEnd = null
        straightenAngle = 0f
    }

    override fun cursor(modifiers: Modifiers): PointerIcon = PointerIcon.Crosshair

    override fun onPointer(event: ToolPointerEvent, ctx: ToolContext): Boolean {
        val b = current() ?: return true
        if (perspective) return onPerspectivePointer(event, ctx, b)
        if (straightenEnabled) return onStraightenPointer(event)
        when (event.phase) {
            PointerPhase.DOWN -> {
                grabbed = CropMath.handleAt(b, event.doc, handleHitRadiusPx(ctx.density, event.pointerKind) / ctx.viewport.zoom)
                grabAt = event.doc
                grabBox = b
            }

            PointerPhase.MOVE -> {
                val h = grabbed ?: return true
                val start = grabBox ?: return true
                box = if (h == CropHandle.MOVE) {
                    start.translate(event.doc.x - grabAt.x, event.doc.y - grabAt.y)
                } else {
                    // A preset ratio always applies; in Free mode Shift keeps the current proportions.
                    val preset = document()?.let { ratioFor(it) }
                    val ratio = preset ?: (start.width / start.height).takeIf { event.modifiers.shift }
                    CropMath.drag(start, h, event.doc, ratio)
                }
            }

            PointerPhase.UP, PointerPhase.CANCEL -> {
                grabbed = null
                grabBox = null
            }
        }
        return true
    }

    private fun onStraightenPointer(event: ToolPointerEvent): Boolean {
        when (event.phase) {
            PointerPhase.DOWN -> {
                straightenStart = event.doc
                straightenEnd = event.doc
                straightenAngle = 0f
            }
            PointerPhase.MOVE -> {
                if (straightenStart != null) {
                    straightenEnd = event.doc
                    straightenAngle = CropMath.straightenAngle(checkNotNull(straightenStart), event.doc)
                }
            }
            PointerPhase.UP -> {
                straightenEnd = event.doc
                straightenStart?.let { straightenAngle = CropMath.straightenAngle(it, event.doc) }
            }
            PointerPhase.CANCEL -> {
                straightenStart = null
                straightenEnd = null
                straightenAngle = 0f
            }
        }
        return true
    }

    private fun onPerspectivePointer(event: ToolPointerEvent, ctx: ToolContext, bounds: DocRectF): Boolean {
        val points = quad?.points ?: return true
        when (event.phase) {
            PointerPhase.DOWN -> {
                val slop = handleHitRadiusPx(ctx.density, event.pointerKind) / ctx.viewport.zoom
                grabbedCorner = points.indices.minByOrNull { i ->
                    val dx = points[i].x - event.doc.x
                    val dy = points[i].y - event.doc.y
                    dx * dx + dy * dy
                }?.takeIf { i ->
                    val p = points[i]
                    kotlin.math.hypot((p.x - event.doc.x).toDouble(), (p.y - event.doc.y).toDouble()) <= slop
                }
                grabAt = event.doc
                grabBox = bounds
            }
            PointerPhase.MOVE -> {
                val index = grabbedCorner ?: return true
                val p = DocPoint(
                    event.doc.x.coerceIn(0f, document()?.width?.toFloat() ?: event.doc.x),
                    event.doc.y.coerceIn(0f, document()?.height?.toFloat() ?: event.doc.y),
                )
                val updated = points.toMutableList().also { it[index] = p }
                quad = CropQuad(updated[0], updated[1], updated[2], updated[3])
            }
            PointerPhase.UP, PointerPhase.CANCEL -> {
                grabbedCorner = null
                grabBox = null
            }
        }
        return true
    }

    /** Enter commits, Esc resets the box. */
    fun onKey(enter: Boolean): Boolean {
        if (enter) return apply()
        reset()
        return true
    }

    fun apply(): Boolean {
        if (perspective) {
            current()  // the frame is created on first use (the dedicated perspective tool starts in this mode)
            val q = quad ?: return false
            val ok = commitPerspective?.invoke(q, deleteCropped) ?: false
            if (ok) {
                box = null
                quad = null
            }
            return ok
        }
        val b = current() ?: return false
        val ok = if (abs(straightenAngle) > 1e-3f) {
            commitAngled?.invoke(b, straightenAngle, deleteCropped) ?: commit(b, deleteCropped)
        } else {
            commit(b, deleteCropped)
        }
        if (ok) {
            box = null
            straightenAngle = 0f
            straightenStart = null
            straightenEnd = null
        }
        return ok
    }

    fun reset() {
        box = null
        document()?.let { quad = fullQuad(it) }
        straightenStart = null
        straightenEnd = null
        straightenAngle = 0f
    }

    @Composable
    override fun Overlay(viewport: ViewportState, hover: ViewPoint?) {
        val b = current() ?: return
        val size = document() ?: return
        if (perspective) {
            val q = quad ?: return
            val accent = Ae.colors.accent
            Canvas(Modifier.fillMaxSize()) {
                fun v(point: DocPoint) = viewport.docToView(point).let { Offset(it.x, it.y) }
                val corners = q.points.map(::v)
                val cropPath = Path().apply {
                    moveTo(corners[0].x, corners[0].y)
                    corners.drop(1).forEach { lineTo(it.x, it.y) }
                    close()
                }
                val shade = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width.toFloat(), size.height.toFloat()))
                    addPath(cropPath)
                }
                drawPath(shade, Color.Black.copy(alpha = 0.55f))
                drawPath(cropPath, Color.White, style = Stroke(1.5.dp.toPx()))
                val hs = 6.dp.toPx()
                corners.forEach { c ->
                    drawCircle(Color.White, hs, c)
                    drawCircle(accent, hs, c, style = Stroke(1.dp.toPx()))
                }
            }
            return
        }
        val accent = Ae.colors.accent
        Canvas(Modifier.fillMaxSize()) {
            fun v(x: Float, y: Float) = viewport.docToView(DocPoint(x, y)).let { Offset(it.x, it.y) }
            fun quad(l: Float, t: Float, r: Float, bt: Float) = Path().apply {
                moveTo(v(l, t).x, v(l, t).y)
                lineTo(v(r, t).x, v(r, t).y)
                lineTo(v(r, bt).x, v(r, bt).y)
                lineTo(v(l, bt).x, v(l, bt).y)
                close()
            }
            // Dim everything outside the box (the canvas and beyond).
            val outer = quad(-size.width * 4f, -size.height * 4f, size.width * 5f, size.height * 5f)
            val shade = Path().apply {
                fillType = PathFillType.EvenOdd
                addPath(outer)
                addPath(quad(b.left, b.top, b.right, b.bottom))
            }
            drawPath(shade, Color.Black.copy(alpha = 0.55f))
            // Crop composition guide selected with O.
            when (overlay) {
                CropOverlay.THIRDS, CropOverlay.GOLDEN_RATIO -> {
                    val fraction = if (overlay == CropOverlay.THIRDS) 1f / 3f else 0.382f
                    for (i in 1..2) {
                        val position = if (i == 1) fraction else 1f - fraction
                        val x = b.left + b.width * position
                        val y = b.top + b.height * position
                        drawLine(Color.White.copy(alpha = 0.5f), v(x, b.top), v(x, b.bottom), 1f)
                        drawLine(Color.White.copy(alpha = 0.5f), v(b.left, y), v(b.right, y), 1f)
                    }
                }

                CropOverlay.DIAGONAL -> {
                    drawLine(Color.White.copy(alpha = 0.5f), v(b.left, b.top), v(b.right, b.bottom), 1f)
                    drawLine(Color.White.copy(alpha = 0.5f), v(b.right, b.top), v(b.left, b.bottom), 1f)
                }
            }
            drawPath(quad(b.left, b.top, b.right, b.bottom), Color.White, style = Stroke(1.5.dp.toPx()))
            val lineStart = straightenStart
            val lineEnd = straightenEnd
            if (!perspective && lineStart != null && lineEnd != null) {
                drawLine(Color.Yellow, v(lineStart.x, lineStart.y), v(lineEnd.x, lineEnd.y), 2.dp.toPx())
            }
            // Handles: corners and edge midpoints.
            val hs = 5.dp.toPx()
            listOf(
                b.left to b.top,
                b.center.x to b.top,
                b.right to b.top,
                b.right to b.center.y,
                b.right to b.bottom,
                b.center.x to b.bottom,
                b.left to b.bottom,
                b.left to b.center.y,
            ).forEach { (x, y) ->
                val c = v(x, y)
                drawRect(Color.White, Offset(c.x - hs, c.y - hs), androidx.compose.ui.geometry.Size(hs * 2, hs * 2))
                drawRect(accent, Offset(c.x - hs, c.y - hs), androidx.compose.ui.geometry.Size(hs * 2, hs * 2), style = Stroke(1.dp.toPx()))
            }
        }
    }

    @Composable
    override fun Options() {
        val b = box
        val perspectiveSize = quad?.let(PerspectiveCropGeometry::outputSize)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (tool != ToolId.PERSPECTIVE_CROP) {
                AeCheckbox(
                    perspective,
                    {
                        setPerspectiveEnabled(it)
                        onActionCompleted(if (it) "Enable perspective crop" else "Disable perspective crop")
                    },
                    "Perspective Crop",
                )
            }
            if (!perspective) {
                AeCheckbox(
                    straightenEnabled,
                    { setStraightenMode(it); onActionCompleted(if (it) "Start crop straighten" else "Cancel crop straighten") },
                    "Straighten Line",
                )
            }
            if (!perspective) {
                AeDropdown(
                    CropAspect.entries,
                    aspect,
                    {
                        chooseAspect(it)
                        onActionCompleted("Set crop aspect to ${it.label.lowercase()}")
                    },
                    { it.label },
                )
                if (aspect == CropAspect.CUSTOM) {
                    AeNumberField(
                        customWidth,
                        ::updateCustomWidth,
                        range = 1f..10000f,
                        width = 54.dp,
                        contentDescription = "Custom crop ratio width",
                        onCommit = { onActionCompleted("Set custom crop width") },
                    )
                    AeText(":", style = Ae.type.body, color = Ae.colors.textSecondary)
                    AeNumberField(
                        customHeight,
                        ::updateCustomHeight,
                        range = 1f..10000f,
                        width = 54.dp,
                        contentDescription = "Custom crop ratio height",
                        onCommit = { onActionCompleted("Set custom crop height") },
                    )
                }
            }
            val outputSize = if (perspective) {
                perspectiveSize
            } else {
                b?.let { DocSize(it.width.toInt().coerceAtLeast(1), it.height.toInt().coerceAtLeast(1)) }
            }
            outputSize?.let { AeText("${it.width} × ${it.height} px", style = Ae.type.caption, color = Ae.colors.textSecondary) }
            if (abs(straightenAngle) > 1e-3f) {
                AeText("${straightenAngle.roundToInt()}°", style = Ae.type.caption, color = Ae.colors.textSecondary)
            }
            AeCheckbox(deleteCropped, {
                deleteCropped = it
                onActionCompleted(if (it) "Enable delete cropped pixels" else "Preserve cropped pixels")
            }, "Delete Cropped Pixels")
            AeButton("Cancel", ::reset, style = AeButtonStyle.Ghost)
            AeButton("Crop", { apply() }, style = AeButtonStyle.Primary, enabled = outputSize != null)
            if (!perspective) {
                val hint = if (straightenEnabled) "Drag a line to level · Enter crops · Esc resets" else "${overlay.label} · O cycles · Enter crops · Esc resets"
                AeText(hint, style = Ae.type.caption, color = Ae.colors.textTertiary)
            }
        }
    }

    private fun fullQuad(size: DocSize) = CropQuad(
        DocPoint(0f, 0f), DocPoint(size.width.toFloat(), 0f),
        DocPoint(size.width.toFloat(), size.height.toFloat()), DocPoint(0f, size.height.toFloat()),
    )

    private fun quadFor(rect: DocRectF) = CropQuad(
        DocPoint(rect.left, rect.top), DocPoint(rect.right, rect.top),
        DocPoint(rect.right, rect.bottom), DocPoint(rect.left, rect.bottom),
    )

    private companion object {
        const val HANDLE_SLOP_PX = 10f
    }
}
