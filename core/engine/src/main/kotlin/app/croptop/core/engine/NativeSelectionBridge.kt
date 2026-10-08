package app.croptop.core.engine

/**
 * Narrow JNI surface for native selection operations. Store and session handles are opaque and must
 * be released by the owner that created them. Store-producing methods return 0 on failure; read
 * [nativeLastError] immediately on the same thread to retrieve the failure message.
 *
 * Integer operation values follow the native enums: combine is Replace=0, Add=1, Subtract=2,
 * Intersect=3; modify is Feather=0, Expand=1, Contract=2, Border=3, Smooth=4. Color range target
 * values follow `ColorRangeTarget` declaration order.
 */
internal object NativeSelectionBridge {
    external fun nativeLastError(): String

    /** Model-free Object Selection (region) or Select Subject ([subject] = true, region ignored); 0 on failure. */
    external fun selectObject(source: Long, left: Double, top: Double, right: Double, bottom: Double, subject: Boolean): Long
    external fun objectSelectLastError(): String
    external fun destroyStore(store: Long)

    external fun rasterizeRect(
        width: Int,
        height: Int,
        left: Double,
        top: Double,
        right: Double,
        bottom: Double,
        antialias: Boolean,
    ): Long

    external fun rasterizeEllipse(
        width: Int,
        height: Int,
        left: Double,
        top: Double,
        right: Double,
        bottom: Double,
        antialias: Boolean,
    ): Long

    /** `xy` packs finite x,y coordinate pairs. */
    external fun rasterizePolygon(width: Int, height: Int, xy: DoubleArray, antialias: Boolean): Long

    external fun combine(selection: Long, shape: Long, operation: Int): Long
    external fun invert(selection: Long): Long
    external fun modify(selection: Long, operation: Int, radius: Float): Long

    external fun magicWand(source: Long, x: Long, y: Long, tolerance: Int, contiguous: Boolean): Long

    /** `samples` packs zero or more sRGB RGB triples. */
    external fun colorRange(
        source: Long,
        target: Int,
        samples: FloatArray,
        fuzziness: Int,
        invert: Boolean,
        workerThreads: Int,
    ): Long

    external fun quickBegin(
        source: Long,
        before: Long,
        diameter: Float,
        subtract: Boolean,
        enhanceEdge: Boolean,
    ): Long

    /** `xy` packs finite document-space brush centers. */
    external fun quickAdd(session: Long, xy: FloatArray): Boolean
    external fun quickCurrent(session: Long): Long
    external fun quickFinish(session: Long): Long
    external fun quickDestroy(session: Long)

    /** `matrix` is a row-major 3x3 transform and must contain exactly nine values. */
    external fun transform(selection: Long, matrix: DoubleArray, workerThreads: Int): Long

    /** Returns an empty array for an empty mask, else `[left, top, right, bottom]` in document pixels. */
    external fun bounds(selection: Long): IntArray

    /**
     * Returns `[truncated(0|1), segmentCount, x0, y0, x1, y1, ...]` for pixel-edge contour segments.
     * `region` is `[left, top, right, bottom]` in document pixels.
     */
    external fun contour(
        selection: Long,
        region: IntArray,
        mipLevel: Int,
        threshold: Int,
        maxSegments: Int,
    ): IntArray
}
