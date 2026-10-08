package app.croptop.core.engine

/**
 * Liquify sessions, Red Eye, histograms and Refine Edge (native `liquify`, `red_eye`, `histogram`, `refine_edge`). Calls that return a
 * store work on a copy-on-write clone (the source layer is untouched for history); the caller owns every returned handle
 * (`NativeSelectionBridge.destroyStore`).
 */
internal object NativeImageOpsBridge {
    enum class LiquifyTool { FORWARD_WARP, RECONSTRUCT, SMOOTH, TWIRL_CLOCKWISE, TWIRL_COUNTER_CLOCKWISE, PUCKER, BLOAT, PUSH_LEFT }

    class LiquifyField(val columns: Int, val rows: Int, val spacing: Int, val nodes: FloatArray)

    /** One Liquify edit: strokes accumulate in a displacement mesh; [render] applies it to a layer at full resolution. */
    class LiquifySession(width: Int, height: Int, spacing: Int = 4) : AutoCloseable {
        private var handle = nativeLiquifyCreate(width, height, spacing).also { check(it != 0L) { nativeLastError().ifBlank { "Liquify failed" } } }

        private fun live(): Long = handle.also { check(it != 0L) { "Liquify session is closed" } }

        fun apply(tool: LiquifyTool, x: Double, y: Double, size: Double, density: Float = 0.5f, pressure: Float = 1f, rate: Float = 0.8f, dx: Double = 0.0, dy: Double = 0.0) {
            check(nativeLiquifyApply(live(), tool.ordinal, x, y, size, density, pressure, rate, dx, dy) == 1L) { nativeLastError().ifBlank { "Liquify stroke failed" } }
        }

        fun reconstructAll(amount: Float) = nativeLiquifyReconstructAll(live(), amount)

        /** [maskHandle] is an A16 store the size of the document; 0 clears the freeze mask. */
        fun setFreezeMask(maskHandle: Long) {
            check(nativeLiquifySetFreeze(live(), maskHandle) == 1L) { nativeLastError().ifBlank { "Freeze mask failed" } }
        }

        fun render(layerHandle: Long, workerThreads: Int = 0, selectionHandle: Long = 0L): Long {
            val out = nativeLiquifyRender(live(), layerHandle, workerThreads, selectionHandle)
            check(out != 0L) { nativeLastError().ifBlank { "Liquify render failed" } }
            return out
        }

        /** The displacement nodes for a GPU preview texture (RG32F, `columns × rows`). */
        fun field(): LiquifyField {
            val raw = checkNotNull(nativeLiquifyField(live())) { "Liquify field unavailable" }
            return LiquifyField(raw[0].toInt(), raw[1].toInt(), raw[2].toInt(), raw.copyOfRange(3, raw.size))
        }

        override fun close() {
            if (handle != 0L) nativeLiquifyDestroy(handle)
            handle = 0L
        }
    }

    /** Red Eye inside the A16 [regionHandle]; [pupilSize] and [darken] are 0-1. */
    fun redEye(layerHandle: Long, regionHandle: Long, pupilSize: Float = 0.5f, darken: Float = 0.5f): Long {
        val out = nativeRedEye(layerHandle, regionHandle, pupilSize, darken)
        check(out != 0L) { nativeLastError().ifBlank { "Red Eye failed" } }
        return out
    }

    class HistogramData(val red: DoubleArray, val green: DoubleArray, val blue: DoubleArray, val luminosity: DoubleArray, val total: Double)

    /** 256-bin histograms weighted by alpha and [maskHandle] (0 = none); [mipLevel] > 0 reads the cached reduction for live panels. */
    fun histogram(layerHandle: Long, maskHandle: Long = 0L, mipLevel: Int = 0, workerThreads: Int = 0): HistogramData {
        val raw = checkNotNull(nativeHistogram(layerHandle, maskHandle, mipLevel, workerThreads)) { nativeLastError().ifBlank { "Histogram failed" } }
        return HistogramData(raw.copyOfRange(0, 256), raw.copyOfRange(256, 512), raw.copyOfRange(512, 768), raw.copyOfRange(768, 1024), raw[1024])
    }

    /** Refine Edge: returns a new A16 mask. Ranges: radius 0-250, smooth 0-100, feather 0-250, contrast 0-100, shiftEdge -100..100. */
    fun refineEdge(guideHandle: Long, maskHandle: Long, radius: Float, smartRadius: Boolean = false, smooth: Float = 0f, feather: Float = 0f, contrast: Float = 0f, shiftEdge: Float = 0f): Long {
        val out = nativeRefineEdge(guideHandle, maskHandle, floatArrayOf(radius, if (smartRadius) 1f else 0f, smooth, feather, contrast, shiftEdge))
        check(out != 0L) { nativeLastError().ifBlank { "Refine Edge failed" } }
        return out
    }

    /** Decontaminate Colors: a new image store with the soft edge of [maskHandle] colour-corrected by [amount] (0-1). */
    fun decontaminate(imageHandle: Long, maskHandle: Long, amount: Float): Long {
        val out = nativeDecontaminate(imageHandle, maskHandle, amount)
        check(out != 0L) { nativeLastError().ifBlank { "Decontaminate failed" } }
        return out
    }

    enum class RetouchSource { CLONE_STAMP, HEALING_BLEND, PATCH, PATCH_CONTENT_AWARE }
    enum class SpotHealType { CONTENT_AWARE, PROXIMITY_MATCH, CREATE_TEXTURE }

    /** Clone stamp / healing blend / patch inside the A16 [regionHandle], sampling [sourceHandle] shifted by (offsetX, offsetY). Patch ignores the source store. */
    fun retouchFromSource(layerHandle: Long, sourceHandle: Long, regionHandle: Long, op: RetouchSource, offsetX: Double, offsetY: Double, workerThreads: Int = 0): Long {
        val out = nativeRetouchFromSource(layerHandle, sourceHandle, regionHandle, op.ordinal, offsetX, offsetY, workerThreads)
        check(out != 0L) { nativeLastError().ifBlank { "${op.name} failed" } }
        return out
    }

    fun spotHeal(layerHandle: Long, maskHandle: Long, type: SpotHealType = SpotHealType.CONTENT_AWARE): Long {
        val out = nativeSpotHeal(layerHandle, maskHandle, type.ordinal)
        check(out != 0L) { nativeLastError().ifBlank { "Spot heal failed" } }
        return out
    }

    /** Applies a `.cube`/`.3dl` colour lookup table (file bytes and name) to a clone of the layer, blended by [strength] 0-1 and the optional mask. */
    fun applyLut(layerHandle: Long, file: ByteArray, fileName: String, strength: Float = 1f, maskHandle: Long = 0L, workerThreads: Int = 0): Long {
        val out = nativeApplyLut(layerHandle, file, fileName, strength, maskHandle, workerThreads)
        check(out != 0L) { nativeLastError().ifBlank { "Colour lookup failed" } }
        return out
    }

    class LutTexture(val size: Int, val rgba: FloatArray)

    /** The 3D table of a `.cube`/`.3dl` file as `size³` RGBA32F texels (red fastest) for a GPU 3D texture. */
    fun lutTexture(file: ByteArray, fileName: String): LutTexture {
        val raw = checkNotNull(nativeLutTexture(file, fileName)) { nativeLastError().ifBlank { "Colour lookup failed" } }
        return LutTexture(raw[0].toInt(), raw.copyOfRange(1, raw.size))
    }

    data class LensCorrection(
        val distortion: Float = 0f,
        val redCyan: Float = 0f,
        val blueYellow: Float = 0f,
        val vignetteAmount: Float = 0f,
        val vignetteMidpoint: Float = 50f,
        val verticalPerspective: Float = 0f,
        val horizontalPerspective: Float = 0f,
        val angle: Float = 0f,
        val scale: Float = 1f,
        val edgeExtend: Boolean = false,
    )

    fun lensCorrection(layerHandle: Long, spec: LensCorrection, maskHandle: Long = 0L, workerThreads: Int = 0): Long {
        val params = floatArrayOf(
            spec.distortion, spec.redCyan, spec.blueYellow, spec.vignetteAmount, spec.vignetteMidpoint,
            spec.verticalPerspective, spec.horizontalPerspective, spec.angle, spec.scale, if (spec.edgeExtend) 1f else 0f,
        )
        val out = nativeLensCorrection(layerHandle, params, maskHandle, workerThreads)
        check(out != 0L) { nativeLastError().ifBlank { "Lens correction failed" } }
        return out
    }

    private external fun nativeLastError(): String
    private external fun nativeRetouchFromSource(dstHandle: Long, srcHandle: Long, regionHandle: Long, op: Int, offsetX: Double, offsetY: Double, workerThreads: Int): Long
    private external fun nativeSpotHeal(dstHandle: Long, maskHandle: Long, type: Int): Long
    private external fun nativeApplyLut(storeHandle: Long, bytes: ByteArray, fileName: String, strength: Float, maskHandle: Long, workerThreads: Int): Long
    private external fun nativeLutTexture(bytes: ByteArray, fileName: String): FloatArray?
    private external fun nativeLensCorrection(storeHandle: Long, params: FloatArray, maskHandle: Long, workerThreads: Int): Long
    private external fun nativeLiquifyCreate(width: Int, height: Int, spacing: Int): Long
    private external fun nativeLiquifyDestroy(mesh: Long)
    private external fun nativeLiquifyApply(mesh: Long, tool: Int, x: Double, y: Double, size: Double, density: Float, pressure: Float, rate: Float, dx: Double, dy: Double): Long
    private external fun nativeLiquifyReconstructAll(mesh: Long, amount: Float)
    private external fun nativeLiquifySetFreeze(mesh: Long, maskHandle: Long): Long
    private external fun nativeLiquifyRender(mesh: Long, storeHandle: Long, workerThreads: Int, selectionHandle: Long): Long
    private external fun nativeLiquifyField(mesh: Long): FloatArray?
    private external fun nativeRedEye(storeHandle: Long, regionHandle: Long, pupilSize: Float, darken: Float): Long
    private external fun nativeHistogram(storeHandle: Long, maskHandle: Long, mipLevel: Int, workerThreads: Int): DoubleArray?
    private external fun nativeRefineEdge(guideHandle: Long, maskHandle: Long, params: FloatArray): Long
    private external fun nativeDecontaminate(imageHandle: Long, maskHandle: Long, amount: Float): Long
}
