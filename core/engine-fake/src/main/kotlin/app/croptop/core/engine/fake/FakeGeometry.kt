package app.croptop.core.engine.fake

import app.croptop.core.model.DocRect
import app.croptop.core.model.DocSize
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Bounds math for canvas-level operations in the fake (no pixels: only layer/selection boxes move). */
internal object FakeGeometry {
    fun union(a: DocRect?, b: DocRect?): DocRect? = when {
        a == null -> b
        b == null -> a
        else -> DocRect(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom))
    }

    fun scale(r: DocRect, sx: Double, sy: Double) =
        DocRect((r.left * sx).roundToInt(), (r.top * sy).roundToInt(), (r.right * sx).roundToInt(), (r.bottom * sy).roundToInt())

    fun flip(r: DocRect, size: DocSize, horizontal: Boolean) = if (horizontal) {
        DocRect(size.width - r.right, r.top, size.width - r.left, r.bottom)
    } else {
        DocRect(r.left, size.height - r.bottom, r.right, size.height - r.top)
    }

    /** Canvas size after rotating by [degrees] (quarter turns swap sides; other angles grow to fit). */
    fun rotatedSize(size: DocSize, degrees: Float): DocSize {
        val q = quarterTurns(degrees)
        if (q != null) return if (q % 2 == 0) size else DocSize(size.height, size.width)
        val r = Math.toRadians(degrees.toDouble())
        val w = abs(size.width * cos(r)) + abs(size.height * sin(r))
        val h = abs(size.width * sin(r)) + abs(size.height * cos(r))
        return DocSize(w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1))
    }

    /** Box of [r] after rotating the canvas [size] by [degrees] about its center into [out]. */
    fun rotate(r: DocRect, size: DocSize, degrees: Float, out: DocSize): DocRect {
        val rad = Math.toRadians(degrees.toDouble())
        val c = cos(rad)
        val s = sin(rad)
        val cx = size.width / 2.0
        val cy = size.height / 2.0
        val corners = listOf(r.left to r.top, r.right to r.top, r.left to r.bottom, r.right to r.bottom).map { (x, y) ->
            val dx = x - cx
            val dy = y - cy
            (dx * c - dy * s + out.width / 2.0) to (dx * s + dy * c + out.height / 2.0)
        }
        return DocRect(
            corners.minOf { it.first }.roundToInt(),
            corners.minOf { it.second }.roundToInt(),
            corners.maxOf { it.first }.roundToInt(),
            corners.maxOf { it.second }.roundToInt(),
        )
    }

    fun quarterTurns(degrees: Float): Int? {
        val d = ((degrees % 360f) + 360f) % 360f
        val q = (d / 90f).roundToInt()
        return if (abs(d - q * 90f) < 0.001f) q % 4 else null
    }
}
