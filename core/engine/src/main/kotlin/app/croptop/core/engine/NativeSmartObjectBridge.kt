package app.croptop.core.engine

/**
 * Smart objects (native `smart_object`): a session holding a copy of a layer, a chain of 3×3 transforms and non-destructive smart filters. [Session.render]
 * resamples the source through the transforms into a document of any size and applies the enabled filters (this is also "Rasterize"); editing a filter or
 * a transform never touches the source pixels. Close the session when the layer is rasterized or deleted.
 */
internal object NativeSmartObjectBridge {
    enum class Quality { NEAREST, BILINEAR, BICUBIC, LANCZOS3 }

    class Session(layerHandle: Long) : AutoCloseable {
        private var handle = nativeConvert(layerHandle).also { check(it != 0L) { nativeLastError().ifBlank { "Smart object failed" } } }

        private fun live(): Long = handle.also { check(it != 0L) { "Smart object session is closed" } }

        /** Row-major 3×3 source→destination matrices, applied first to last. */
        fun setTransforms(matrices: List<DoubleArray>) {
            require(matrices.all { it.size == 9 }) { "each transform needs nine numbers" }
            check(
                nativeSetTransforms(
                    live(),
                    matrices.flatMap {
                        it.asList()
                    }.toDoubleArray(),
                ) == 1L,
            ) { nativeLastError().ifBlank { "Transforms rejected" } }
        }

        /** Returns the filter's index. [blendMode] follows the native BlendMode ordinals (0 = Normal). */
        fun addGaussianBlur(radius: Float, blendMode: Int = 0, opacity: Float = 1f): Int = addFilter(0, floatArrayOf(radius), blendMode, opacity)
        fun addUnsharpMask(amount: Float, radius: Float, threshold: Float, blendMode: Int = 0, opacity: Float = 1f): Int =
            addFilter(1, floatArrayOf(amount, radius, threshold), blendMode, opacity)

        private fun addFilter(kind: Int, params: FloatArray, blendMode: Int, opacity: Float): Int {
            val index = nativeAddFilter(live(), kind, params, blendMode, opacity)
            check(index >= 0L && (index > 0L || nativeLastError().isBlank())) { nativeLastError().ifBlank { "Smart filter failed" } }
            return index.toInt()
        }

        fun configureFilter(index: Int, enabled: Boolean, blendMode: Int = 0, opacity: Float = 1f) {
            check(nativeConfigureFilter(live(), index, enabled, blendMode, opacity) == 1L) { nativeLastError().ifBlank { "No such smart filter" } }
        }

        fun removeFilter(index: Int) {
            check(nativeRemoveFilter(live(), index) == 1L) { nativeLastError().ifBlank { "No such smart filter" } }
        }

        val filterCount: Int get() = nativeFilterCount(live())

        /** [maskHandle] is an A16 store of the document size; 0 clears the filter mask. */
        fun setFilterMask(maskHandle: Long, enabled: Boolean = true) {
            check(nativeSetFilterMask(live(), maskHandle, enabled) == 1L) { nativeLastError().ifBlank { "Filter mask rejected" } }
        }

        /** A new store the caller owns (`NativeSelectionBridge.destroyStore`). */
        fun render(documentWidth: Int, documentHeight: Int, quality: Quality = Quality.BICUBIC, workerThreads: Int = 0): Long {
            val out = nativeRender(live(), documentWidth, documentHeight, quality.ordinal, workerThreads)
            check(out != 0L) { nativeLastError().ifBlank { "Smart object render failed" } }
            return out
        }

        override fun close() {
            if (handle != 0L) nativeDestroy(handle)
            handle = 0L
        }
    }

    private external fun nativeLastError(): String
    private external fun nativeConvert(layerHandle: Long): Long
    private external fun nativeDestroy(obj: Long)
    private external fun nativeSetTransforms(obj: Long, matrices: DoubleArray): Long
    private external fun nativeAddFilter(obj: Long, kind: Int, params: FloatArray, blendMode: Int, opacity: Float): Long
    private external fun nativeConfigureFilter(obj: Long, index: Int, enabled: Boolean, blendMode: Int, opacity: Float): Long
    private external fun nativeRemoveFilter(obj: Long, index: Int): Long
    private external fun nativeFilterCount(obj: Long): Int
    private external fun nativeSetFilterMask(obj: Long, maskHandle: Long, enabled: Boolean): Long
    private external fun nativeRender(obj: Long, width: Int, height: Int, quality: Int, workerThreads: Int): Long
    private external fun nativeBlendFilter(input: Long, filtered: Long, blendMode: Int, opacity: Float, workerThreads: Int): Long

    private external fun nativeBlendThroughMask(base: Long, filtered: Long, mask: Long, workerThreads: Int): Long

    /** `mix(base, filtered, mask coverage)` per pixel: the smart-filter mask applied to a filtered render (new store, caller-owned). */
    fun blendThroughMask(base: Long, filtered: Long, mask: Long, workerThreads: Int = 0): Long =
        nativeBlendThroughMask(base, filtered, mask, workerThreads).also {
            check(it != 0L) { nativeLastError().ifBlank { "Smart filter mask blend failed" } }
        }

    fun blendFilter(input: Long, filtered: Long, blendMode: Int, opacity: Float, workerThreads: Int = 0): Long =
        nativeBlendFilter(input, filtered, blendMode, opacity, workerThreads).also {
            check(it != 0L) { nativeLastError().ifBlank { "Smart filter blend failed" } }
        }
}
