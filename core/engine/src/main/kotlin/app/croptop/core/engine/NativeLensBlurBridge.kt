package app.croptop.core.engine

/** JNI access to the native depth-driven Lens Blur helper. Returned stores belong to the caller. */
internal object NativeLensBlurBridge {
    data class Spec(
        val radius: Float = 15f,
        val focalDistance: Float = 0f,
        val invertDepth: Boolean = false,
        val irisBlades: Int = 6,
        val irisRotationDegrees: Float = 0f,
        val irisCurvature: Float = 0f,
        val specularBrightness: Float = 0f,
        val specularThreshold: Float = 0.9f,
        val noise: Float = 0f,
        val monochromaticNoise: Boolean = true,
        val seed: Int = 1,
        val workerThreads: Int = 0,
    )

    fun lensBlur(
        layerStore: Long,
        depthStore: Long = 0L,
        spec: Spec = Spec(),
        progress: (Float) -> Boolean = { true },
    ): Long {
        require(layerStore != 0L) { "Lens Blur source store is null" }
        val progressCallback = java.util.function.DoublePredicate { value -> progress(value.toFloat()) }
        val result = nativeLensBlur(
            layerStore,
            depthStore,
            spec.radius,
            spec.focalDistance,
            spec.invertDepth,
            spec.irisBlades,
            spec.irisRotationDegrees,
            spec.irisCurvature,
            spec.specularBrightness,
            spec.specularThreshold,
            spec.noise,
            spec.monochromaticNoise,
            spec.seed,
            spec.workerThreads,
            progressCallback,
        )
        check(result != 0L) { nativeLastError().ifBlank { "Lens Blur failed" } }
        return result
    }

    fun depthRangeMask(depthStore: Long, near: Float, far: Float, feather: Float): Long {
        require(depthStore != 0L) { "Depth map store is null" }
        val result = nativeDepthRangeMask(depthStore, near, far, feather)
        check(result != 0L) { nativeLastError().ifBlank { "Depth-range mask failed" } }
        return result
    }

    /** Mixes the processed pixels over the source by A16 selection coverage. Returns a new store. */
    fun blendSelection(source: Long, processed: Long, selection: Long): Long {
        val result = nativeBlendSelection(source, processed, selection)
        check(result != 0L) { nativeLastError().ifBlank { "Could not apply the selection to Lens Blur" } }
        return result
    }

    private external fun nativeLastError(): String
    private external fun nativeLensBlur(
        layerStore: Long,
        depthStore: Long,
        radius: Float,
        focalDistance: Float,
        invertDepth: Boolean,
        irisBlades: Int,
        irisRotationDegrees: Float,
        irisCurvature: Float,
        specularBrightness: Float,
        specularThreshold: Float,
        noise: Float,
        monochromaticNoise: Boolean,
        seed: Int,
        workerThreads: Int,
        progress: java.util.function.DoublePredicate,
    ): Long

    private external fun nativeBlendSelection(source: Long, processed: Long, selection: Long): Long

    private external fun nativeDepthRangeMask(depthStore: Long, near: Float, far: Float, feather: Float): Long
}
