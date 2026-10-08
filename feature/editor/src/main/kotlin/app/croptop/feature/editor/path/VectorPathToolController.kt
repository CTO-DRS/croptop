package app.croptop.feature.editor.path

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerIcon
import app.croptop.core.designsystem.component.AeText
import app.croptop.core.designsystem.theme.Ae
import app.croptop.core.input.Modifiers
import app.croptop.core.model.DocPoint
import app.croptop.core.model.PathAnchorId
import app.croptop.core.model.PathAnchorMode
import app.croptop.core.model.ToolId
import app.croptop.core.model.VectorPathDraft
import app.croptop.core.model.VectorPathId
import app.croptop.core.model.VectorShapeKind
import app.croptop.core.model.ViewPoint
import app.croptop.core.model.ViewportState
import app.croptop.feature.editor.tools.PointerPhase
import app.croptop.feature.editor.tools.ToolContext
import app.croptop.feature.editor.tools.ToolController
import app.croptop.feature.editor.tools.ToolPointerEvent
import kotlin.math.hypot

/** Shape/Pen input controller. Drafts commit only after a complete pointer gesture. */
class VectorPathToolController(
    override val tool: ToolId,
    private val drafts: PathDraftController,
    private val onCommit: (List<VectorPathDraft>) -> Boolean,
) : ToolController {
    var onActionCompleted: (String) -> Unit = {}
    private var gestureStart: DocPoint? = null
    private var gestureEnd: DocPoint? = null
    private var originalPaths: List<VectorPathDraft>? = null
    private var draggedAnchor: PathAnchorSelection? = null
    private var draggedPath: VectorPathId? = null
    private var draggedRoundedCorner: RoundedCorner? = null
    private var convertingAnchor: PathAnchorSelection? = null
    private var didMoveAnchor = false
    private var ctrlSelectingFromPen = false

    override fun cursor(modifiers: Modifiers) = when {
        tool == ToolId.PATH_SELECT || tool == ToolId.DIRECT_SELECT || (tool == ToolId.PEN && modifiers.ctrl) -> PointerIcon.Default
        else -> PointerIcon.Crosshair
    }

    override fun onPointer(event: ToolPointerEvent, ctx: ToolContext): Boolean {
        return when (tool) {
            ToolId.PEN -> {
                if (event.phase == PointerPhase.DOWN) ctrlSelectingFromPen = event.modifiers.ctrl
                if (ctrlSelectingFromPen) onPathSelect(event, ctx) else onPen(event, ctx)
            }

            ToolId.RECTANGLE, ToolId.ELLIPSE, ToolId.POLYGON, ToolId.LINE -> onShape(event)

            ToolId.PATH_SELECT -> onPathObjectSelect(event, ctx)

            ToolId.DIRECT_SELECT -> onPathSelect(event, ctx)

            else -> false
        }
    }

    private fun onPen(event: ToolPointerEvent, ctx: ToolContext): Boolean = when (event.phase) {
        PointerPhase.DOWN -> {
            val before = drafts.state.value.paths
            if (event.modifiers.alt) {
                val tolerance = 8f / ctx.viewport.zoom.coerceAtLeast(0.01f)
                val anchor = nearestAnchor(event.doc, tolerance)
                if (anchor != null) {
                    convertingAnchor = anchor
                    originalPaths = before
                    drafts.selectPath(anchor.pathId)
                    drafts.selectAnchor(anchor)
                    drafts.setAnchorMode(anchor, PathAnchorMode.CORNER)
                    gestureStart = anchorPoint(anchor) ?: event.doc
                    gestureEnd = gestureStart
                    return true
                }
            }
            gestureStart = event.doc
            gestureEnd = event.doc
            originalPaths = before
            true
        }

        PointerPhase.MOVE -> {
            val conversion = convertingAnchor
            val start = gestureStart
            if (conversion != null && start != null) {
                gestureEnd = event.doc
                val threshold = 2f / ctx.viewport.zoom.coerceAtLeast(0.01f)
                val moved = hypot((event.doc.x - start.x).toDouble(), (event.doc.y - start.y).toDouble()) > threshold
                if (moved) {
                    drafts.moveHandle(conversion, incoming = false, endpoint = event.doc)
                }
            } else if (start != null) {
                gestureEnd = event.doc
            }
            true
        }

        PointerPhase.UP -> {
            val start = gestureStart ?: return true
            val before = originalPaths.orEmpty()
            val conversion = convertingAnchor
            if (conversion != null) {
                val threshold = 2f / ctx.viewport.zoom.coerceAtLeast(0.01f)
                val moved = hypot((event.doc.x - start.x).toDouble(), (event.doc.y - start.y).toDouble()) > threshold
                if (moved) {
                    drafts.moveHandle(conversion, incoming = false, endpoint = event.doc)
                }
                if (before != drafts.state.value.paths) finishCommit(before)
                clearGesture()
                return true
            }
            val handle = event.doc - start
            val active = drafts.state.value.paths.firstOrNull { it.id == drafts.state.value.selectedPathId }
            if (active != null && active.subpaths.lastOrNull()?.closed == false) {
                drafts.appendPenAnchor(start, handle)
            } else {
                drafts.beginPenPath(start, handle)
            }
            finishCommit(before)
            clearGesture()
            true
        }

        PointerPhase.CANCEL -> {
            restoreGesture()
            clearGesture()
            true
        }
    }

    private fun onShape(event: ToolPointerEvent): Boolean = when (event.phase) {
        PointerPhase.DOWN -> {
            gestureStart = event.doc
            gestureEnd = event.doc
            originalPaths = drafts.state.value.paths
            val currentKind = drafts.state.value.shapeOptions.kind
            val kind = when (tool) {
                ToolId.POLYGON -> if (currentKind == VectorShapeKind.STAR) VectorShapeKind.STAR else VectorShapeKind.POLYGON
                ToolId.RECTANGLE -> if (currentKind == VectorShapeKind.ROUNDED_RECTANGLE) currentKind else VectorShapeKind.RECTANGLE
                else -> tool.shapeKind()
            }
            drafts.setShapeOptions(drafts.state.value.shapeOptions.copy(kind = kind))
            true
        }

        PointerPhase.MOVE -> {
            if (gestureStart != null) gestureEnd = event.doc
            true
        }

        PointerPhase.UP -> {
            val start = gestureStart ?: return true
            val before = originalPaths.orEmpty()
            if (drafts.beginShapePath(start, event.doc) != null) finishCommit(before)
            clearGesture()
            true
        }

        PointerPhase.CANCEL -> {
            restoreGesture()
            clearGesture()
            true
        }
    }

    private fun onPathSelect(event: ToolPointerEvent, ctx: ToolContext): Boolean = when (event.phase) {
        PointerPhase.DOWN -> {
            originalPaths = drafts.state.value.paths
            didMoveAnchor = false
            val tolerance = 8f / ctx.viewport.zoom.coerceAtLeast(0.01f)
            val corner = nearestRoundedCorner(event.doc, tolerance)
            val anchor = if (corner == null) nearestAnchor(event.doc, tolerance) else null
            if (corner != null) {
                drafts.selectPath(corner.first)
                drafts.selectAnchor(null)
                draggedRoundedCorner = corner.second
            } else if (anchor != null) {
                drafts.selectPath(anchor.pathId)
                drafts.selectAnchor(anchor)
                draggedAnchor = anchor
            } else {
                draggedRoundedCorner = null
                draggedAnchor = null
                drafts.selectPath(pathAt(event.doc, tolerance))
            }
            true
        }

        PointerPhase.MOVE -> {
            val corner = draggedRoundedCorner
            if (corner != null) {
                didMoveAnchor = drafts.setSelectedRoundedCornerFromPoint(corner, event.doc) || didMoveAnchor
            } else {
                val selection = draggedAnchor
                if (selection != null) {
                    didMoveAnchor = drafts.moveAnchor(selection, event.doc) || didMoveAnchor
                }
            }
            true
        }

        PointerPhase.UP -> {
            val before = originalPaths
            draggedRoundedCorner?.let { corner ->
                didMoveAnchor = drafts.setSelectedRoundedCornerFromPoint(corner, event.doc) || didMoveAnchor
            } ?: draggedAnchor?.let { selection -> didMoveAnchor = drafts.moveAnchor(selection, event.doc) || didMoveAnchor }
            if (didMoveAnchor && before != null && before != drafts.state.value.paths) finishCommit(before)
            clearGesture()
            true
        }

        PointerPhase.CANCEL -> {
            restoreGesture()
            clearGesture()
            true
        }
    }

    private fun onPathObjectSelect(event: ToolPointerEvent, ctx: ToolContext): Boolean = when (event.phase) {
        PointerPhase.DOWN -> {
            originalPaths = drafts.state.value.paths
            didMoveAnchor = false
            draggedPath = pathAt(event.doc, tolerance = 8f / ctx.viewport.zoom.coerceAtLeast(0.01f))
            drafts.selectPath(draggedPath)
            gestureStart = event.doc
            gestureEnd = event.doc
            true
        }

        PointerPhase.MOVE -> {
            val id = draggedPath
            val previous = gestureEnd
            if (id != null && previous != null) {
                val offset = event.doc - previous
                didMoveAnchor = drafts.movePath(id, offset) || didMoveAnchor
                gestureEnd = event.doc
            }
            true
        }

        PointerPhase.UP -> {
            val id = draggedPath
            val previous = gestureEnd
            if (id != null && previous != null) {
                val offset = event.doc - previous
                didMoveAnchor = drafts.movePath(id, offset) || didMoveAnchor
            }
            val before = originalPaths
            if (didMoveAnchor && before != null && before != drafts.state.value.paths) finishCommit(before)
            clearGesture()
            true
        }

        PointerPhase.CANCEL -> {
            restoreGesture()
            clearGesture()
            true
        }
    }

    private fun nearestAnchor(point: DocPoint, tolerance: Float): PathAnchorSelection? =
        drafts.state.value.paths.asSequence().flatMap { path ->
            path.subpaths.flatMapIndexed { subpathIndex, subpath ->
                subpath.anchors.map { anchor ->
                    PathAnchorSelection(path.id, subpathIndex, anchor.id) to hypot(
                        (anchor.point.x - point.x).toDouble(),
                        (anchor.point.y - point.y).toDouble(),
                    )
                }
            }.asSequence()
        }.filter { (_, distance) -> distance <= tolerance }
            .minByOrNull { it.second }
            ?.first

    private fun nearestRoundedCorner(point: DocPoint, tolerance: Float): Pair<app.croptop.core.model.VectorPathId, RoundedCorner>? {
        val selectedPath = drafts.state.value.paths.firstOrNull { it.id == drafts.state.value.selectedPathId } ?: return null
        return RoundedCorner.entries.mapNotNull { corner ->
            val handle = roundedRectangleRadiusHandle(selectedPath, corner) ?: return@mapNotNull null
            val distance = hypot((handle.x - point.x).toDouble(), (handle.y - point.y).toDouble())
            if (distance <= tolerance) corner to distance else null
        }.minByOrNull { it.second }?.first?.let { selectedPath.id to it }
    }

    private fun pathAt(point: DocPoint, tolerance: Float = 0f): VectorPathId? = drafts.state.value.paths.lastOrNull { path ->
        val points = path.subpaths.flatMap { it.anchors }.map { it.point }
        points.isNotEmpty() && point.x in (points.minOf { it.x } - tolerance)..(points.maxOf { it.x } + tolerance) &&
            point.y in (points.minOf { it.y } - tolerance)..(points.maxOf { it.y } + tolerance)
    }?.id

    private fun finishCommit(previous: List<VectorPathDraft>) {
        if (!onCommit(drafts.state.value.paths)) drafts.loadPersistedPaths(previous)
    }

    private fun restoreGesture() {
        originalPaths?.let(drafts::loadPersistedPaths)
    }

    private fun clearGesture() {
        gestureStart = null
        gestureEnd = null
        originalPaths = null
        draggedAnchor = null
        draggedPath = null
        draggedRoundedCorner = null
        convertingAnchor = null
        didMoveAnchor = false
        ctrlSelectingFromPen = false
    }

    private fun anchorPoint(selection: PathAnchorSelection): DocPoint? = drafts.state.value.paths
        .firstOrNull { it.id == selection.pathId }
        ?.subpaths
        ?.getOrNull(selection.subpathIndex)
        ?.anchors
        ?.firstOrNull { it.id == selection.anchorId }
        ?.point

    @Composable
    override fun Options() {
        val state by drafts.state.collectAsState()
        Column {
            ShapeOptionsBar(state.shapeOptions, drafts::setShapeOptions, onActionCompleted = onActionCompleted)
            state.message?.let { AeText(it, color = Ae.colors.warning) }
        }
    }

    @Composable
    override fun Overlay(viewport: ViewportState, hover: ViewPoint?) {
        val state by drafts.state.collectAsState()
        val start = gestureStart
        val end = gestureEnd
        val isShapeGesture = tool in setOf(ToolId.RECTANGLE, ToolId.ELLIPSE, ToolId.POLYGON, ToolId.LINE)
        val selectedPath = state.paths.firstOrNull { it.id == state.selectedPathId }
        if (start == null && selectedPath == null) return
        val lineColor = Color(Ae.colors.accent.toArgb())
        Canvas(Modifier.fillMaxSize()) {
            if (start != null && end != null && isShapeGesture) {
                var previewAnchorId = -1L
                val anchors = shapeDraftAnchors(
                    state.shapeOptions,
                    start,
                    end,
                ) { PathAnchorId(previewAnchorId--) }
                val closed = state.shapeOptions.kind != VectorShapeKind.LINE
                fun viewPoint(point: DocPoint, handle: DocPoint? = null): Offset {
                    val withHandle = DocPoint(point.x + (handle?.x ?: 0f), point.y + (handle?.y ?: 0f))
                    val view = viewport.docToView(withHandle)
                    return Offset(view.x, view.y)
                }
                val outline = Path().apply {
                    val first = viewPoint(anchors.first().point)
                    moveTo(first.x, first.y)
                    val segmentCount = if (closed) anchors.size else anchors.lastIndex
                    repeat(segmentCount) { index ->
                        val current = anchors[index]
                        val next = anchors[(index + 1) % anchors.size]
                        val endpoint = viewPoint(next.point)
                        if (current.outgoing != null || next.incoming != null) {
                            val control1 = viewPoint(current.point, current.outgoing)
                            val control2 = viewPoint(next.point, next.incoming)
                            cubicTo(control1.x, control1.y, control2.x, control2.y, endpoint.x, endpoint.y)
                        } else {
                            lineTo(endpoint.x, endpoint.y)
                        }
                    }
                    if (closed) close()
                }
                drawPath(outline, lineColor, style = Stroke(1.5f))
            }
            selectedPath?.subpaths?.forEach { subpath ->
                val anchors = subpath.anchors
                anchors.zipWithNext().forEach { (a, b) ->
                    val av = viewport.docToView(a.point)
                    val bv = viewport.docToView(b.point)
                    drawLine(lineColor.copy(alpha = 0.7f), Offset(av.x, av.y), Offset(bv.x, bv.y), 1f)
                }
                anchors.forEach { anchor ->
                    val p = viewport.docToView(anchor.point)
                    val selected = state.selectedAnchor?.anchorId == anchor.id
                    drawCircle(if (selected) Color.White else lineColor, radius = if (selected) 5f else 4f, center = Offset(p.x, p.y))
                    drawCircle(lineColor, radius = if (selected) 3f else 2f, center = Offset(p.x, p.y))
                }
            }
            state.selectedAnchor?.let { selection ->
                if (selection.pathId == selectedPath?.id) {
                    selectedPath.subpaths.getOrNull(selection.subpathIndex)?.anchors
                        ?.firstOrNull { it.id == selection.anchorId }
                        ?.let { anchor ->
                            val point = viewport.docToView(anchor.point)
                            val center = Offset(point.x, point.y)
                            listOfNotNull(anchor.incoming, anchor.outgoing).forEach { handle ->
                                val endpoint = viewport.docToView(anchor.point + handle)
                                val handleCenter = Offset(endpoint.x, endpoint.y)
                                drawLine(lineColor.copy(alpha = 0.8f), center, handleCenter, 1f)
                                drawCircle(Color.White, radius = 4f, center = handleCenter)
                                drawCircle(lineColor, radius = 3f, center = handleCenter, style = Stroke(1f))
                            }
                        }
                }
            }
            selectedPath?.takeIf {
                tool in setOf(ToolId.PATH_SELECT, ToolId.DIRECT_SELECT) &&
                    it.primitive?.kind == VectorShapeKind.ROUNDED_RECTANGLE
            }?.let { path ->
                RoundedCorner.entries.forEach { corner ->
                    roundedRectangleRadiusHandle(path, corner)?.let { handle ->
                        val view = viewport.docToView(handle)
                        val center = Offset(view.x, view.y)
                        drawCircle(Color.White, radius = 7f, center = center)
                        drawCircle(lineColor, radius = 5f, center = center, style = Stroke(1.5f))
                    }
                }
            }
        }
    }

    private fun ToolId.shapeKind(): VectorShapeKind = when (this) {
        ToolId.ELLIPSE -> VectorShapeKind.ELLIPSE
        ToolId.POLYGON -> VectorShapeKind.POLYGON
        ToolId.LINE -> VectorShapeKind.LINE
        else -> VectorShapeKind.RECTANGLE
    }
}
