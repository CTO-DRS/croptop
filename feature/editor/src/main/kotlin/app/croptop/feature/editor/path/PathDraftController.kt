package app.croptop.feature.editor.path

import app.croptop.core.model.DocPoint
import app.croptop.core.model.PathAnchor
import app.croptop.core.model.PathAnchorId
import app.croptop.core.model.PathAnchorMode
import app.croptop.core.model.SelectionShape
import app.croptop.core.model.ShapeToolOptions
import app.croptop.core.model.VectorPathDraft
import app.croptop.core.model.VectorPathId
import app.croptop.core.model.VectorPathStyle
import app.croptop.core.model.VectorShapeKind
import app.croptop.core.model.VectorSourceBox
import app.croptop.core.model.VectorSubpath
import app.croptop.core.model.toPathStyle
import app.croptop.core.model.toPrimitiveMetadata
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Selection of one anchor in the transient path editor. */
data class PathAnchorSelection(val pathId: VectorPathId, val subpathIndex: Int, val anchorId: PathAnchorId)

enum class RoundedCorner { TOP_LEFT, TOP_RIGHT, BOTTOM_RIGHT, BOTTOM_LEFT }

/** UI-only path state. Nothing here mutates a document or creates an undo step. */
data class PathDraftState(
    val shapeOptions: ShapeToolOptions = ShapeToolOptions(),
    val paths: List<VectorPathDraft> = emptyList(),
    val selectedPathId: VectorPathId? = null,
    val selectedAnchor: PathAnchorSelection? = null,
    val message: String? = null,
)

/**
 * Editor-side path working set synchronized with the selected engine-owned shape layer.
 */
class PathDraftController(initial: PathDraftState = PathDraftState()) {
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<PathDraftState> = _state.asStateFlow()

    private var nextPathId = (initial.paths.maxOfOrNull { it.id.value } ?: 0L) + 1L
    private var nextAnchorId = (
        initial.paths.flatMap { path -> path.subpaths.flatMap { it.anchors } }
            .maxOfOrNull { it.id.value } ?: 0L
        ) + 1L

    @Synchronized
    fun setShapeOptions(options: ShapeToolOptions) {
        _state.value = _state.value.copy(shapeOptions = options.normalized(), message = null)
    }

    /** Replaces local drafts from an engine layer snapshot while preserving valid local selection. */
    @Synchronized
    fun loadPersistedPaths(paths: List<VectorPathDraft>) {
        if (_state.value.paths == paths) return
        nextPathId = (paths.maxOfOrNull { it.id.value } ?: 0L) + 1L
        nextAnchorId = (paths.flatMap { path -> path.subpaths.flatMap { it.anchors } }.maxOfOrNull { it.id.value } ?: 0L) + 1L
        val selectedPath = _state.value.selectedPathId?.takeIf { candidate -> paths.any { it.id == candidate } } ?: paths.firstOrNull()?.id
        val selectedAnchor = _state.value.selectedAnchor?.takeIf { selection ->
            paths.firstOrNull { it.id == selection.pathId }
                ?.subpaths?.getOrNull(selection.subpathIndex)
                ?.anchors?.any { it.id == selection.anchorId } == true
        }
        _state.value = _state.value.copy(paths = paths, selectedPathId = selectedPath, selectedAnchor = selectedAnchor, message = null)
    }

    /** Starts a local draft from a Pen press. [handleVector] is the drag vector in document pixels. */
    @Synchronized
    fun beginPenPath(point: DocPoint, handleVector: DocPoint? = null): VectorPathDraft {
        val existingNames = _state.value.paths.map { it.name.lowercase() }.toSet()
        val name = generateSequence(1) { it + 1 }.map { "Path $it" }.first { it.lowercase() !in existingNames }
        val handle = handleVector?.takeUnless { it.isZero() }
        val anchor = PathAnchor(
            id = nextAnchorId(),
            point = point,
            incoming = handle?.negated(),
            outgoing = handle,
            mode = if (handle == null) PathAnchorMode.CORNER else PathAnchorMode.SMOOTH,
        )
        val path = VectorPathDraft(
            id = nextPathId(),
            name = name,
            subpaths = listOf(VectorSubpath(listOf(anchor))),
            style = _state.value.shapeOptions.toPathStyle(),
        )
        _state.value = _state.value.copy(
            paths = _state.value.paths + path,
            selectedPathId = path.id,
            selectedAnchor = PathAnchorSelection(path.id, 0, anchor.id),
            message = null,
        )
        return path
    }

    /** Creates an editable rectangle or ellipse draft from a document-space drag. */
    @Synchronized
    fun beginShapePath(start: DocPoint, end: DocPoint): VectorPathDraft? {
        val options = _state.value.shapeOptions.normalized()
        if (options.kind !in setOf(
                VectorShapeKind.RECTANGLE,
                VectorShapeKind.ROUNDED_RECTANGLE,
                VectorShapeKind.ELLIPSE,
                VectorShapeKind.POLYGON,
                VectorShapeKind.STAR,
                VectorShapeKind.LINE,
            )
        ) {
            reject("${options.kind.label} drafts are not available yet")
            return null
        }
        if (listOf(start.x, start.y, end.x, end.y).any { !it.isFinite() || kotlin.math.abs(it) > MAX_COORDINATE }) {
            reject("Shape bounds must be finite and within the document range")
            return null
        }
        val left = minOf(start.x, end.x)
        val top = minOf(start.y, end.y)
        val width = kotlin.math.abs(end.x - start.x)
        val height = kotlin.math.abs(end.y - start.y)
        val isLine = options.kind == VectorShapeKind.LINE
        val invalidSize = if (isLine) width <= 0f && height <= 0f else width <= 0f || height <= 0f
        if (invalidSize) {
            reject(if (isLine) "Drag between two distinct points to create a line" else "Drag a wider and taller area to create a shape")
            return null
        }
        if (!listOf(left + width, top + height).all { it.isFinite() && kotlin.math.abs(it) <= MAX_COORDINATE }) {
            reject("Shape bounds must be finite and within the document range")
            return null
        }

        // A Line is a directed open path, not a bounded live primitive. Its endpoints are already
        // represented exactly by anchors; the engine's primitive source-box contract requires two
        // positive dimensions and cannot represent horizontal or vertical lines without changing them.
        val primitive =
            if (isLine) {
                null
            } else {
                options.toPrimitiveMetadata().copy(sourceBox = VectorSourceBox(left, top, width, height))
            }
        val anchors = shapeDraftAnchors(options, start, end, ::nextAnchorId)
        val existingNames = _state.value.paths.map { it.name.lowercase() }.toSet()
        val stem = options.kind.label
        val name = generateSequence(1) { it + 1 }.map { "$stem $it" }.first { it.lowercase() !in existingNames }
        val path = VectorPathDraft(
            id = nextPathId(),
            name = name,
            subpaths = listOf(VectorSubpath(anchors, closed = options.kind != VectorShapeKind.LINE)),
            style = options.toPathStyle(),
            primitive = primitive,
        )
        _state.value = _state.value.copy(
            paths = _state.value.paths + path,
            selectedPathId = path.id,
            selectedAnchor = null,
            message = null,
        )
        return path
    }

    /** Adds an anchor to the active open subpath; a drag creates a smooth pair of handles. */
    @Synchronized
    fun appendPenAnchor(point: DocPoint, handleVector: DocPoint? = null): Boolean {
        val state = _state.value
        val pathIndex = state.paths.indexOfFirst { it.id == state.selectedPathId }
        if (pathIndex < 0) return reject("Start a path before adding an anchor")
        val path = state.paths[pathIndex]
        val subpathIndex = path.subpaths.lastIndex
        if (subpathIndex < 0 || path.subpaths[subpathIndex].closed) return reject("The selected path is closed")
        val vector = handleVector?.takeUnless { it.isZero() }
        val anchor = PathAnchor(
            id = nextAnchorId(),
            point = point,
            incoming = vector?.negated(),
            outgoing = vector,
            mode = if (vector == null) PathAnchorMode.CORNER else PathAnchorMode.SMOOTH,
        )
        val subpaths = path.subpaths.toMutableList().apply {
            this[subpathIndex] = this[subpathIndex].copy(anchors = this[subpathIndex].anchors + anchor)
        }
        replacePath(pathIndex, path.copy(subpaths = subpaths, primitive = null))
        _state.value = _state.value.copy(selectedAnchor = PathAnchorSelection(path.id, subpathIndex, anchor.id), message = null)
        return true
    }

    @Synchronized
    fun selectPath(id: VectorPathId?) {
        val selected = id?.takeIf { candidate -> _state.value.paths.any { it.id == candidate } }
        _state.value = _state.value.copy(selectedPathId = selected, selectedAnchor = null, message = null)
    }

    /** Translates every anchor while retaining editable primitive parameters and adjusted source bounds. */
    @Synchronized
    fun movePath(id: VectorPathId, offset: DocPoint): Boolean {
        if (offset.x == 0f && offset.y == 0f) return false
        val index = _state.value.paths.indexOfFirst { it.id == id }
        if (index < 0) return reject("The selected path no longer exists")
        val path = _state.value.paths[index]
        val subpaths = path.subpaths.map { subpath ->
            subpath.copy(anchors = subpath.anchors.map { anchor -> anchor.copy(point = anchor.point + offset) })
        }
        val primitive = path.primitive?.let { metadata ->
            metadata.copy(sourceBox = metadata.sourceBox?.let { box -> box.copy(x = box.x + offset.x, y = box.y + offset.y) })
        }
        replacePath(index, path.copy(subpaths = subpaths, primitive = primitive))
        _state.value = _state.value.copy(message = null)
        return true
    }

    @Synchronized
    fun showMessage(message: String) {
        _state.value = _state.value.copy(message = message)
    }

    /** Replaces the selected path's paint style without changing its geometry or primitive data. */
    @Synchronized
    fun setSelectedPathStyle(style: VectorPathStyle): Boolean {
        val state = _state.value
        val index = state.paths.indexOfFirst { it.id == state.selectedPathId }
        if (index < 0) return reject("Select a path before changing its fill or stroke")
        replacePath(index, state.paths[index].copy(style = style.normalized()))
        _state.value = _state.value.copy(message = null)
        return true
    }

    /** Updates one rounded-rectangle radius from a document-space drag handle position. */
    @Synchronized
    fun setSelectedRoundedCornerFromPoint(corner: RoundedCorner, point: DocPoint): Boolean {
        if (!point.x.isFinite() || !point.y.isFinite() ||
            kotlin.math.abs(point.x) > MAX_COORDINATE || kotlin.math.abs(point.y) > MAX_COORDINATE
        ) {
            return reject("Corner position must be finite and within the document range")
        }
        val state = _state.value
        val index = state.paths.indexOfFirst { it.id == state.selectedPathId }
        if (index < 0) return reject("Select a rounded rectangle before editing its corners")
        val path = state.paths[index]
        val primitive = path.primitive?.takeIf { it.kind == VectorShapeKind.ROUNDED_RECTANGLE }
            ?: return reject("Selected path is not a live rounded rectangle")
        val box = primitive.sourceBox ?: return reject("Rounded rectangle has no editable source bounds")
        val center = DocPoint(box.x + box.width / 2f, box.y + box.height / 2f)
        val unrotated = point.rotateAround(center, -primitive.rotationDegrees)
        val right = box.x + box.width
        val bottom = box.y + box.height
        val radius = when (corner) {
            RoundedCorner.TOP_LEFT -> ((unrotated.x - box.x) + (unrotated.y - box.y)) / 2f
            RoundedCorner.TOP_RIGHT -> ((right - unrotated.x) + (unrotated.y - box.y)) / 2f
            RoundedCorner.BOTTOM_RIGHT -> ((right - unrotated.x) + (bottom - unrotated.y)) / 2f
            RoundedCorner.BOTTOM_LEFT -> ((unrotated.x - box.x) + (bottom - unrotated.y)) / 2f
        }.coerceIn(0f, minOf(box.width, box.height) / 2f)
        val currentRadius = when (corner) {
            RoundedCorner.TOP_LEFT -> primitive.cornerRadii.topLeft
            RoundedCorner.TOP_RIGHT -> primitive.cornerRadii.topRight
            RoundedCorner.BOTTOM_RIGHT -> primitive.cornerRadii.bottomRight
            RoundedCorner.BOTTOM_LEFT -> primitive.cornerRadii.bottomLeft
        }
        if (kotlin.math.abs(currentRadius - radius) < 0.01f) return true
        val radii = when (corner) {
            RoundedCorner.TOP_LEFT -> primitive.cornerRadii.copy(topLeft = radius)
            RoundedCorner.TOP_RIGHT -> primitive.cornerRadii.copy(topRight = radius)
            RoundedCorner.BOTTOM_RIGHT -> primitive.cornerRadii.copy(bottomRight = radius)
            RoundedCorner.BOTTOM_LEFT -> primitive.cornerRadii.copy(bottomLeft = radius)
        }.normalized()
        val anchors = roundedRectangleDraftAnchors(
            box.x,
            box.y,
            box.width,
            box.height,
            radii,
            primitive.rotationDegrees,
            ::nextAnchorId,
        )
        val updated = path.copy(
            subpaths = listOf(VectorSubpath(anchors, closed = true)),
            primitive = primitive.copy(cornerRadii = radii),
        )
        replacePath(index, updated)
        _state.value = _state.value.copy(selectedAnchor = null, message = null)
        return true
    }

    @Synchronized
    fun selectAnchor(selection: PathAnchorSelection?) {
        _state.value = _state.value.copy(selectedAnchor = selection?.takeIf(::containsAnchor), message = null)
    }

    /** Moves an anchor while preserving its relative handle vectors. */
    @Synchronized
    fun moveAnchor(selection: PathAnchorSelection, point: DocPoint): Boolean = updateAnchor(selection) { it.copy(point = point) }

    /** Moves one Bézier handle endpoint. Smooth anchors keep tangent direction; symmetric anchors also keep length. */
    @Synchronized
    fun moveHandle(selection: PathAnchorSelection, incoming: Boolean, endpoint: DocPoint): Boolean = updateAnchor(selection) { anchor ->
        val vector = endpoint - anchor.point
        val opposite = if (incoming) anchor.outgoing else anchor.incoming
        when (anchor.mode) {
            PathAnchorMode.CORNER -> if (incoming) anchor.copy(incoming = vector) else anchor.copy(outgoing = vector)

            PathAnchorMode.SMOOTH -> {
                val oppositeLength = opposite?.length() ?: vector.length()
                val mirrored = vector.oppositeWithLength(oppositeLength)
                if (incoming) anchor.copy(incoming = vector, outgoing = mirrored) else anchor.copy(incoming = mirrored, outgoing = vector)
            }

            PathAnchorMode.SYMMETRIC -> {
                val mirrored = vector.negated()
                if (incoming) anchor.copy(incoming = vector, outgoing = mirrored) else anchor.copy(incoming = mirrored, outgoing = vector)
            }
        }
    }

    @Synchronized
    fun setAnchorMode(selection: PathAnchorSelection, mode: PathAnchorMode): Boolean = updateAnchor(selection) { anchor ->
        when (mode) {
            PathAnchorMode.CORNER -> anchor.copy(mode = mode)
            PathAnchorMode.SMOOTH -> anchor.withAlignedHandles(preserveLength = true).copy(mode = mode)
            PathAnchorMode.SYMMETRIC -> anchor.withAlignedHandles(preserveLength = false).copy(mode = mode)
        }
    }

    /** A contour requires three anchors before it can be closed as a fillable shape. */
    @Synchronized
    fun closeActivePath(): Boolean {
        val state = _state.value
        val pathIndex = state.paths.indexOfFirst { it.id == state.selectedPathId }
        if (pathIndex < 0) return reject("Select a path before closing it")
        val path = state.paths[pathIndex]
        val subpathIndex = path.subpaths.lastIndex
        if (subpathIndex < 0) return reject("The selected path has no anchors")
        val subpath = path.subpaths[subpathIndex]
        if (subpath.closed) return true
        if (subpath.anchors.size < MIN_CLOSED_ANCHORS) return reject("Add at least three anchors to close a path")
        val subpaths = path.subpaths.toMutableList().apply { this[subpathIndex] = subpath.copy(closed = true) }
        replacePath(pathIndex, path.copy(subpaths = subpaths, primitive = null))
        _state.value = _state.value.copy(message = null)
        return true
    }

    @Synchronized
    fun renamePath(id: VectorPathId, name: String): Boolean {
        val clean = name.trim().take(MAX_PATH_NAME_LENGTH)
        if (clean.isBlank()) return reject("Path names cannot be empty")
        val index = _state.value.paths.indexOfFirst { it.id == id }
        if (index < 0) return reject("The selected path no longer exists")
        replacePath(index, _state.value.paths[index].copy(name = clean))
        _state.value = _state.value.copy(message = null)
        return true
    }

    @Synchronized
    fun deleteSelectedPath(): Boolean {
        val state = _state.value
        val selected = state.selectedPathId ?: return reject("Select a path to delete it")
        val paths = state.paths.filterNot { it.id == selected }
        _state.value = state.copy(
            paths = paths,
            selectedPathId = paths.lastOrNull()?.id,
            selectedAnchor = null,
            message = null,
        )
        return true
    }

    @Synchronized
    fun deleteSelectedAnchor(): Boolean {
        val state = _state.value
        val selection = state.selectedAnchor ?: return reject("Select an anchor to delete it")
        val pathIndex = state.paths.indexOfFirst { it.id == selection.pathId }
        if (pathIndex < 0) return reject("The selected path no longer exists")
        val path = state.paths[pathIndex]
        val subpath = path.subpaths.getOrNull(selection.subpathIndex) ?: return reject("The selected subpath no longer exists")
        val anchorIndex = subpath.anchors.indexOfFirst { it.id == selection.anchorId }
        if (anchorIndex < 0) return reject("The selected anchor no longer exists")
        val anchors = subpath.anchors.toMutableList().apply { removeAt(anchorIndex) }
        val subpaths = path.subpaths.toMutableList()
        if (anchors.isEmpty()) {
            subpaths.removeAt(selection.subpathIndex)
        } else {
            subpaths[selection.subpathIndex] = subpath.copy(anchors = anchors, closed = subpath.closed && anchors.size >= MIN_CLOSED_ANCHORS)
        }
        val paths = state.paths.toMutableList()
        if (subpaths.isEmpty()) {
            paths.removeAt(pathIndex)
        } else {
            paths[pathIndex] = path.copy(subpaths = subpaths, primitive = null)
        }
        val nextPath = paths.getOrNull(pathIndex.coerceAtMost(paths.lastIndex))
        val nextSubpathIndex = nextPath?.subpaths?.lastIndex ?: -1
        val nextAnchor = nextPath?.subpaths?.getOrNull(nextSubpathIndex)?.anchors?.lastOrNull()
        _state.value = state.copy(
            paths = paths,
            selectedPathId = nextPath?.id,
            selectedAnchor = nextAnchor?.let { PathAnchorSelection(nextPath.id, nextSubpathIndex, it.id) },
            message = null,
        )
        return true
    }

    private fun updateAnchor(selection: PathAnchorSelection, edit: (PathAnchor) -> PathAnchor): Boolean {
        val pathIndex = _state.value.paths.indexOfFirst { it.id == selection.pathId }
        if (pathIndex < 0) return reject("The selected path no longer exists")
        val path = _state.value.paths[pathIndex]
        val subpath = path.subpaths.getOrNull(selection.subpathIndex) ?: return reject("The selected subpath no longer exists")
        val anchorIndex = subpath.anchors.indexOfFirst { it.id == selection.anchorId }
        if (anchorIndex < 0) return reject("The selected anchor no longer exists")
        val anchors = subpath.anchors.toMutableList().apply { this[anchorIndex] = edit(this[anchorIndex]) }
        val subpaths = path.subpaths.toMutableList().apply { this[selection.subpathIndex] = subpath.copy(anchors = anchors) }
        replacePath(pathIndex, path.copy(subpaths = subpaths, primitive = null))
        _state.value = _state.value.copy(selectedAnchor = selection, message = null)
        return true
    }

    private fun containsAnchor(selection: PathAnchorSelection): Boolean = _state.value.paths
        .firstOrNull { it.id == selection.pathId }
        ?.subpaths
        ?.getOrNull(selection.subpathIndex)
        ?.anchors
        ?.any { it.id == selection.anchorId } == true

    private fun replacePath(index: Int, path: VectorPathDraft) {
        _state.value = _state.value.copy(paths = _state.value.paths.toMutableList().apply { this[index] = path })
    }

    private fun reject(message: String): Boolean {
        _state.value = _state.value.copy(message = message)
        return false
    }

    private fun nextPathId() = VectorPathId(nextPathId++)
    private fun nextAnchorId() = PathAnchorId(nextAnchorId++)

    companion object {
        const val MIN_CLOSED_ANCHORS = 3
        const val MAX_PATH_NAME_LENGTH = 120
        const val MAX_COORDINATE = 10_000_000f
    }
}

/** Shared document-space geometry for committed paths and their live previews. */
internal fun shapeDraftAnchors(
    options: ShapeToolOptions,
    start: DocPoint,
    end: DocPoint,
    newAnchorId: () -> PathAnchorId,
): List<PathAnchor> {
    val left = minOf(start.x, end.x)
    val top = minOf(start.y, end.y)
    val width = kotlin.math.abs(end.x - start.x)
    val height = kotlin.math.abs(end.y - start.y)
    val center = DocPoint(left + width / 2f, top + height / 2f)
    val rotation = options.rotationDegrees
    return when (options.kind) {
        VectorShapeKind.LINE -> lineDraftAnchors(start, end, center, rotation, newAnchorId)

        VectorShapeKind.RECTANGLE -> listOf(
            DocPoint(left, top),
            DocPoint(left + width, top),
            DocPoint(left + width, top + height),
            DocPoint(left, top + height),
        ).map { point -> PathAnchor(newAnchorId(), point.rotateAround(center, rotation)) }

        VectorShapeKind.ROUNDED_RECTANGLE -> roundedRectangleDraftAnchors(
            left,
            top,
            width,
            height,
            options.cornerRadii,
            rotation,
            newAnchorId,
        )

        VectorShapeKind.ELLIPSE -> {
            val rx = width / 2f
            val ry = height / 2f
            val kappa = 0.552284749831f
            listOf(
                DocPoint(center.x + rx, center.y) to DocPoint(0f, ry * kappa),
                DocPoint(center.x, center.y + ry) to DocPoint(-rx * kappa, 0f),
                DocPoint(center.x - rx, center.y) to DocPoint(0f, -ry * kappa),
                DocPoint(center.x, center.y - ry) to DocPoint(rx * kappa, 0f),
            ).map { (point, outgoing) ->
                PathAnchor(
                    id = newAnchorId(),
                    point = point.rotateAround(center, rotation),
                    incoming = outgoing.negated().rotateVector(rotation),
                    outgoing = outgoing.rotateVector(rotation),
                    mode = PathAnchorMode.SYMMETRIC,
                )
            }
        }

        VectorShapeKind.POLYGON, VectorShapeKind.STAR -> polygonAnchorPoints(
            center = center,
            width = width,
            height = height,
            sides = options.polygonSides,
            innerRatio = if (options.kind == VectorShapeKind.STAR) options.starInnerRatio else 1f,
            rotationDegrees = rotation,
        ).map { PathAnchor(newAnchorId(), it) }
    }
}

internal fun polygonAnchorPoints(
    center: DocPoint,
    width: Float,
    height: Float,
    sides: Int,
    innerRatio: Float,
    rotationDegrees: Float,
): List<DocPoint> {
    val count = sides * if (innerRatio < 1f) 2 else 1
    return (0 until count).map { index ->
        val angle = -Math.PI.toFloat() / 2f + Math.PI.toFloat() * 2f * index / count
        val radius = if (count > sides && index % 2 == 1) innerRatio else 1f
        DocPoint(
            center.x + width / 2f * radius * cos(angle),
            center.y + height / 2f * radius * sin(angle),
        ).rotateAround(center, rotationDegrees)
    }
}

internal fun roundedRectangleAnchors(
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    radii: app.croptop.core.model.ShapeCornerRadii,
    newAnchorId: () -> PathAnchorId,
): List<PathAnchor> {
    val limit = minOf(width, height) / 2f
    val tl = radii.topLeft.coerceAtMost(limit)
    val tr = radii.topRight.coerceAtMost(limit)
    val br = radii.bottomRight.coerceAtMost(limit)
    val bl = radii.bottomLeft.coerceAtMost(limit)
    val kappa = 0.552284749831f
    val anchors = mutableListOf<PathAnchor>(PathAnchor(id = newAnchorId(), point = DocPoint(left + tl, top)))

    fun lineTo(point: DocPoint) {
        anchors += PathAnchor(id = newAnchorId(), point = point)
    }

    fun curveTo(control1: DocPoint, control2: DocPoint, point: DocPoint) {
        val previous = anchors.removeAt(anchors.lastIndex)
        anchors += previous.copy(outgoing = control1 - previous.point)
        anchors += PathAnchor(id = newAnchorId(), point = point, incoming = control2 - point)
    }

    lineTo(DocPoint(left + width - tr, top))
    if (tr > 0f) {
        curveTo(
            DocPoint(left + width - tr + tr * kappa, top),
            DocPoint(left + width, top + tr - tr * kappa),
            DocPoint(left + width, top + tr),
        )
    }
    lineTo(DocPoint(left + width, top + height - br))
    if (br > 0f) {
        curveTo(
            DocPoint(left + width, top + height - br + br * kappa),
            DocPoint(left + width - br + br * kappa, top + height),
            DocPoint(left + width - br, top + height),
        )
    }
    lineTo(DocPoint(left + bl, top + height))
    if (bl > 0f) {
        curveTo(
            DocPoint(left + bl - bl * kappa, top + height),
            DocPoint(left, top + height - bl + bl * kappa),
            DocPoint(left, top + height - bl),
        )
    }
    lineTo(DocPoint(left, top + tl))
    if (tl > 0f) {
        curveTo(
            DocPoint(left, top + tl - tl * kappa),
            DocPoint(left + tl - tl * kappa, top),
            DocPoint(left + tl, top),
        )
    }
    if (anchors.last().point == anchors.first().point) {
        val closing = anchors.removeAt(anchors.lastIndex)
        anchors[0] = anchors.first().copy(incoming = closing.incoming)
    }
    return anchors
}

internal fun roundedRectangleDraftAnchors(
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    radii: app.croptop.core.model.ShapeCornerRadii,
    rotationDegrees: Float,
    newAnchorId: () -> PathAnchorId,
): List<PathAnchor> {
    val center = DocPoint(left + width / 2f, top + height / 2f)
    return roundedRectangleAnchors(left, top, width, height, radii, newAnchorId).map { anchor ->
        anchor.copy(
            point = anchor.point.rotateAround(center, rotationDegrees),
            incoming = anchor.incoming?.rotateVector(rotationDegrees),
            outgoing = anchor.outgoing?.rotateVector(rotationDegrees),
        )
    }
}

internal fun roundedRectangleRadiusHandle(path: VectorPathDraft, corner: RoundedCorner): DocPoint? {
    val primitive = path.primitive?.takeIf { it.kind == VectorShapeKind.ROUNDED_RECTANGLE } ?: return null
    val box = primitive.sourceBox ?: return null
    val radii = primitive.cornerRadii.normalized()
    val limit = minOf(box.width, box.height) / 2f
    val tl = radii.topLeft.coerceAtMost(limit)
    val tr = radii.topRight.coerceAtMost(limit)
    val br = radii.bottomRight.coerceAtMost(limit)
    val bl = radii.bottomLeft.coerceAtMost(limit)
    val local = when (corner) {
        RoundedCorner.TOP_LEFT -> DocPoint(box.x + tl, box.y + tl)
        RoundedCorner.TOP_RIGHT -> DocPoint(box.x + box.width - tr, box.y + tr)
        RoundedCorner.BOTTOM_RIGHT -> DocPoint(box.x + box.width - br, box.y + box.height - br)
        RoundedCorner.BOTTOM_LEFT -> DocPoint(box.x + bl, box.y + box.height - bl)
    }
    return local.rotateAround(DocPoint(box.x + box.width / 2f, box.y + box.height / 2f), primitive.rotationDegrees)
}

/** Flattens one closed subpath for the engine's polygon-selection contract. */
internal fun VectorPathDraft.toSelectionPolygon(): SelectionShape.Polygon? {
    if (subpaths.size != 1) return null
    val subpath = subpaths.single()
    if (!subpath.closed || subpath.anchors.size < 3) return null
    val points = mutableListOf<DocPoint>()
    val anchors = subpath.anchors
    anchors.indices.forEach { index ->
        val current = anchors[index]
        val next = anchors[(index + 1) % anchors.size]
        if (points.lastOrNull() != current.point) points += current.point
        if (current.outgoing != null || next.incoming != null) {
            val control1 = current.point + (current.outgoing ?: DocPoint.Zero)
            val control2 = next.point + (next.incoming ?: DocPoint.Zero)
            flattenCubic(current.point, control1, control2, next.point, points)
        } else if (points.lastOrNull() != next.point) {
            points += next.point
        }
        if (points.size > MAX_SELECTION_VERTICES) return null
    }
    if (points.lastOrNull() == points.firstOrNull()) points.removeAt(points.lastIndex)
    if (points.size < 3 || points.size > MAX_SELECTION_VERTICES) return null
    return SelectionShape.Polygon(points)
}

private fun flattenCubic(
    start: DocPoint,
    control1: DocPoint,
    control2: DocPoint,
    end: DocPoint,
    points: MutableList<DocPoint>,
    depth: Int = 0,
) {
    if (depth >= MAX_CURVE_DEPTH ||
        maxOf(distanceToSegment(control1, start, end), distanceToSegment(control2, start, end)) <= CURVE_FLATNESS
    ) {
        points += end
        return
    }
    fun midpoint(a: DocPoint, b: DocPoint) = DocPoint((a.x + b.x) / 2f, (a.y + b.y) / 2f)
    val p01 = midpoint(start, control1)
    val p12 = midpoint(control1, control2)
    val p23 = midpoint(control2, end)
    val p012 = midpoint(p01, p12)
    val p123 = midpoint(p12, p23)
    val center = midpoint(p012, p123)
    flattenCubic(start, p01, p012, center, points, depth + 1)
    flattenCubic(center, p123, p23, end, points, depth + 1)
}

private fun distanceToSegment(point: DocPoint, start: DocPoint, end: DocPoint): Float {
    val dx = end.x - start.x
    val dy = end.y - start.y
    val lengthSquared = dx * dx + dy * dy
    if (lengthSquared == 0f) return hypot((point.x - start.x).toDouble(), (point.y - start.y).toDouble()).toFloat()
    val projection = (((point.x - start.x) * dx + (point.y - start.y) * dy) / lengthSquared).coerceIn(0f, 1f)
    return hypot(
        (point.x - start.x - projection * dx).toDouble(),
        (point.y - start.y - projection * dy).toDouble(),
    ).toFloat()
}

private const val CURVE_FLATNESS = 0.25f
private const val MAX_CURVE_DEPTH = 12
private const val MAX_SELECTION_VERTICES = 1_000_000

internal fun lineDraftAnchors(
    start: DocPoint,
    end: DocPoint,
    center: DocPoint,
    rotationDegrees: Float,
    newAnchorId: () -> PathAnchorId,
): List<PathAnchor> = listOf(start, end).map { point ->
    PathAnchor(id = newAnchorId(), point = point.rotateAround(center, rotationDegrees))
}

private fun PathAnchor.withAlignedHandles(preserveLength: Boolean): PathAnchor {
    val inVector = incoming
    val outVector = outgoing
    if (inVector == null && outVector == null) return this
    val source = outVector ?: inVector!!.negated()
    val targetLength = if (preserveLength) inVector?.length() ?: outVector?.length() ?: 0f else source.length()
    val aligned = source.oppositeWithLength(targetLength)
    return copy(
        incoming = aligned,
        outgoing = if (preserveLength) source else aligned.negated(),
    )
}

private fun DocPoint.isZero() = x == 0f && y == 0f

private fun DocPoint.negated() = DocPoint(if (x == 0f) 0f else -x, if (y == 0f) 0f else -y)

private fun DocPoint.length(): Float = hypot(x.toDouble(), y.toDouble()).toFloat()

private fun DocPoint.oppositeWithLength(length: Float): DocPoint {
    val current = this.length()
    if (current == 0f || length == 0f) return DocPoint.Zero
    val scale = length / current
    return DocPoint(-x * scale, -y * scale)
}

internal fun DocPoint.rotateAround(center: DocPoint, degrees: Float): DocPoint {
    val vector = this - center
    val rotated = vector.rotateVector(degrees)
    return center + rotated
}

internal fun DocPoint.rotateVector(degrees: Float): DocPoint {
    if (degrees == 0f) return this
    val radians = Math.toRadians(degrees.toDouble())
    val cosine = kotlin.math.cos(radians).toFloat()
    val sine = kotlin.math.sin(radians).toFloat()
    return DocPoint(x * cosine - y * sine, x * sine + y * cosine)
}
