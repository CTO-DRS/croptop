package app.croptop.core.engine

/**
 * Narrow JNI surface for moving pixels into the engine from outside (model output). Store handles are opaque; stores returned
 * here belong to the caller. Failures return 0 / false: read [lastError] on the same thread.
 */
internal object NativePixelBridge {
    external fun lastError(): String

    /** 0–255 coverage bytes of an A16 mask over the rectangle (row by row, 0 outside the mask); null on failure. */
    external fun readCoverage(store: Long, left: Int, top: Int, width: Int, height: Int): ByteArray?

    /** A16 mask store at the size of [guide] from an 8-bit coarse mask, its edges following the image; 0 on failure. */
    external fun guidedUpsample(guide: Long, lowWidth: Int, lowHeight: Int, alpha: ByteArray, radius: Int, epsilon: Float): Long

    /**
     * Writes straight-alpha RGBA ([srcWidth] × [srcHeight]) over the region of [store], scaled bilinearly; [coverage] (same size,
     * optional) says how much of each new pixel replaces the old one.
     */
    external fun writeRegion(
        store: Long,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
        srcWidth: Int,
        srcHeight: Int,
        rgba: ByteArray,
        coverage: ByteArray?,
    ): Boolean
}
