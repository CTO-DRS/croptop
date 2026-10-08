// Runs a fixed-input-size network over an image of any size: tiles with overlap, edge padding for the
// partial tiles, feathered blending of the outputs, cooperative cancellation and progress. Used by denoise, upscale and inpaint.
package app.croptop.core.ai

import kotlin.coroutines.cancellation.CancellationException

/** What the network consumes and produces per tile. Tensors are NHWC: `[1, tileSize, tileSize, channels]`. */
data class TiledSpec(
    val tileSize: Int,
    val overlap: Int,
    val inputChannels: Int,
    val outputChannels: Int = inputChannels,
    /** Output pixels per input pixel along each axis (1 for denoise/inpaint, 2 or 4 for upscalers). */
    val scale: Int = 1,
) {
    init {
        require(tileSize > 0) { "tileSize must be positive" }
        require(overlap in 0 until tileSize) { "overlap must be in [0, tileSize)" }
        require(inputChannels > 0 && outputChannels > 0) { "channel counts must be positive" }
        require(scale in 1..8) { "scale must be 1..8" }
    }
}

object TiledInference {
    /**
     * [image] is interleaved row-major, `width * height * spec.inputChannels` floats. Returns `width*scale × height*scale ×
     * outputChannels` floats. [isCancelled] is polled before every tile; when it returns true the run stops with a
     * [CancellationException]. [progress] receives (tiles done, tiles total) after each tile.
     */
    fun run(
        image: FloatArray,
        width: Int,
        height: Int,
        spec: TiledSpec,
        session: ModelSession,
        isCancelled: () -> Boolean = { false },
        progress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): FloatArray {
        require(width > 0 && height > 0) { "image must not be empty" }
        require(image.size == width * height * spec.inputChannels) { "image size does not match ${width}x$height x${spec.inputChannels}" }
        val tiles = TilePlanner.plan(width, height, spec.tileSize, spec.overlap)
        val blender = TileBlender(width * spec.scale, height * spec.scale, spec.outputChannels, spec.overlap * spec.scale)
        val inputShape = intArrayOf(1, spec.tileSize, spec.tileSize, spec.inputChannels)
        val input = FloatArray(spec.tileSize * spec.tileSize * spec.inputChannels)
        tiles.forEachIndexed { index, tile ->
            if (isCancelled()) throw CancellationException("inference cancelled after $index of ${tiles.size} tiles")
            fill(input, image, width, tile, spec)
            val output = session.run(input, inputShape)
            val outSide = spec.tileSize * spec.scale
            require(output.size == outSide * outSide * spec.outputChannels) {
                "model returned ${output.size} values, expected ${outSide * outSide * spec.outputChannels}"
            }
            val outTile = Tile(tile.x * spec.scale, tile.y * spec.scale, tile.width * spec.scale, tile.height * spec.scale)
            blender.add(outTile, crop(output, outSide, outTile, spec.outputChannels))
            progress(index + 1, tiles.size)
        }
        return blender.result()
    }

    /** Copies [tile] into the model input, replicating the last row/column when the tile is smaller than the network's size. */
    private fun fill(input: FloatArray, image: FloatArray, width: Int, tile: Tile, spec: TiledSpec) {
        val c = spec.inputChannels
        for (y in 0 until spec.tileSize) {
            val sy = tile.y + minOf(y, tile.height - 1)
            for (x in 0 until spec.tileSize) {
                val sx = tile.x + minOf(x, tile.width - 1)
                System.arraycopy(image, (sy * width + sx) * c, input, (y * spec.tileSize + x) * c, c)
            }
        }
    }

    private fun crop(output: FloatArray, side: Int, outTile: Tile, channels: Int): FloatArray {
        if (outTile.width == side && outTile.height == side) return output
        val out = FloatArray(outTile.width * outTile.height * channels)
        for (y in 0 until outTile.height) {
            System.arraycopy(output, y * side * channels, out, y * outTile.width * channels, outTile.width * channels)
        }
        return out
    }
}
