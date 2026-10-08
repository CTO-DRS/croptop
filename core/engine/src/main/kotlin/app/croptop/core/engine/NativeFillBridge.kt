package app.croptop.core.engine

internal fun interface NativeFillProgressListener {
    /** @return false to cancel before the fill history step commits. */
    fun onProgress(progress: Float): Boolean
}

/** Native pixel kernels used by destructive fill commands. Store handles remain owned by NativeDocument. */
internal object NativeFillBridge {
    /** Returns null after success, or a useful rejection message. */
    fun solid(
        store: Long,
        rgba: FloatArray,
        opacity: Float,
        blendMode: Int,
        preserveTransparency: Boolean,
        selectionMask: Long = 0L,
    ): String? = nativeSolid(store, rgba, opacity, blendMode, preserveTransparency, selectionMask)

    /** Runs native PatchMatch inside the selected A16 region and applies it to the COW target. */
    fun contentAware(
        store: Long,
        opacity: Float,
        blendMode: Int,
        preserveTransparency: Boolean,
        selectionMask: Long,
        progress: NativeFillProgressListener = NativeFillProgressListener { true },
        options: app.croptop.core.model.ContentAwareOptions = app.croptop.core.model.ContentAwareOptions(),
    ): String? = nativeContentAware(
        store, opacity, blendMode, preserveTransparency, selectionMask, progress,
        options.rotation.ordinal, options.mirror, options.colorAdaptation,
        if (options.samplingOnlyPainted) 1 else 0, encodeStrokes(options.strokes),
    )

    /** [count, then per stroke: add (0/1), radius, point count, x0, y0, ...]; empty when there are no strokes. */
    internal fun encodeStrokes(strokes: List<app.croptop.core.model.SamplingStroke>): FloatArray {
        if (strokes.isEmpty()) return FloatArray(0)
        val out = ArrayList<Float>()
        out += strokes.size.toFloat()
        for (stroke in strokes) {
            out += if (stroke.add) 1f else 0f
            out += stroke.radius
            out += stroke.points.size.toFloat()
            for (p in stroke.points) {
                out += p.x
                out += p.y
            }
        }
        return out.toFloatArray()
    }

    /**
     * [base] (0 = empty) with brush [strokes] applied (add covers, subtract clears) as a new A16 store the same size as [like];
     * the caller owns the handle. Throws with the native reason on failure.
     */
    fun strokeMask(like: Long, base: Long, strokes: List<app.croptop.core.model.SamplingStroke>): Long {
        val handle = nativeStrokeMask(like, base, encodeStrokes(strokes))
        check(handle != 0L) { nativeStrokeMaskError().ifBlank { "Could not apply the strokes" } }
        return handle
    }

    private external fun nativeStrokeMask(like: Long, base: Long, strokes: FloatArray): Long

    private external fun nativeStrokeMaskError(): String

    private external fun nativeSolid(
        store: Long,
        rgba: FloatArray,
        opacity: Float,
        blendMode: Int,
        preserveTransparency: Boolean,
        selectionMask: Long,
    ): String?

    private external fun nativeContentAware(
        store: Long,
        opacity: Float,
        blendMode: Int,
        preserveTransparency: Boolean,
        selectionMask: Long,
        progress: NativeFillProgressListener,
        rotation: Int,
        mirror: Boolean,
        colorAdaptation: Boolean,
        samplingMode: Int,
        samplingStrokes: FloatArray,
    ): String?
}
