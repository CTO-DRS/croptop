// Tiled inference for large images: tile layout with overlap, and feathered blending of tile outputs.
package app.croptop.core.ai

import kotlin.math.PI
import kotlin.math.cos

/** A square-ish region of the source image to run through the network, in image pixels. */
data class Tile(val x: Int, val y: Int, val width: Int, val height: Int) {
    val right: Int get() = x + width
    val bottom: Int get() = y + height
}

object TilePlanner {
    /**
     * Covers a [width]×[height] image with [tile]-pixel tiles that overlap by [overlap] pixels. The last tile in each direction is
     * shifted back to end at the image edge, so every tile has full size when the image is at least that big; a smaller image gets
     * one tile of its own size. Tiles are ordered row by row.
     */
    fun plan(width: Int, height: Int, tile: Int, overlap: Int): List<Tile> {
        require(width > 0 && height > 0) { "image must not be empty" }
        require(tile > 0) { "tile must be positive" }
        require(overlap in 0 until tile) { "overlap must be in [0, tile)" }
        val xs = starts(width, tile, overlap)
        val ys = starts(height, tile, overlap)
        val w = minOf(tile, width)
        val h = minOf(tile, height)
        return ys.flatMap { y -> xs.map { x -> Tile(x, y, w, h) } }
    }

    private fun starts(length: Int, tile: Int, overlap: Int): List<Int> {
        if (length <= tile) return listOf(0)
        val stride = tile - overlap
        val out = mutableListOf<Int>()
        var at = 0
        while (at + tile < length) {
            out += at
            at += stride
        }
        out += length - tile
        return out.distinct()
    }
}

/**
 * Accumulates tile outputs into one image with feathered seams: inside a tile, weight ramps from ~0 to 1 across each overlap band that
 * borders another tile, and stays 1 along the outer image edge, so overlapping predictions cross-fade and the weights sum to a constant.
 * [channels] values per pixel, interleaved.
 */
class TileBlender(private val width: Int, private val height: Int, private val channels: Int, private val overlap: Int) {
    private val sum = FloatArray(width * height * channels)
    private val weight = FloatArray(width * height)

    init {
        require(width > 0 && height > 0 && channels > 0) { "invalid blender size" }
        require(overlap >= 0) { "overlap must not be negative" }
    }

    /** [data] holds `tile.width * tile.height * channels` values, row-major and interleaved. */
    fun add(tile: Tile, data: FloatArray) {
        require(tile.x >= 0 && tile.y >= 0 && tile.right <= width && tile.bottom <= height) { "tile lies outside the image" }
        require(data.size == tile.width * tile.height * channels) { "tile data has the wrong size" }
        val leftFeather = tile.x > 0
        val rightFeather = tile.right < width
        val topFeather = tile.y > 0
        val bottomFeather = tile.bottom < height
        for (ly in 0 until tile.height) {
            val wy = ramp(ly, tile.height, topFeather, bottomFeather)
            val row = (tile.y + ly) * width
            for (lx in 0 until tile.width) {
                val w = ramp(lx, tile.width, leftFeather, rightFeather) * wy
                val pixel = row + tile.x + lx
                weight[pixel] += w
                val from = (ly * tile.width + lx) * channels
                val to = pixel * channels
                for (c in 0 until channels) sum[to + c] += data[from + c] * w
            }
        }
    }

    /** The blended image; pixels no tile covered are 0. */
    fun result(): FloatArray {
        val out = FloatArray(sum.size)
        for (pixel in 0 until width * height) {
            val w = weight[pixel]
            if (w <= 0f) continue
            for (c in 0 until channels) out[pixel * channels + c] = sum[pixel * channels + c] / w
        }
        return out
    }

    /** 0 → ~0 … 1 over the overlap band at a feathered end; a half-sample offset keeps the outermost pixel above zero. */
    private fun ramp(i: Int, n: Int, featherStart: Boolean, featherEnd: Boolean): Float {
        if (overlap == 0) return 1f
        var w = 1f
        if (featherStart && i < overlap) w = minOf(w, smooth((i + 0.5f) / overlap))
        val fromEnd = n - 1 - i
        if (featherEnd && fromEnd < overlap) w = minOf(w, smooth((fromEnd + 0.5f) / overlap))
        return w
    }

    private fun smooth(t: Float): Float = (0.5f - 0.5f * cos(PI.toFloat() * t.coerceIn(0f, 1f)))
}
