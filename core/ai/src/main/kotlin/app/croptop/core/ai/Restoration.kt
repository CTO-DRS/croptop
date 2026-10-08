// AI Denoise and Super Resolution. Both networks take a fixed square tile, so a picture is walked in
// blocks: each block is run with some context around it and only its centre is kept, so tile seams never reach the result.
package app.croptop.core.ai

import kotlin.math.min

/**
 * Adapts a network whose tensors are channels-first (`[1, C, H, W]`) to the NHWC calls the rest of the code makes. [outputChannels]
 * tells how to read the flat output back.
 */
class ChannelsFirstSession(
    private val inner: ModelSession,
    private val inputChannelsFirst: Boolean,
    private val outputChannelsFirst: Boolean,
    private val outputChannels: Int = 3,
) : ModelSession by inner {
    override fun run(input: FloatArray, inputShape: IntArray): FloatArray {
        require(inputShape.size == 4) { "expected an NHWC shape" }
        val (h, w, c) = Triple(inputShape[1], inputShape[2], inputShape[3])
        val out = if (inputChannelsFirst) {
            val chw = FloatArray(input.size)
            for (y in 0 until h) for (x in 0 until w) for (k in 0 until c) chw[(k * h + y) * w + x] = input[(y * w + x) * c + k]
            inner.run(chw, intArrayOf(1, c, h, w))
        } else {
            inner.run(input, inputShape)
        }
        if (!outputChannelsFirst) return out
        val pixels = out.size / outputChannels
        val nhwc = FloatArray(out.size)
        for (p in 0 until pixels) for (k in 0 until outputChannels) nhwc[p * outputChannels + k] = out[k * pixels + p]
        return nhwc
    }
}

object Restoration {
    /** Straight RGBA bytes to interleaved RGB floats in 0–1. */
    fun rgbFloats(rgba: ByteArray, pixels: Int): FloatArray {
        val out = FloatArray(pixels * 3)
        for (p in 0 until pixels) for (c in 0 until 3) out[p * 3 + c] = (rgba[p * 4 + c].toInt() and 0xFF) / 255f
        return out
    }

    fun toByte(v: Float): Byte = (v * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
}

/** Real-image denoising with a NAFNet-class network (`[1,3,256,256]` in and out, 0–1). */
class Denoiser(private val session: ModelSession) {
    /** RGB floats (0–1) of the same size as [rgba] (`width × height × 4` bytes). */
    fun denoise(rgba: ByteArray, width: Int, height: Int, isCancelled: () -> Boolean = { false }): FloatArray =
        TiledInference.run(
            Restoration.rgbFloats(rgba, width * height), width, height,
            TiledSpec(tileSize = TILE, overlap = OVERLAP, inputChannels = 3), session, isCancelled,
        )

    companion object {
        const val TILE = 256
        const val OVERLAP = 32
    }
}

/** ×4 super resolution with a Real-ESRGAN-general-x4v3 class network (`[1,128,128,3]` in, `[1,3,512,512]` out, 0–1). */
class Upscaler(private val session: ModelSession) {
    object Alpha {
        /**
         * The block's alpha scaled by [factor] (bilinear, pixel centres aligned), as bytes `blockW*factor × blockH*factor`. The
         * network sees only colour, so transparency is carried over from the source instead of becoming opaque.
         */
        fun alphaBlock(rgba: ByteArray, width: Int, height: Int, blockX: Int, blockY: Int, blockW: Int, blockH: Int, factor: Int): ByteArray {
            val outW = blockW * factor
            val outH = blockH * factor
            val out = ByteArray(outW * outH)
            fun a(x: Int, y: Int) = (rgba[(y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)) * 4 + 3].toInt() and 0xFF).toFloat()
            for (y in 0 until outH) {
                val fy = blockY + (y + 0.5f) / factor - 0.5f
                val y0 = kotlin.math.floor(fy).toInt()
                val ty = fy - y0
                for (x in 0 until outW) {
                    val fx = blockX + (x + 0.5f) / factor - 0.5f
                    val x0 = kotlin.math.floor(fx).toInt()
                    val tx = fx - x0
                    val v = (a(x0, y0) * (1 - tx) + a(x0 + 1, y0) * tx) * (1 - ty) + (a(x0, y0 + 1) * (1 - tx) + a(x0 + 1, y0 + 1) * tx) * ty
                    out[y * outW + x] = (v + 0.5f).toInt().coerceIn(0, 255).toByte()
                }
            }
            return out
        }
    }

    /**
     * Upscales the block (`blockW × blockH` at [blockX], [blockY]) of a [width] × [height] source ([rgba], straight RGBA bytes) by
     * [factor] (2 or 4); returns RGB floats of `blockW*factor × blockH*factor`. A margin of context around the block is run and dropped.
     */
    fun upscaleBlock(rgba: ByteArray, width: Int, height: Int, blockX: Int, blockY: Int, blockW: Int, blockH: Int, factor: Int): FloatArray {
        require(factor == 2 || factor == 4) { "the factor is 2 or 4" }
        require(blockW in 1..BLOCK && blockH in 1..BLOCK && blockX >= 0 && blockY >= 0 && blockX + blockW <= width && blockY + blockH <= height)
        val winW = min(TILE, width)
        val winH = min(TILE, height)
        val left = (blockX - MARGIN).coerceIn(0, width - winW)
        val top = (blockY - MARGIN).coerceIn(0, height - winH)
        val input = FloatArray(TILE * TILE * 3)
        for (y in 0 until TILE) {
            val sy = top + min(y, winH - 1)
            for (x in 0 until TILE) {
                val sx = left + min(x, winW - 1)
                for (c in 0 until 3) input[(y * TILE + x) * 3 + c] = (rgba[(sy * width + sx) * 4 + c].toInt() and 0xFF) / 255f
            }
        }
        val out = session.run(input, intArrayOf(1, TILE, TILE, 3))
        val side = TILE * 4
        require(out.size == side * side * 3) { "model returned ${out.size} values, expected ${side * side * 3}" }
        val offX = (blockX - left) * 4
        val offY = (blockY - top) * 4
        val outW = blockW * factor
        val outH = blockH * factor
        val result = FloatArray(outW * outH * 3)
        val k = 4 / factor // source samples averaged per output pixel along each axis (1 for ×4, 2 for ×2)
        for (y in 0 until outH) for (x in 0 until outW) for (c in 0 until 3) {
            var sum = 0f
            for (dy in 0 until k) for (dx in 0 until k) sum += out[((offY + y * k + dy) * side + offX + x * k + dx) * 3 + c]
            result[(y * outW + x) * 3 + c] = (sum / (k * k)).coerceIn(0f, 1f)
        }
        return result
    }

    companion object {
        const val TILE = 128
        const val MARGIN = 16
        /** Source pixels per block: the tile minus a margin on both sides. */
        const val BLOCK = TILE - 2 * MARGIN
    }
}

/** How a picture is walked: the blocks (left, top, width, height) of an area, each at most [size] on a side. */
object BlockPlan {
    fun blocks(left: Int, top: Int, right: Int, bottom: Int, size: Int): List<DocBlock> {
        val out = ArrayList<DocBlock>()
        var y = top
        while (y < bottom) {
            val h = min(size, bottom - y)
            var x = left
            while (x < right) {
                out += DocBlock(x, y, min(size, right - x), h)
                x += size
            }
            y += size
        }
        return out
    }
}

data class DocBlock(val x: Int, val y: Int, val width: Int, val height: Int) {
    val right: Int get() = x + width
    val bottom: Int get() = y + height
}

/** Largest areas the in-memory restoration paths accept (pixels). */
object RestorationLimits {
    const val DENOISE_PIXELS = 120_000_000L
    const val UPSCALE_OUTPUT_PIXELS = 100_000_000L
}
