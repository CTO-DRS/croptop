package app.croptop.core.model

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.serialization.Serializable

/**
 * Float rectangle in document pixels: selections, crop boxes, transform bounds.
 * Coordinates must be finite and ordered; use [of] to build one from two arbitrary corners.
 */
@Serializable
data class DocRectF(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    init {
        require(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite()) { "DocRectF must be finite" }
        require(left <= right && top <= bottom) { "DocRectF must be ordered: $left,$top,$right,$bottom" }
    }

    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val isEmpty: Boolean get() = width <= 0f || height <= 0f
    val center: DocPoint get() = DocPoint((left + right) / 2f, (top + bottom) / 2f)

    fun contains(p: DocPoint): Boolean = p.x >= left && p.x < right && p.y >= top && p.y < bottom

    fun translate(dx: Float, dy: Float) = DocRectF(left + dx, top + dy, right + dx, bottom + dy)

    /** Smallest integer rectangle that covers this one. */
    fun toDocRect(): DocRect = DocRect(
        kotlin.math.floor(left).toInt(),
        kotlin.math.floor(top).toInt(),
        kotlin.math.ceil(right).toInt(),
        kotlin.math.ceil(bottom).toInt(),
    )

    companion object {
        fun of(a: DocPoint, b: DocPoint) = DocRectF(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y))

        fun of(r: DocRect) = DocRectF(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat())
    }
}

fun DocRect.translate(dx: Int, dy: Int) = DocRect(left + dx, top + dy, right + dx, bottom + dy)

/**
 * Immutable 3×3 affine/projective matrix, row-major: maps document points
 * `(x, y, 1)`. Nine finite values.
 */
@Serializable
data class Matrix3(val values: List<Float>) {
    init {
        require(values.size == 9) { "Matrix3 needs 9 values" }
        require(values.all { it.isFinite() }) { "Matrix3 values must be finite" }
    }

    operator fun get(row: Int, col: Int): Float = values[row * 3 + col]

    /** this × other (apply [other] first, then this). */
    operator fun times(o: Matrix3): Matrix3 = Matrix3(List(9) { i -> (0 until 3).sumOf { k -> (this[i / 3, k] * o[k, i % 3]).toDouble() }.toFloat() })

    fun map(p: DocPoint): DocPoint {
        val w = this[2, 0] * p.x + this[2, 1] * p.y + this[2, 2]
        val x = this[0, 0] * p.x + this[0, 1] * p.y + this[0, 2]
        val y = this[1, 0] * p.x + this[1, 1] * p.y + this[1, 2]
        return if (w == 0f || w == 1f) DocPoint(x, y) else DocPoint(x / w, y / w)
    }

    /** Pure translation (no rotation/scale/skew/perspective). */
    val isTranslation: Boolean get() = this[0, 0] == 1f && this[0, 1] == 0f && this[1, 0] == 0f && this[1, 1] == 1f && this[2, 0] == 0f &&
        this[2, 1] == 0f &&
        this[2, 2] == 1f

    val translation: DocPoint get() = DocPoint(this[0, 2], this[1, 2])

    companion object {
        val Identity = Matrix3(listOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f))

        fun translate(dx: Float, dy: Float) = Matrix3(listOf(1f, 0f, dx, 0f, 1f, dy, 0f, 0f, 1f))

        fun scale(sx: Float, sy: Float, about: DocPoint = DocPoint.Zero) =
            translate(about.x, about.y) * Matrix3(listOf(sx, 0f, 0f, 0f, sy, 0f, 0f, 0f, 1f)) * translate(-about.x, -about.y)

        fun rotate(degrees: Float, about: DocPoint = DocPoint.Zero): Matrix3 {
            val r = Math.toRadians(degrees.toDouble())
            val c = cos(r).toFloat()
            val s = sin(r).toFloat()
            return translate(about.x, about.y) * Matrix3(listOf(c, -s, 0f, s, c, 0f, 0f, 0f, 1f)) * translate(-about.x, -about.y)
        }
    }
}

/** How a new selection combines with the current one (Shift / Alt / Shift+Alt). */
@Serializable
enum class SelectionOp { REPLACE, ADD, SUBTRACT, INTERSECT }

/** A selection shape in document pixels. */
@Serializable
sealed interface SelectionShape {
    /** Bounding box of the shape. */
    val bounds: DocRectF

    @Serializable
    data class Rect(val rect: DocRectF) : SelectionShape {
        override val bounds: DocRectF get() = rect
    }

    @Serializable
    data class Ellipse(val rect: DocRectF) : SelectionShape {
        override val bounds: DocRectF get() = rect
    }

    /** Closed polygon (lasso, polygonal lasso): at least three finite points. */
    @Serializable
    data class Polygon(val points: List<DocPoint>) : SelectionShape {
        init {
            require(points.size >= 3) { "A polygon selection needs at least 3 points" }
            require(points.all { it.x.isFinite() && it.y.isFinite() }) { "Polygon points must be finite" }
        }

        override val bounds: DocRectF get() = DocRectF(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })
    }
}

/** Select ▸ Modify. Radii/widths in document pixels. */
@Serializable
enum class SelectionModify(val label: String, val maxPx: Float) {
    EXPAND("Expand", 500f),
    CONTRACT("Contract", 500f),
    BORDER("Border", 200f),
    SMOOTH("Smooth", 500f),
    FEATHER("Feather", 1000f),
}

/** What new canvas area is filled with (Canvas Size, rotate by arbitrary angles). */
@Serializable
sealed interface CanvasFill {
    @Serializable
    data object Transparent : CanvasFill

    @Serializable
    data class Color(val color: RgbaColor) : CanvasFill
}

/**
 * Pure selection geometry used by the fake engine and by the UI to preview a combine before the
 * engine confirms it: the resulting bounds of [op] applied to [current] and [shape] (null = none).
 * Masks live in the engine; bounds are what [SelectionInfo.Active] reports.
 */
object SelectionBounds {
    fun combine(current: DocRect?, shape: DocRect, op: SelectionOp, canvas: DocSize): DocRect? {
        val c = DocRect.of(canvas)
        val s = shape.intersect(c).takeUnless { it.isEmpty }
        return when (op) {
            SelectionOp.REPLACE -> s

            SelectionOp.ADD -> when {
                current == null -> s
                s == null -> current
                else -> DocRect(min(current.left, s.left), min(current.top, s.top), max(current.right, s.right), max(current.bottom, s.bottom))
            }

            // Bounds can't shrink reliably for a subtraction without the mask; keep the current box
            // unless the shape covers it completely.
            SelectionOp.SUBTRACT -> when {
                current == null -> null
                s != null && s.left <= current.left && s.top <= current.top && s.right >= current.right && s.bottom >= current.bottom -> null
                else -> current
            }

            SelectionOp.INTERSECT -> current?.let { cur -> s?.intersect(cur)?.takeUnless { it.isEmpty } }
        }
    }

    fun modify(current: DocRect, kind: SelectionModify, px: Float, canvas: DocSize): DocRect? {
        val d = px.roundToInt().coerceAtLeast(0)
        val r = when (kind) {
            SelectionModify.EXPAND, SelectionModify.BORDER -> DocRect(current.left - d, current.top - d, current.right + d, current.bottom + d)
            SelectionModify.CONTRACT -> DocRect(current.left + d, current.top + d, current.right - d, current.bottom - d)
            SelectionModify.SMOOTH, SelectionModify.FEATHER -> current
        }
        return r.intersect(DocRect.of(canvas)).takeUnless { it.isEmpty }
    }
}

/** Edit ▸ Fill contents (the UI resolves Foreground/Background to a color). */
@Serializable
sealed interface FillSource {
    @Serializable
    data class Solid(val color: RgbaColor) : FillSource

    /** Content-Aware (Pro): fills from the surrounding image. */
    @Serializable
    data class ContentAware(val options: ContentAwareOptions = ContentAwareOptions()) : FillSource
}

/** How far Content-Aware Fill may rotate patches it copies (Photoshop's Rotation Adaptation). */
@Serializable
enum class ContentAwareRotation { NONE, LOW, MEDIUM, HIGH, FULL }

/** A brush stroke on the sampling area: [add] marks pixels patches may come from, otherwise it excludes them. */
@Serializable
data class SamplingStroke(val points: List<DocPoint>, val radius: Float, val add: Boolean = true) {
    init {
        require(radius.isFinite() && radius in 0.5f..4096f) { "sampling brush radius must be 0.5..4096" }
        require(points.isNotEmpty() && points.size <= MAX_POINTS) { "a sampling stroke needs 1..$MAX_POINTS points" }
        require(points.all { it.x.isFinite() && it.y.isFinite() }) { "sampling points must be finite" }
    }

    companion object {
        const val MAX_POINTS = 4096
        const val MAX_STROKES = 64
    }
}

/**
 * Content-Aware Fill settings. With [samplingOnlyPainted] false, patches come from everywhere outside the hole
 * except areas excluded by subtract strokes; with true, only from areas painted by add strokes.
 */
@Serializable
data class ContentAwareOptions(
    val rotation: ContentAwareRotation = ContentAwareRotation.NONE,
    val mirror: Boolean = false,
    val colorAdaptation: Boolean = true,
    val samplingOnlyPainted: Boolean = false,
    val strokes: List<SamplingStroke> = emptyList(),
    /** Non-null writes the result to a new layer with this id above the active layer, leaving the original untouched. */
    val newLayerId: LayerId? = null,
) {
    init {
        require(strokes.size <= SamplingStroke.MAX_STROKES) { "at most ${SamplingStroke.MAX_STROKES} sampling strokes" }
        require(!samplingOnlyPainted || strokes.any { it.add }) { "painting-only sampling needs at least one added area" }
    }
}

/** Geometry used by the Gradient tool. */
@Serializable
enum class GradientShape { LINEAR, RADIAL, ANGLE, REFLECTED, DIAMOND }

/** An adjustment or smart-filter entry: its spec, parameters, and optional non-destructive filter state. */
@Serializable
data class AdjustmentInfo(
    val specId: String,
    val values: Map<String, ParamValue> = emptyMap(),
    val enabled: Boolean = true,
    val blendMode: BlendMode = BlendMode.NORMAL,
    val opacity: Float = 1f,
) {
    init {
        require(opacity.isFinite() && opacity in 0f..1f) { "adjustment opacity must be between 0 and 1" }
    }
}

/**
 * Edit ▸ Transform ▸ Warp: a [columns] × [rows] lattice laid evenly over the whole canvas (identity: node (i, j) at
 * x = j·(width−1)/(columns−1), y = i·(height−1)/(rows−1)); [nodes] holds where each point was dragged to, row-major, in document pixels.
 */
@Serializable
data class WarpMesh(val columns: Int, val rows: Int, val nodes: List<DocPoint>) {
    init {
        require(columns in 2..64 && rows in 2..64) { "a warp mesh needs 2-64 columns and rows" }
        require(nodes.size == columns * rows) { "expected ${columns * rows} warp nodes, got ${nodes.size}" }
        require(nodes.all { it.x.isFinite() && it.y.isFinite() }) { "warp nodes must be finite" }
    }

    /** True when every node is at (or within [tolerance] px of) its undeformed position on a [width] × [height] canvas. */
    fun isIdentity(width: Int, height: Int, tolerance: Float = 0.01f): Boolean {
        val base = identity(width, height, columns, rows)
        return nodes.indices.all { i ->
            kotlin.math.abs(nodes[i].x - base.nodes[i].x) <= tolerance && kotlin.math.abs(nodes[i].y - base.nodes[i].y) <= tolerance
        }
    }

    companion object {
        fun identity(width: Int, height: Int, columns: Int = 4, rows: Int = 4): WarpMesh = WarpMesh(
            columns,
            rows,
            List(columns * rows) { index ->
                val row = index / columns
                val column = index % columns
                DocPoint(column * (width - 1f) / (columns - 1), row * (height - 1f) / (rows - 1))
            },
        )
    }
}
