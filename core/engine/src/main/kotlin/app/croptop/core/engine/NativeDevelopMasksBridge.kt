package app.croptop.core.engine

import app.croptop.core.model.DevelopSettings

/**
 * Develop local-adjustment masks (native `develop_masks`, `develop_mask_ops`, `develop_mask_blend`): gradient and radial masks, luminance and colour
 * ranges, brush dabs, mask algebra and the masked blend that applies a local adjustment. Every call returns a new store the caller owns
 * (`NativeSelectionBridge.destroyStore`); masks are A16 and inputs are never modified.
 */
internal object NativeDevelopMasksBridge {
    enum class Op { REPLACE, ADD, SUBTRACT, INTERSECT }

    fun linearGradient(width: Int, height: Int, x1: Double, y1: Double, x2: Double, y2: Double): Long =
        result(nativeLinearGradient(width, height, x1, y1, x2, y2), "Gradient mask")

    fun radial(width: Int, height: Int, centreX: Double, centreY: Double, radiusX: Double, radiusY: Double, rotationDegrees: Double = 0.0, feather: Double = 0.5, invert: Boolean = false): Long =
        result(nativeRadial(width, height, centreX, centreY, radiusX, radiusY, rotationDegrees, feather, invert), "Radial mask")

    fun luminanceRange(imageHandle: Long, low: Float, high: Float, smoothness: Float): Long = result(nativeLuminanceRange(imageHandle, low, high, smoothness), "Luminance range")

    /** [r], [g], [b] are the target colour in the image's stored 0-1 domain. */
    fun colorRange(imageHandle: Long, r: Float, g: Float, b: Float, tolerance: Float = 0.05f, feather: Float = 0.05f): Long =
        result(nativeColorRange(imageHandle, r, g, b, tolerance, feather), "Colour range")

    /** A new mask with one dab added ([size] is the diameter in px, [feather] and [flow] 0-1). */
    fun brushDab(maskHandle: Long, x: Double, y: Double, size: Double, feather: Double = 0.5, flow: Double = 1.0, erase: Boolean = false): Long =
        result(nativeBrushDab(maskHandle, x, y, size, feather, flow, erase), "Brush dab")

    fun combine(base: Long, operand: Long, op: Op): Long = result(nativeCombine(base, operand, op.ordinal), "Mask operation")

    /** Mixes [adjusted] over [base] by mask coverage × [opacity]; alpha stays the base's. */
    fun applyMasked(base: Long, adjusted: Long, mask: Long, opacity: Float = 1f): Long = result(nativeApplyMasked(base, adjusted, mask, opacity), "Masked adjustment")

    /**
     * Runs the RAW Develop pipeline on [source], then applies the rendered result only through [mask].
     * The returned store is caller-owned; the intermediate full Develop render is always released here.
     */
    fun renderLocalAdjustment(
        source: Long,
        mask: Long,
        settings: DevelopSettings,
        opacity: Float = 1f,
        useGpu: Boolean = true,
        workerThreads: Int = 0,
        grainSeed: Int = 1,
    ): Long {
        require(opacity.isFinite() && opacity in 0f..1f) { "Local Develop opacity must be in 0..1" }
        val adjusted = NativeDevelopBridge.render(
            source,
            settings,
            useGpu = useGpu,
            workerThreads = workerThreads,
            grainSeed = grainSeed,
        ).storeHandle
        return try {
            applyMasked(source, adjusted, mask, opacity)
        } finally {
            NativeSelectionBridge.destroyStore(adjusted)
        }
    }

    private fun result(handle: Long, what: String): Long {
        check(handle != 0L) { nativeLastError().ifBlank { "$what failed" } }
        return handle
    }

    private external fun nativeLastError(): String
    private external fun nativeLinearGradient(width: Int, height: Int, x1: Double, y1: Double, x2: Double, y2: Double): Long
    private external fun nativeRadial(width: Int, height: Int, cx: Double, cy: Double, rx: Double, ry: Double, rotation: Double, feather: Double, invert: Boolean): Long
    private external fun nativeLuminanceRange(image: Long, low: Float, high: Float, smoothness: Float): Long
    private external fun nativeColorRange(image: Long, r: Float, g: Float, b: Float, tolerance: Float, feather: Float): Long
    private external fun nativeBrushDab(mask: Long, cx: Double, cy: Double, size: Double, feather: Double, flow: Double, erase: Boolean): Long
    private external fun nativeCombine(base: Long, operand: Long, op: Int): Long
    private external fun nativeApplyMasked(base: Long, adjusted: Long, mask: Long, opacity: Float): Long
}
