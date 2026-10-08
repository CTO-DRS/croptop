package app.croptop.core.model

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round
import kotlinx.serialization.Serializable

/** A guide's line direction: a HORIZONTAL guide sits at a y position; a VERTICAL guide at an x position. */
enum class GuideAxis { HORIZONTAL, VERTICAL }

@Serializable
data class Guide(val axis: GuideAxis, val position: Float)

@Serializable
data class GridSettings(val spacing: Float = 100f, val subdivisions: Int = 4) {
    init {
        require(spacing > 0f) { "spacing must be > 0" }
        require(subdivisions in 1..16) { "subdivisions must be 1–16" }
    }
}

/** Where to snap. */
@Serializable
data class SnapTo(
    val guides: Boolean = true,
    val grid: Boolean = true,
    val documentBounds: Boolean = true,
    val layers: Boolean = true,
)

/**
 * Rulers, guides, grid and snapping for one document window. Guides live with the
 * document; the show/hide toggles are per view.
 */
@Serializable
data class ViewAids(
    val rulers: Boolean = false,
    val grid: Boolean = false,
    val showGuides: Boolean = true,
    val lockGuides: Boolean = false,
    val snap: Boolean = true,
    val snapTo: SnapTo = SnapTo(),
    val gridSettings: GridSettings = GridSettings(),
    val guides: List<Guide> = emptyList(),
) {
    fun addGuide(g: Guide) = copy(guides = guides + g)

    fun moveGuide(index: Int, position: Float) = copy(guides = guides.mapIndexed { i, g -> if (i == index) g.copy(position = position) else g })

    fun removeGuide(index: Int) = copy(guides = guides.filterIndexed { i, _ -> i != index })

    /** Guides within [tolerance] doc units of [p] on either axis; nearest first. */
    fun guideAt(p: DocPoint, tolerance: Float): Int? = guides.indices
        .map { i -> i to abs(guides[i].position - if (guides[i].axis == GuideAxis.VERTICAL) p.x else p.y) }
        .filter { it.second <= tolerance }
        .minByOrNull { it.second }
        ?.first

    /**
     * Candidate positions to snap to along one axis ([GuideAxis.VERTICAL] = x values).
     * [extra] adds e.g. other layers' edges and centers (smart guides).
     */
    fun snapTargets(axis: GuideAxis, doc: DocSize, near: Float, extra: List<SnapTarget> = emptyList()): List<SnapTarget> {
        if (!snap) return emptyList()
        val length = if (axis == GuideAxis.VERTICAL) doc.width.toFloat() else doc.height.toFloat()
        return buildList {
            if (snapTo.guides && showGuides) guides.filter { it.axis == axis }.forEach { add(SnapTarget(it.position, SnapKind.GUIDE)) }
            if (snapTo.documentBounds) {
                add(SnapTarget(0f, SnapKind.BOUNDS))
                add(SnapTarget(length / 2f, SnapKind.CENTER))
                add(SnapTarget(length, SnapKind.BOUNDS))
            }
            if (snapTo.grid && grid) {
                val s = gridSettings.spacing / gridSettings.subdivisions
                add(SnapTarget(floor(near / s) * s, SnapKind.GRID))
                add(SnapTarget(ceil(near / s) * s, SnapKind.GRID))
            }
            if (snapTo.layers) addAll(extra)
        }
    }

    companion object {
        /** PS toggles: View ▸ Rulers/Grid/Guides/Snap/Lock Guides. */
        val Default = ViewAids()
    }
}

enum class SnapKind { GUIDE, GRID, BOUNDS, CENTER, LAYER, SELECTION }

data class SnapTarget(val position: Float, val kind: SnapKind)

data class Snapped(val value: Float, val target: SnapTarget?)

object Snapping {
    /** Snap distance in view pixels (Photoshop uses ~8 screen px). */
    const val THRESHOLD_VIEW_PX = 8f

    /**
     * Snaps [value] to the nearest target within [threshold] (doc units). Guides win ties over grid lines
     * so a guide on a grid line reports as a guide.
     */
    fun snap(value: Float, targets: List<SnapTarget>, threshold: Float): Snapped {
        val best = targets
            .filter { abs(it.position - value) <= threshold }
            .minWithOrNull(compareBy<SnapTarget> { abs(it.position - value) }.thenBy { it.kind.ordinal })
        return if (best == null) Snapped(value, null) else Snapped(best.position, best)
    }

    /**
     * Snaps a moving span (e.g. a layer's left/center/right) — whichever of its [edges] is closest to a
     * target moves the whole span. Returns the delta to add and the target hit.
     */
    fun snapSpan(edges: List<Float>, targets: List<SnapTarget>, threshold: Float): Pair<Float, SnapTarget?> {
        var bestDelta = 0f
        var bestTarget: SnapTarget? = null
        var bestDist = Float.MAX_VALUE
        for (e in edges) {
            val s = snap(e, targets, threshold)
            val t = s.target ?: continue
            val d = abs(s.value - e)
            if (d < bestDist) {
                bestDist = d
                bestDelta = s.value - e
                bestTarget = t
            }
        }
        return bestDelta to bestTarget
    }
}

/** Ruler tick spacing: a 1–2–5 step in doc pixels so labelled ticks are at least [minViewPx] apart. */
object RulerScale {
    fun majorStep(zoom: Float, minViewPx: Float = 64f): Float {
        val raw = minViewPx / zoom
        val mag = 10f.pow(floor(log10(raw)))
        return listOf(1f, 2f, 5f, 10f).map { it * mag }.first { it >= raw - 1e-4f }
    }

    /** Minor ticks per major step. */
    fun minorCount(step: Float): Int {
        val lead = round(step / 10f.pow(floor(log10(step))))
        return if (lead == 2f) 4 else 5
    }

    /** Doc-space tick positions from [start] to [end] (inclusive) at [step]. */
    fun ticks(start: Float, end: Float, step: Float): List<Float> {
        val lo = ceil(minOf(start, end) / step).toInt()
        val hi = floor(maxOf(start, end) / step).toInt()
        if (hi - lo > 2000) return emptyList()
        return (lo..hi).map { it * step }
    }

    fun label(value: Float): String = if (abs(value - round(value)) < 1e-3f) round(value).toInt().toString() else "%.1f".format(value)
}

/** View ▸ New Guide Layout… (columns/rows with gutters inside optional margins). */
@Serializable
data class GuideLayout(
    val columns: Int = 0,
    val columnGutter: Float = 0f,
    val rows: Int = 0,
    val rowGutter: Float = 0f,
    val margin: Float = 0f,
    val clearExisting: Boolean = true,
) {
    fun guides(doc: DocSize): List<Guide> = buildList {
        fun split(count: Int, gutter: Float, length: Float, axis: GuideAxis) {
            if (count <= 0) return
            val start = margin
            val end = length - margin
            val cell = (end - start - gutter * (count - 1)) / count
            if (cell <= 0f) return
            var x = start
            repeat(count) { i ->
                add(Guide(axis, x))
                x += cell
                add(Guide(axis, x))
                if (i < count - 1) x += gutter
            }
        }
        split(columns, columnGutter, doc.width.toFloat(), GuideAxis.VERTICAL)
        split(rows, rowGutter, doc.height.toFloat(), GuideAxis.HORIZONTAL)
    }.distinct()

    fun applyTo(aids: ViewAids, doc: DocSize): ViewAids =
        aids.copy(guides = (if (clearExisting) emptyList() else aids.guides) + guides(doc), showGuides = true)
}
