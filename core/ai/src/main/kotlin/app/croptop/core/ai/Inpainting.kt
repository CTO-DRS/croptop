// AI Remove: a LaMa-class network fills the selected area from its surroundings. The network sees a
// 512² window around the selection (at the picture's own resolution whenever the object is small enough), and only the filled
// area, softened at its edge, is written back, so everything outside the selection stays bit-exact.
package app.croptop.core.ai

import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocRect
import app.croptop.core.model.DocSize
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** What [Inpainter] returns: [rgb] is `512 × 512 × 3` in 0–1 and [coverage] says how much of each pixel replaces the original. */
class InpaintResult(val rgb: FloatArray, val coverage: ByteArray)

class Inpainter(private val session: ModelSession) {
    /** [rgb] `512 × 512 × 3` in 0–1; [fill] `512 × 512` with 1 where content must be made up. */
    fun inpaint(rgb: FloatArray, fill: FloatArray): InpaintResult {
        val size = SIZE
        require(rgb.size == size * size * 3 && fill.size == size * size) { "the inpainting window is ${size}x$size" }
        val out = session.runAll(listOf(rgb, fill), listOf(intArrayOf(1, size, size, 3), intArrayOf(1, size, size, 1)))
        require(out.size == size * size * 3) { "model returned ${out.size} values, expected ${size * size * 3}" }
        for (i in out.indices) out[i] = out[i].coerceIn(0f, 1f)
        return InpaintResult(out, InpaintWindow.feather(fill))
    }

    companion object {
        const val SIZE = 512
    }
}

object InpaintWindow {
    /** Grows the filled area so the edge of the object and its halo are made up too, not copied from the original. */
    const val GROW_PX = 6
    const val FEATHER_PX = 3

    /**
     * The window of the picture the network sees: a square about 1.5× the selection (the network's own 512 when the selection
     * is small, so nothing is resampled), centred on the selection and kept inside the picture.
     */
    fun window(selection: DocRect, picture: DocSize): DocRect {
        val longest = max(selection.width, selection.height)
        val side = if (longest * 3 <= 512 * 2) Inpainter.SIZE else ceil(longest * 1.5).toInt()
        val w = min(side, picture.width)
        val h = min(side, picture.height)
        val cx = (selection.left + selection.right) / 2
        val cy = (selection.top + selection.bottom) / 2
        val left = (cx - w / 2).coerceIn(0, picture.width - w)
        val top = (cy - h / 2).coerceIn(0, picture.height - h)
        return DocRect(left, top, left + w, top + h)
    }

    /** Bilinear resize of a one-channel image (bytes 0–255) to `outW × outH`, values 0–1. */
    fun resizeCoverage(src: ByteArray, w: Int, h: Int, outW: Int, outH: Int): FloatArray {
        require(src.size == w * h)
        val out = FloatArray(outW * outH)
        for (y in 0 until outH) {
            val fy = ((y + 0.5f) * h / outH - 0.5f).coerceIn(0f, (h - 1).toFloat())
            val y0 = fy.toInt()
            val y1 = min(y0 + 1, h - 1)
            val ty = fy - y0
            for (x in 0 until outW) {
                val fx = ((x + 0.5f) * w / outW - 0.5f).coerceIn(0f, (w - 1).toFloat())
                val x0 = fx.toInt()
                val x1 = min(x0 + 1, w - 1)
                val tx = fx - x0
                fun at(xx: Int, yy: Int) = (src[yy * w + xx].toInt() and 0xFF) / 255f
                out[y * outW + x] = (at(x0, y0) * (1 - tx) + at(x1, y0) * tx) * (1 - ty) + (at(x0, y1) * (1 - tx) + at(x1, y1) * tx) * ty
            }
        }
        return out
    }

    /** The network's fill mask: the selection (≥ half covered) grown by [GROW_PX]; 1 = make up. */
    fun fillMask(coverage: FloatArray, size: Int): FloatArray {
        val binary = FloatArray(coverage.size) { if (coverage[it] >= 0.5f) 1f else 0f }
        return boxMax(binary, size, GROW_PX)
    }

    /** Fill mask softened at its edge, as the 0–255 weight with which the result replaces the original. */
    fun feather(fill: FloatArray): ByteArray {
        val size = Inpainter.SIZE
        val soft = boxBlur(fill, size, FEATHER_PX)
        return ByteArray(soft.size) { i -> if (fill[i] >= 0.5f) 255.toByte() else (soft[i] * 255f + 0.5f).toInt().coerceIn(0, 255).toByte() }
    }

    private fun boxMax(src: FloatArray, size: Int, r: Int): FloatArray {
        val tmp = FloatArray(src.size)
        for (y in 0 until size) for (x in 0 until size) {
            var m = 0f
            for (k in max(0, x - r)..min(size - 1, x + r)) m = max(m, src[y * size + k])
            tmp[y * size + x] = m
        }
        val out = FloatArray(src.size)
        for (y in 0 until size) for (x in 0 until size) {
            var m = 0f
            for (k in max(0, y - r)..min(size - 1, y + r)) m = max(m, tmp[k * size + x])
            out[y * size + x] = m
        }
        return out
    }

    private fun boxBlur(src: FloatArray, size: Int, r: Int): FloatArray {
        val tmp = FloatArray(src.size)
        for (y in 0 until size) for (x in 0 until size) {
            var sum = 0f
            var n = 0
            for (k in max(0, x - r)..min(size - 1, x + r)) { sum += src[y * size + k]; n++ }
            tmp[y * size + x] = sum / n
        }
        val out = FloatArray(src.size)
        for (y in 0 until size) for (x in 0 until size) {
            var sum = 0f
            var n = 0
            for (k in max(0, y - r)..min(size - 1, y + r)) { sum += tmp[k * size + x]; n++ }
            out[y * size + x] = sum / n
        }
        return out
    }
}

/** Coverage of a brush stroke (a polyline with a round brush) over a window of the picture, for AI Remove's brush. */
object StrokeCoverage {
    /**
     * `window.width × window.height` bytes: 255 where a pixel centre is within [radius] of the polyline through [points] (document
     * pixels), softened over one pixel at the rim. A single point is a dab.
     */
    fun rasterize(points: List<DocPoint>, radius: Float, window: DocRect): ByteArray {
        require(points.isNotEmpty() && radius > 0f) { "a stroke needs a point and a radius" }
        val out = ByteArray(window.width * window.height)
        val segments = if (points.size == 1) listOf(points[0] to points[0]) else points.zipWithNext()
        val reach = radius + 1f
        for ((a, b) in segments) {
            val x0 = max(window.left, kotlin.math.floor(min(a.x, b.x) - reach).toInt())
            val x1 = min(window.right - 1, kotlin.math.ceil(max(a.x, b.x) + reach).toInt())
            val y0 = max(window.top, kotlin.math.floor(min(a.y, b.y) - reach).toInt())
            val y1 = min(window.bottom - 1, kotlin.math.ceil(max(a.y, b.y) + reach).toInt())
            val dx = b.x - a.x
            val dy = b.y - a.y
            val len2 = dx * dx + dy * dy
            for (y in y0..y1) {
                for (x in x0..x1) {
                    val px = x + 0.5f - a.x
                    val py = y + 0.5f - a.y
                    val t = if (len2 > 0f) ((px * dx + py * dy) / len2).coerceIn(0f, 1f) else 0f
                    val ex = px - t * dx
                    val ey = py - t * dy
                    val d = kotlin.math.sqrt(ex * ex + ey * ey)
                    val v = ((radius - d) + 0.5f).coerceIn(0f, 1f)
                    if (v > 0f) {
                        val i = (y - window.top) * window.width + (x - window.left)
                        val cur = out[i].toInt() and 0xFF
                        out[i] = max(cur, (v * 255f + 0.5f).toInt()).toByte()
                    }
                }
            }
        }
        return out
    }
}
