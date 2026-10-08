package app.croptop.core.model

import kotlinx.serialization.Serializable

/** A point in document pixel space (origin top-left, +y down). Sub-pixel precision. */
@Serializable
data class DocPoint(val x: Float, val y: Float) {
    operator fun plus(o: DocPoint) = DocPoint(x + o.x, y + o.y)

    operator fun minus(o: DocPoint) = DocPoint(x - o.x, y - o.y)

    companion object {
        val Zero = DocPoint(0f, 0f)
    }
}

/** Integer size in document pixels. */
@Serializable
data class DocSize(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "DocSize must be positive: ${width}x$height" }
    }

    val megapixels: Float get() = width.toLong() * height / 1_000_000f
}

/** Integer rectangle in document pixels; [right]/[bottom] are exclusive. */
@Serializable
data class DocRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    fun contains(p: DocPoint): Boolean = p.x >= left && p.x < right && p.y >= top && p.y < bottom

    fun intersect(o: DocRect): DocRect = DocRect(maxOf(left, o.left), maxOf(top, o.top), minOf(right, o.right), minOf(bottom, o.bottom))

    companion object {
        fun of(size: DocSize) = DocRect(0, 0, size.width, size.height)
    }
}
