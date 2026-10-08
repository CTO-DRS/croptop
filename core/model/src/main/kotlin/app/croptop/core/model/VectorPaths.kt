package app.croptop.core.model

import kotlinx.serialization.Serializable

/** Stable identity for an editable vector path. Geometry values alone do not imply document storage. */
@Serializable
@JvmInline
value class VectorPathId(val value: Long)

/** Stable identity for an anchor within the editor path model. */
@Serializable
@JvmInline
value class PathAnchorId(val value: Long)

@Serializable
enum class VectorShapeKind(val label: String) {
    RECTANGLE("Rectangle"),
    ROUNDED_RECTANGLE("Rounded Rectangle"),
    ELLIPSE("Ellipse"),
    POLYGON("Polygon"),
    STAR("Star"),
    LINE("Line"),
}

@Serializable
enum class PathAnchorMode(val label: String) {
    CORNER("Corner"),
    SMOOTH("Smooth"),
    SYMMETRIC("Symmetric"),
}

@Serializable
enum class PathStrokeJoin { MITER, ROUND, BEVEL }

@Serializable
enum class PathStrokeCap { BUTT, ROUND, SQUARE }

@Serializable
enum class PathStrokeAlignment { CENTER, INSIDE, OUTSIDE }

/** Serializable style stored with a vector path record. Null fill/stroke disables that paint. */
@Serializable
data class VectorPathStyle(
    val fillColor: RgbaColor? = RgbaColor.Transparent,
    val strokeColor: RgbaColor? = RgbaColor.Black,
    val strokeWidth: Float = 1f,
    val join: PathStrokeJoin = PathStrokeJoin.MITER,
    val miterLimit: Float = ShapeToolOptions.DEFAULT_MITER_LIMIT,
    val cap: PathStrokeCap = PathStrokeCap.BUTT,
    val alignment: PathStrokeAlignment = PathStrokeAlignment.CENTER,
    val dashLengths: List<Float> = emptyList(),
    val dashOffset: Float = 0f,
    val arrowAtStart: Boolean = false,
    val arrowAtEnd: Boolean = false,
    val arrowLength: Float = 4f,
    val arrowWidth: Float = 3f,
) {
    fun normalized(): VectorPathStyle {
        val options = ShapeToolOptions(
            fillColor = fillColor ?: RgbaColor.Transparent,
            strokeColor = strokeColor ?: RgbaColor.Black,
            strokeWidth = strokeWidth,
            join = join,
            miterLimit = miterLimit,
            cap = cap,
            alignment = alignment,
            dashLengths = dashLengths,
            dashOffset = dashOffset,
            arrowAtStart = arrowAtStart,
            arrowAtEnd = arrowAtEnd,
            arrowLength = arrowLength,
            arrowWidth = arrowWidth,
        ).normalized()
        return copy(
            fillColor = fillColor?.normalizedColor(),
            strokeColor = strokeColor?.normalizedColor(),
            strokeWidth = options.strokeWidth,
            miterLimit = options.miterLimit,
            dashLengths = options.dashLengths,
            dashOffset = options.dashOffset,
            arrowLength = options.arrowLength,
            arrowWidth = options.arrowWidth,
        )
    }
}

/** Primitive parameters retained while a shape is still editable as a live primitive. */
@Serializable
data class VectorPrimitiveMetadata(
    val kind: VectorShapeKind,
    val cornerRadii: ShapeCornerRadii = ShapeCornerRadii(),
    val polygonSides: Int = 5,
    val starInnerRatio: Float = 0.5f,
    val rotationDegrees: Float = 0f,
    val sourceBox: VectorSourceBox? = null,
) {
    fun normalized(): VectorPrimitiveMetadata = copy(
        cornerRadii = cornerRadii.normalized(),
        polygonSides = polygonSides.coerceIn(ShapeToolOptions.MIN_POLYGON_SIDES, ShapeToolOptions.MAX_POLYGON_SIDES),
        starInnerRatio = finiteOr(starInnerRatio, 0.5f).coerceIn(
            ShapeToolOptions.MIN_STAR_INNER_RATIO,
            ShapeToolOptions.MAX_STAR_INNER_RATIO,
        ),
        rotationDegrees = ((finiteOr(rotationDegrees, 0f) + 180f).mod(360f)) - 180f,
        sourceBox = sourceBox?.normalized(),
    )
}

/** Original shape bounds retained so rotated live primitives can round-trip without lossy bounds. */
@Serializable
data class VectorSourceBox(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
) {
    fun normalized(): VectorSourceBox? {
        if (!x.isFinite() || !y.isFinite() || !width.isFinite() || !height.isFinite()) return null
        if (width <= 0f || height <= 0f) return null
        return this
    }
}

/** Per-corner live radius values. Clamp each corner against the visible size before rendering. */
@Serializable
data class ShapeCornerRadii(
    val topLeft: Float = 0f,
    val topRight: Float = 0f,
    val bottomRight: Float = 0f,
    val bottomLeft: Float = 0f,
) {
    fun normalized(): ShapeCornerRadii = ShapeCornerRadii(
        topLeft = radius(topLeft),
        topRight = radius(topRight),
        bottomRight = radius(bottomRight),
        bottomLeft = radius(bottomLeft),
    )

    companion object {
        fun all(radius: Float) = ShapeCornerRadii(radius, radius, radius, radius).normalized()
    }
}

/** Tool-bar defaults for future vector shapes; normalize before handing values to a renderer. */
@Serializable
data class ShapeToolOptions(
    val kind: VectorShapeKind = VectorShapeKind.RECTANGLE,
    val fillEnabled: Boolean = true,
    val fillColor: RgbaColor = RgbaColor.Transparent,
    val strokeEnabled: Boolean = true,
    val strokeColor: RgbaColor = RgbaColor.Black,
    val strokeWidth: Float = 1f,
    val cornerRadii: ShapeCornerRadii = ShapeCornerRadii(),
    val polygonSides: Int = 5,
    val starInnerRatio: Float = 0.5f,
    val rotationDegrees: Float = 0f,
    val join: PathStrokeJoin = PathStrokeJoin.MITER,
    val miterLimit: Float = DEFAULT_MITER_LIMIT,
    val cap: PathStrokeCap = PathStrokeCap.BUTT,
    val alignment: PathStrokeAlignment = PathStrokeAlignment.CENTER,
    val dashLengths: List<Float> = emptyList(),
    val dashOffset: Float = 0f,
    val arrowAtStart: Boolean = false,
    val arrowAtEnd: Boolean = false,
    val arrowLength: Float = 4f,
    val arrowWidth: Float = 3f,
) {
    fun normalized(): ShapeToolOptions = copy(
        fillColor = fillColor.normalizedColor(),
        strokeColor = strokeColor.normalizedColor(),
        strokeWidth = finiteOr(strokeWidth, 1f).coerceIn(MIN_STROKE_WIDTH, MAX_STROKE_WIDTH),
        cornerRadii = cornerRadii.normalized(),
        polygonSides = polygonSides.coerceIn(MIN_POLYGON_SIDES, MAX_POLYGON_SIDES),
        starInnerRatio = finiteOr(starInnerRatio, 0.5f).coerceIn(MIN_STAR_INNER_RATIO, MAX_STAR_INNER_RATIO),
        rotationDegrees = ((finiteOr(rotationDegrees, 0f) + 180f).mod(360f)) - 180f,
        miterLimit = finiteOr(miterLimit, DEFAULT_MITER_LIMIT).coerceIn(MIN_MITER_LIMIT, MAX_MITER_LIMIT),
        dashLengths = dashLengths.asSequence().filter { it.isFinite() && it > 0f }.take(MAX_DASH_SEGMENTS).toList(),
        dashOffset = finiteOr(dashOffset, 0f),
        arrowLength = finiteOr(arrowLength, 4f).coerceIn(MIN_ARROW_SIZE, MAX_ARROW_SIZE),
        arrowWidth = finiteOr(arrowWidth, 3f).coerceIn(MIN_ARROW_SIZE, MAX_ARROW_SIZE),
    )

    companion object {
        const val MIN_STROKE_WIDTH = 0.1f
        const val MAX_STROKE_WIDTH = 10_000f
        const val DEFAULT_MITER_LIMIT = 4f
        const val MIN_MITER_LIMIT = 1f
        const val MAX_MITER_LIMIT = 100f
        const val MAX_CORNER_RADIUS = 10_000f
        const val MIN_ARROW_SIZE = 1f
        const val MAX_ARROW_SIZE = 32f
        const val MIN_POLYGON_SIDES = 3
        const val MAX_POLYGON_SIDES = 64
        const val MIN_STAR_INNER_RATIO = 0.05f
        const val MAX_STAR_INNER_RATIO = 0.95f
        const val MAX_DASH_SEGMENTS = 32
    }
}

/** Snapshot the current shape defaults into a path record style. */
fun ShapeToolOptions.toPathStyle(): VectorPathStyle = normalized().let { options ->
    VectorPathStyle(
        fillColor = options.fillColor.takeIf { options.fillEnabled },
        strokeColor = options.strokeColor.takeIf { options.strokeEnabled },
        strokeWidth = options.strokeWidth,
        join = options.join,
        miterLimit = options.miterLimit,
        cap = options.cap,
        alignment = options.alignment,
        dashLengths = options.dashLengths,
        dashOffset = options.dashOffset,
        arrowAtStart = options.arrowAtStart,
        arrowAtEnd = options.arrowAtEnd,
        arrowLength = options.arrowLength,
        arrowWidth = options.arrowWidth,
    )
}

/** Snapshot the active primitive controls for a future shape gesture. */
fun ShapeToolOptions.toPrimitiveMetadata(): VectorPrimitiveMetadata = normalized().let { options ->
    VectorPrimitiveMetadata(
        kind = options.kind,
        cornerRadii = options.cornerRadii,
        polygonSides = options.polygonSides,
        starInnerRatio = options.starInnerRatio,
        rotationDegrees = options.rotationDegrees,
    )
}

/** A cubic-editable anchor. Handles are relative vectors from [point] in document coordinates. */
@Serializable
data class PathAnchor(
    val id: PathAnchorId,
    val point: DocPoint,
    val incoming: DocPoint? = null,
    val outgoing: DocPoint? = null,
    val mode: PathAnchorMode = PathAnchorMode.CORNER,
)

/** One open or closed subpath; anchors are ordered from the first point to the last. */
@Serializable
data class VectorSubpath(
    val anchors: List<PathAnchor>,
    val closed: Boolean = false,
)

/**
 * Value-only path geometry for the pen and Paths panel foundation. It is not attached to a document,
 * history, or saved file until the vector-layer engine contract is integrated.
 */
@Serializable
data class VectorPathDraft(
    val id: VectorPathId,
    val name: String,
    val subpaths: List<VectorSubpath>,
    val fillRule: PathFillRule = PathFillRule.NON_ZERO,
    val style: VectorPathStyle = VectorPathStyle(),
    val primitive: VectorPrimitiveMetadata? = null,
)

@Serializable
enum class PathFillRule { NON_ZERO, EVEN_ODD }

private fun finiteOr(value: Float, fallback: Float): Float = if (value.isFinite()) value else fallback

private fun radius(value: Float) = finiteOr(value, 0f).coerceIn(0f, ShapeToolOptions.MAX_CORNER_RADIUS)

private fun RgbaColor.normalizedColor(): RgbaColor = RgbaColor(
    r = finiteOr(r, 0f).coerceIn(0f, 1f),
    g = finiteOr(g, 0f).coerceIn(0f, 1f),
    b = finiteOr(b, 0f).coerceIn(0f, 1f),
    a = finiteOr(a, 1f).coerceIn(0f, 1f),
)
