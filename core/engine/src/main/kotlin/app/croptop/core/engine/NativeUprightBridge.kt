package app.croptop.core.engine

/**
 * Upright and manual perspective correction (native `develop_upright_bridge`). [analyze] finds the transform (or applies the manual sliders)
 * and [render] resamples a layer with it; the [UprightResult] can be re-rendered with a cheaper filter for previews without re-detecting.
 */
internal object NativeUprightBridge {
    enum class Mode { LEVEL, VERTICAL, FULL, AUTO, GUIDED, MANUAL }
    enum class Filter { NEAREST, BILINEAR, BICUBIC, LANCZOS3 }

    /** Guide line in source pixels (Guided mode). */
    data class Guide(val x1: Double, val y1: Double, val x2: Double, val y2: Double)

    /** Manual sliders: vertical/horizontal/aspect/xOffset/yOffset −100…100, rotate in degrees, scale in percent. */
    data class Manual(
        val vertical: Double = 0.0,
        val horizontal: Double = 0.0,
        val rotate: Double = 0.0,
        val aspect: Double = 0.0,
        val scale: Double = 100.0,
        val xOffset: Double = 0.0,
        val yOffset: Double = 0.0,
        val focalLengthPx: Double = 0.0,
    )

    class UprightResult internal constructor(internal val packed: DoubleArray, val notes: String) {
        /** Source → output transform, row-major 3×3. */
        val matrix: DoubleArray get() = packed.copyOfRange(0, 9)
        val cropLeft: Double get() = packed[9]
        val cropTop: Double get() = packed[10]
        val cropRight: Double get() = packed[11]
        val cropBottom: Double get() = packed[12]
        val width: Int get() = packed[13].toInt()
        val height: Int get() = packed[14].toInt()
        val focalLengthPx: Double get() = packed[15]
        val verticalFound: Boolean get() = packed[16] != 0.0
        val horizontalFound: Boolean get() = packed[17] != 0.0
        val tiltDegrees: Double get() = packed[18]
        val segmentsUsed: Int get() = packed[19].toInt()
    }

    fun analyze(
        layerHandle: Long,
        mode: Mode,
        guides: List<Guide> = emptyList(),
        manual: Manual = Manual(),
        exifFocalLength35mm: Double = 0.0,
        autoCrop: Boolean = true,
    ): UprightResult {
        val g = DoubleArray(guides.size * 4) { i -> guides[i / 4].let { listOf(it.x1, it.y1, it.x2, it.y2)[i % 4] } }
        val m =
            doubleArrayOf(
                manual.vertical,
                manual.horizontal,
                manual.rotate,
                manual.aspect,
                manual.scale,
                manual.xOffset,
                manual.yOffset,
                manual.focalLengthPx,
            )
        val packed =
            checkNotNull(nativeAnalyze(layerHandle, mode.ordinal, exifFocalLength35mm, g, m, autoCrop)) {
                nativeLastError().ifBlank { "Upright failed" }
            }
        return UprightResult(packed, nativeLastNote())
    }

    /** A new cropped, corrected store the caller owns (`NativeSelectionBridge.destroyStore`). */
    fun render(layerHandle: Long, result: UprightResult, filter: Filter = Filter.LANCZOS3, workerThreads: Int = 0): Long {
        val out = nativeRender(layerHandle, result.packed, filter.ordinal, workerThreads)
        check(out != 0L) { nativeLastError().ifBlank { "Upright render failed" } }
        return out
    }

    private external fun nativeLastError(): String
    private external fun nativeLastNote(): String
    private external fun nativeAnalyze(
        storeHandle: Long,
        mode: Int,
        exifFocal35mm: Double,
        guides: DoubleArray,
        manual: DoubleArray,
        autoCrop: Boolean,
    ): DoubleArray?
    private external fun nativeRender(storeHandle: Long, result: DoubleArray, filter: Int, workerThreads: Int): Long
}
