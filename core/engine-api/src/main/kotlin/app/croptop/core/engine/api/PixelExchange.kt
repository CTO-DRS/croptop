package app.croptop.core.engine.api

/** A model's coarse output mask: [alpha] holds `width × height` bytes, 0 = outside, 255 = inside, row by row. */
/**
 * A model's mask for [Document.selectFromMask]. [refine] snaps its edges to the picture (a guided filter), which a coarse mask from
 * a small network needs; a mask that is already precise (a high-resolution matting model) sets it false so its edge is kept as is.
 */
class CoarseMask(val width: Int, val height: Int, val alpha: ByteArray, val refine: Boolean = true) {
    init {
        require(width in 1..MAX_SIDE && height in 1..MAX_SIDE) { "A coarse mask is 1–$MAX_SIDE px per side" }
        require(alpha.size.toLong() == width.toLong() * height) { "A coarse mask needs width × height bytes" }
    }

    companion object {
        const val MAX_SIDE = 4096
    }
}

/**
 * Straight-alpha 8-bit RGBA pixels from outside the engine (model output). [coverage], when present, is `width × height` bytes
 * saying how much of each pixel replaces what is there; without it the pixels replace outright.
 */
class PixelPatch(val width: Int, val height: Int, val rgba: ByteArray, val coverage: ByteArray? = null) {
    init {
        require(width in 1..CoarseMask.MAX_SIDE && height in 1..CoarseMask.MAX_SIDE) { "A pixel patch is 1–${CoarseMask.MAX_SIDE} px per side" }
        require(rgba.size.toLong() == width.toLong() * height * 4) { "A pixel patch needs width × height × 4 bytes" }
        require(coverage == null || coverage.size.toLong() == width.toLong() * height) { "Coverage needs width × height bytes" }
    }
}
