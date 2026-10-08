package app.croptop.core.engine.api

import kotlinx.serialization.Serializable

/**
 * A downscaled merged-canvas snapshot in row-major, straight-alpha sRGB RGBA byte order.
 *
 * Preview dimensions are bounded to 1024 pixels per side (the largest picture an on-device model looks at). [rgba] must contain exactly
 * `width * height * 4` bytes.
 */
@Serializable
data class DocumentPreview(val width: Int, val height: Int, val rgba: ByteArray) {
    init {
        require(width in 1..MAX_DIMENSION && height in 1..MAX_DIMENSION) {
            "Preview dimensions must be between 1 and $MAX_DIMENSION px"
        }
        val expectedBytes = width.toLong() * height * BYTES_PER_PIXEL
        require(expectedBytes == rgba.size.toLong()) {
            "Preview RGBA buffer must contain exactly $expectedBytes bytes"
        }
    }

    companion object {
        const val MAX_DIMENSION = 1024
        private const val BYTES_PER_PIXEL = 4

        /** Fits the canvas inside [maxDimension], preserving its aspect ratio. */
        fun dimensionsFor(canvasWidth: Int, canvasHeight: Int, maxDimension: Int): Pair<Int, Int> {
            require(canvasWidth > 0 && canvasHeight > 0)
            require(maxDimension in 1..MAX_DIMENSION)
            val scale = minOf(1.0, maxDimension.toDouble() / maxOf(canvasWidth, canvasHeight))
            return (canvasWidth * scale).toInt().coerceAtLeast(1) to
                (canvasHeight * scale).toInt().coerceAtLeast(1)
        }
    }
}
