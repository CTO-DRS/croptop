package app.croptop.core.engine

/**
 * Multi-image merges (native `stack_merge`, `panorama`): alignment, HDR merge, focus stacking and panorama stitching. Inputs are layer store
 * handles; every merge returns a new store the caller owns (`NativeSelectionBridge.destroyStore`). Run them as background jobs: there is no
 * mid-merge cancellation over JNI, so drop a stale result instead.
 */
internal object NativeStackBridge {
    enum class Projection { AUTO, PERSPECTIVE, CYLINDRICAL, REPOSITION }

    class Offset(val dx: Int, val dy: Int)

    class StitchReport(val projection: Projection, val placed: List<Int>, val dropped: List<Int>, val gains: List<Float>, val anchor: Int)

    fun align(referenceHandle: Long, frameHandle: Long, levels: Int = 6): Offset {
        val v = checkNotNull(nativeAlign(referenceHandle, frameHandle, levels)) { nativeLastError().ifBlank { "Alignment failed" } }
        return Offset(v[0], v[1])
    }

    /** Linear RGBA32F HDR from bracketed frames; [exposures] are relative (for example 2^EV), one per frame. */
    fun mergeHdr(frames: List<Long>, exposures: List<Float>, align: Boolean = true, workerThreads: Int = 0): Long {
        require(frames.size == exposures.size) { "one exposure per frame is required" }
        val out = nativeMergeHdr(frames.toLongArray(), exposures.toFloatArray(), align, workerThreads)
        check(out != 0L) { nativeLastError().ifBlank { "HDR merge failed" } }
        return out
    }

    fun focusStack(frames: List<Long>, align: Boolean = true, smoothing: Float = 4f, workerThreads: Int = 0): Long {
        val out = nativeFocusStack(frames.toLongArray(), align, smoothing, workerThreads)
        check(out != 0L) { nativeLastError().ifBlank { "Focus stack failed" } }
        return out
    }

    /** Stitches 2-32 layers; returns the panorama store and which inputs were placed or dropped. */
    fun stitch(
        frames: List<Long>,
        projection: Projection = Projection.AUTO,
        blend: Boolean = true,
        exposureCompensation: Boolean = true,
        workerThreads: Int = 0,
    ): Pair<Long, StitchReport> {
        val out = nativeStitch(frames.toLongArray(), projection.ordinal, blend, exposureCompensation, workerThreads)
        check(out != 0L) { nativeLastError().ifBlank { "Panorama failed" } }
        val info = nativeLastInfo().split(';').mapNotNull { it.split('=', limit = 2).takeIf { p -> p.size == 2 }?.let { p -> p[0] to p[1] } }.toMap()
        fun ints(key: String) = info[key].orEmpty().split(',').mapNotNull(String::toIntOrNull)
        val report = StitchReport(
            Projection.entries.getOrElse(info["projection"]?.toIntOrNull() ?: 0) { Projection.PERSPECTIVE },
            ints("placed"),
            ints("dropped"),
            info["gains"].orEmpty().split(',').mapNotNull(String::toFloatOrNull),
            info["anchor"]?.toIntOrNull() ?: 0,
        )
        return out to report
    }

    private external fun nativeLastError(): String
    private external fun nativeLastInfo(): String
    private external fun nativeAlign(reference: Long, frame: Long, levels: Int): IntArray?
    private external fun nativeMergeHdr(handles: LongArray, exposures: FloatArray, align: Boolean, workerThreads: Int): Long
    private external fun nativeFocusStack(handles: LongArray, align: Boolean, smoothing: Float, workerThreads: Int): Long
    private external fun nativeStitch(handles: LongArray, projection: Int, blend: Boolean, exposureCompensation: Boolean, workerThreads: Int): Long
}
