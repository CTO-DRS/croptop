// Geometry for AI inpaint/remove: pick the image region a LaMa-class network should see for a hole, so a
// small hole in a huge photo costs one network call on a context crop instead of tiling the whole image.
package app.croptop.core.ai

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Half-open pixel rectangle. */
data class PixelBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    init {
        require(right > left && bottom > top) { "box must not be empty" }
    }
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * What to feed the network: [crop] of the source image (holds the hole plus context), resampled by [scale] (network pixels per source
 * pixel, ≤ 1 when the crop is larger than the network's input) onto a [modelSize]² square, [padX]/[padY] being the mirrored-border
 * padding added on the right/bottom when the crop is smaller than the square. When [needsTiling] the hole itself is larger than one
 * network window and the caller falls back to PatchMatch or tiled inference.
 */
data class InpaintPlan(val crop: PixelBox, val scale: Float, val modelSize: Int, val padX: Int, val padY: Int, val needsTiling: Boolean)

object InpaintPlanner {
    /**
     * [hole] is the bounding box of the mask in an [imageWidth]×[imageHeight] image. Context is [contextFraction] of the hole's
     * larger side on every side (at least [minContext] px), then the box is grown to the network's aspect (square), clamped to the
     * image. A hole whose box does not fit a network window at [maxDownscale]× reduction requests tiling.
     */
    fun plan(
        hole: PixelBox,
        imageWidth: Int,
        imageHeight: Int,
        modelSize: Int,
        contextFraction: Float = 0.5f,
        minContext: Int = 32,
        maxDownscale: Float = 4f,
    ): InpaintPlan {
        require(imageWidth > 0 && imageHeight > 0 && modelSize > 0) { "sizes must be positive" }
        require(hole.left >= 0 && hole.top >= 0 && hole.right <= imageWidth && hole.bottom <= imageHeight) { "hole lies outside the image" }
        require(contextFraction >= 0f && maxDownscale >= 1f) { "invalid planner settings" }
        val margin = max(minContext, ceil(max(hole.width, hole.height) * contextFraction).toInt())
        var left = hole.left - margin
        var top = hole.top - margin
        var right = hole.right + margin
        var bottom = hole.bottom + margin
        // Square (network aspect): grow the shorter side around the centre.
        val side = max(right - left, bottom - top)
        val cx = (left + right) / 2
        val cy = (top + bottom) / 2
        left = cx - side / 2
        top = cy - side / 2
        right = left + side
        bottom = top + side
        // Shift into the image first, then clamp (a crop bigger than the image is cut to it).
        if (left < 0) {
            right -= left
            left = 0
        }
        if (top < 0) {
            bottom -= top
            top = 0
        }
        if (right > imageWidth) {
            left -= right - imageWidth
            right = imageWidth
        }
        if (bottom > imageHeight) {
            top -= bottom - imageHeight
            bottom = imageHeight
        }
        left = max(0, left)
        top = max(0, top)
        right = min(imageWidth, right)
        bottom = min(imageHeight, bottom)
        val crop = PixelBox(left, top, right, bottom)
        val longest = max(crop.width, crop.height)
        val scale = if (longest > modelSize) modelSize.toFloat() / longest else 1f
        val scaledW = ceil(crop.width * scale).toInt().coerceAtMost(modelSize)
        val scaledH = ceil(crop.height * scale).toInt().coerceAtMost(modelSize)
        val holeLongest = max(hole.width, hole.height)
        val needsTiling = holeLongest * scale > modelSize * 0.9f || (holeLongest / modelSize.toFloat()) > maxDownscale * 0.9f
        return InpaintPlan(crop, scale, modelSize, padX = modelSize - scaledW, padY = modelSize - scaledH, needsTiling = needsTiling)
    }
}
