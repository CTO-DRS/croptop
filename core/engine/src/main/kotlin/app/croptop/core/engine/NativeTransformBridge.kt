package app.croptop.core.engine

/**
 * Native layer transform sessions (`transform_session`), the pixel side of engine-api `TransformSession`
 * (Move drag / Free Transform, optionally limited to the selection). A document's `beginTransform` should:
 * [begin] per target layer (passing the selection's mask store, or 0), call [preview] on each `update`
 * for the viewport proxy, and on `commit` call [commit], swap the layer's store for the returned handle as
 * one history step and invalidate the dirty rectangle; `cancel` just [destroy]s. Matrices are Matrix3
 * values (row-major, document space, relative to the state at begin). Store handles returned here belong
 * to the caller (destroy them like any NativeTileStore).
 */
internal object NativeTransformBridge {
    enum class Filter { NEAREST, BILINEAR, BICUBIC, LANCZOS3 }

    data class Commit(val storeHandle: Long, val left: Int, val top: Int, val right: Int, val bottom: Int)

    fun begin(layerStore: Long, selectionStore: Long = 0L): Long {
        val handle = nativeBegin(layerStore, selectionStore)
        require(handle != 0L) { nativeLastError().ifBlank { "Transform could not start" } }
        return handle
    }

    /** A new proxy store at [scale] (0 < scale ≤ 1). */
    fun preview(session: Long, matrix: FloatArray, scale: Float): Long {
        val handle = nativePreview(session, matrix, scale)
        require(handle != 0L) { nativeLastError().ifBlank { "Transform preview failed" } }
        return handle
    }

    fun commit(session: Long, matrix: FloatArray, filter: Filter = Filter.BICUBIC): Commit {
        val values = nativeCommit(session, matrix, filter.ordinal) ?: throw IllegalArgumentException(nativeLastError().ifBlank { "Transform failed" })
        return Commit(values[0], values[1].toInt(), values[2].toInt(), values[3].toInt(), values[4].toInt())
    }

    fun destroy(session: Long) = nativeDestroy(session)

    private external fun nativeLastError(): String
    private external fun nativeBegin(layerStore: Long, selectionStore: Long): Long
    private external fun nativePreview(session: Long, matrix: FloatArray, scale: Float): Long
    private external fun nativeCommit(session: Long, matrix: FloatArray, filter: Int): LongArray?
    private external fun nativeDestroy(session: Long)
}
