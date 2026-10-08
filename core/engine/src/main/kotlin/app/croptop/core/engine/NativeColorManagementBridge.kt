package app.croptop.core.engine

/** JNI ownership boundary for Little CMS profiles and reusable Lab/CMYK readout transforms. */
internal object NativeColorManagementBridge {
    enum class BuiltInSpace { SRGB, DISPLAY_P3, ADOBE_RGB, PROPHOTO, LINEAR_SRGB, LINEAR_P3, REC2020 }

    enum class ConversionIntent { PERCEPTUAL, RELATIVE_COLORIMETRIC, SATURATION, ABSOLUTE_COLORIMETRIC }

    /** Native TileStore format codes; only RGBA stores can be color converted. */
    enum class RgbaFormat { RGBA8, RGBA16, RGBA32F }

    enum class Model { RGB, GRAY, CMYK, LAB, OTHER }

    data class Readout(val lab: List<Float>, val cmykPercent: List<Float>, val outOfGamut: Boolean)

    /**
     * Converts a copy of [sourceHandle] from [sourceIcc] to [targetIcc]. The source remains untouched;
     * the returned native TileStore handle is owned by the caller and must be released with
     * `NativeSelectionBridge.destroyStore`. The native conversion derives its output allocator from the
     * source store, preserving its TilePool and MemoryBudgetManager.
     */
    fun convertTileStore(
        sourceHandle: Long,
        sourceIcc: ByteArray,
        targetIcc: ByteArray,
        outputFormat: RgbaFormat,
        intent: ConversionIntent = ConversionIntent.RELATIVE_COLORIMETRIC,
        blackPointCompensation: Boolean = true,
        workerThreads: Int = 0,
    ): Long {
        require(sourceHandle != 0L) { "Source TileStore handle is null" }
        require(sourceIcc.isNotEmpty() && targetIcc.isNotEmpty()) { "Source and target ICC profiles are required" }
        require(workerThreads in 0..32) { "Worker count must be between 0 and 32" }
        val result = nativeConvertTileStore(
            sourceHandle,
            sourceIcc,
            targetIcc,
            outputFormat.ordinal,
            intent.ordinal,
            blackPointCompensation,
            workerThreads,
        )
        check(result != 0L) { nativeLastError().ifBlank { "Profile conversion failed" } }
        return result
    }

    /**
     * A display/soft-proof 3D LUT (`size`³ × RGB floats, red fastest) from [document] to [display]; with [proof] it also simulates that
     * device, and with [gamutWarning] paints colours the proof cannot reproduce in [warning] (display-encoded RGB).
     */
    fun bakeDisplayLut(
        document: Profile,
        display: Profile,
        size: Int,
        proof: Profile? = null,
        intent: ConversionIntent = ConversionIntent.RELATIVE_COLORIMETRIC,
        blackPointCompensation: Boolean = true,
        proofIntent: ConversionIntent = ConversionIntent.RELATIVE_COLORIMETRIC,
        gamutWarning: Boolean = false,
        warning: FloatArray = floatArrayOf(0.5f, 0.5f, 0.5f),
    ): FloatArray {
        require(size in 2..65) { "LUT size is 2–65" }
        require(warning.size == 3) { "the gamut warning colour has three channels" }
        return nativeBakeDisplayLut(
            document.live(), display.live(), size, proof?.live() ?: 0L, intent.ordinal, blackPointCompensation, proofIntent.ordinal,
            gamutWarning, warning[0], warning[1], warning[2],
        ) ?: error(nativeLastError().ifBlank { "Could not build the display transform" })
    }

    /** Runs `0xAARRGGBB` viewport pixels through a baked LUT in place (alpha untouched). */
    fun applyLutArgb(pixels: IntArray, count: Int, table: FloatArray, size: Int): Boolean = nativeApplyLutArgb(pixels, count, table, size)

    class Profile private constructor(handle: Long) : AutoCloseable {
        private var nativeHandle = handle

        val description: String get() = nativeProfileDescription(live())
        val model: Model get() = Model.entries[nativeProfileModel(live()).coerceIn(0, Model.entries.lastIndex)]
        fun iccBytes(): ByteArray = nativeProfileIccBytes(live())

        internal fun live(): Long = nativeHandle.also { check(it != 0L) { "ICC profile is closed" } }

        override fun close() {
            if (nativeHandle != 0L) nativeDestroyProfile(nativeHandle)
            nativeHandle = 0L
        }

        companion object {
            fun builtIn(space: BuiltInSpace, linear: Boolean = false): Profile =
                create(nativeCreateBuiltinProfile(space.ordinal, linear), "Could not create ${space.name} profile")

            fun fromIcc(bytes: ByteArray): Profile = create(nativeCreateIccProfile(bytes), nativeLastError().ifBlank { "Invalid ICC profile" })

            fun genericCmyk(totalInkPercent: Float = 300f): Profile =
                create(nativeCreateGenericCmykProfile(totalInkPercent), nativeLastError().ifBlank { "Could not create CMYK profile" })

            private fun create(handle: Long, error: String) = Profile(handle.also { check(it != 0L) { error } })
        }
    }

    class ReadoutSession(document: Profile, cmyk: Profile) : AutoCloseable {
        private var nativeHandle = nativeCreateReadout(document.live(), cmyk.live()).also {
            check(it != 0L) { nativeLastError().ifBlank { "Could not create color readout" } }
        }

        fun read(rgb: FloatArray): Readout {
            require(rgb.size == 3) { "RGB readout requires exactly three channels" }
            val values = nativeReadout(live(), rgb)
            check(values.size == 8) { nativeLastError().ifBlank { "Color readout failed" } }
            return Readout(values.copyOfRange(0, 3).toList(), values.copyOfRange(3, 7).toList(), values[7] != 0f)
        }

        private fun live() = nativeHandle.also { check(it != 0L) { "Color readout is closed" } }

        override fun close() {
            if (nativeHandle != 0L) nativeDestroyReadout(nativeHandle)
            nativeHandle = 0L
        }
    }

    private external fun nativeBakeDisplayLut(
        document: Long, display: Long, size: Int, proof: Long, intent: Int, blackPointCompensation: Boolean, proofIntent: Int,
        gamutWarning: Boolean, warningR: Float, warningG: Float, warningB: Float,
    ): FloatArray?

    private external fun nativeApplyLutArgb(pixels: IntArray, count: Int, table: FloatArray, size: Int): Boolean

    private external fun nativeLastError(): String
    private external fun nativeCreateBuiltinProfile(space: Int, linear: Boolean): Long
    private external fun nativeCreateIccProfile(bytes: ByteArray): Long
    private external fun nativeCreateGenericCmykProfile(totalInkPercent: Float): Long
    private external fun nativeDestroyProfile(profile: Long)
    private external fun nativeProfileDescription(profile: Long): String
    private external fun nativeProfileModel(profile: Long): Int
    private external fun nativeProfileIccBytes(profile: Long): ByteArray
    private external fun nativeConvertTileStore(
        source: Long,
        sourceIcc: ByteArray,
        targetIcc: ByteArray,
        outputFormat: Int,
        intent: Int,
        blackPointCompensation: Boolean,
        workerThreads: Int,
    ): Long
    private external fun nativeCreateReadout(document: Long, cmyk: Long): Long
    private external fun nativeReadout(session: Long, rgb: FloatArray): FloatArray
    private external fun nativeDestroyReadout(session: Long)
}
