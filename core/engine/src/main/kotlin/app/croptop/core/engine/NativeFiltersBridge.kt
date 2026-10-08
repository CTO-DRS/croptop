package app.croptop.core.engine

/**
 * The Filter menu (native `filters_blur`/`filters_noise`/`filters_distort`): every call returns a *new* native store (a copy-on-write
 * clone with the filter applied), so the source layer is untouched and a history step can keep both. The caller owns the returned handle
 * (`NativeSelectionBridge.destroyStore`). [mask] is an optional A16 selection/layer-mask store limiting the effect.
 *
 * Ids and parameter order are fixed by `filter_dispatch.h`; [Kind] mirrors them and [Kind.parameters] is the expected count.
 */
internal object NativeFiltersBridge {
    enum class Kind(val id: Int, val parameters: Int) {
        GAUSSIAN_BLUR(0, 1),
        BOX_BLUR(1, 1),
        UNSHARP_MASK(2, 3),
        HIGH_PASS(3, 1),
        MOTION_BLUR(4, 2),
        RADIAL_BLUR(5, 4),
        SURFACE_BLUR(6, 2),
        TILT_SHIFT(7, 6),
        SMART_SHARPEN(8, 4),
        TWIRL(9, 1),
        PINCH(10, 1),
        SPHERIZE(11, 1),
        WAVE(12, 4),
        RIPPLE(13, 2),
        POLAR_COORDINATES(14, 1),
        DISPLACE(15, 3),
        EMBOSS(16, 3),
        FIND_EDGES(17, 0),
        GLOWING_EDGES(18, 3),
        OIL_PAINT(19, 2),
        DIFFUSE_GLOW(20, 3),
        VIGNETTE(21, 3),
        CLOUDS(22, 8),
        CHROMATIC_ABERRATION(23, 2),
        ADD_NOISE(24, 3),
        FILM_GRAIN(25, 2),
        MEDIAN(26, 1),
        DUST_AND_SCRATCHES(27, 2),
        REDUCE_NOISE(28, 2),
        MOSAIC(29, 1),
        CRYSTALLIZE(30, 1),
        COLOR_HALFTONE(31, 4),
        LENS_FLARE(32, 4),
    }

    /** Applies [kind] to [storeHandle]; [map] is only used by [Kind.DISPLACE]. Throws with the native reason on invalid parameters. */
    fun apply(
        storeHandle: Long,
        kind: Kind,
        vararg parameters: Float,
        maskHandle: Long = 0L,
        mapHandle: Long = 0L,
        seed: Int = 0,
        workerThreads: Int = 0,
    ): Long {
        require(parameters.size == kind.parameters) { "${kind.name} takes ${kind.parameters} parameters, got ${parameters.size}" }
        val handle = nativeApply(storeHandle, kind.id, parameters, maskHandle, mapHandle, seed, workerThreads)
        check(handle != 0L) { nativeLastError().ifBlank { "${kind.name} failed" } }
        return handle
    }

    fun gaussianBlur(store: Long, radius: Float, mask: Long = 0L) = apply(store, Kind.GAUSSIAN_BLUR, radius, maskHandle = mask)
    fun boxBlur(store: Long, radius: Int, mask: Long = 0L) = apply(store, Kind.BOX_BLUR, radius.toFloat(), maskHandle = mask)
    fun unsharpMask(
        store: Long,
        amount: Float,
        radius: Float,
        threshold: Float,
        mask: Long = 0L,
    ) = apply(store, Kind.UNSHARP_MASK, amount, radius, threshold, maskHandle = mask)
    fun motionBlur(store: Long, angle: Float, distance: Float, mask: Long = 0L) = apply(store, Kind.MOTION_BLUR, angle, distance, maskHandle = mask)
    fun median(store: Long, radius: Int, mask: Long = 0L) = apply(store, Kind.MEDIAN, radius.toFloat(), maskHandle = mask)
    fun addNoise(store: Long, amount: Float, gaussian: Boolean, monochromatic: Boolean, seed: Int, mask: Long = 0L) =
        apply(store, Kind.ADD_NOISE, amount, if (gaussian) 1f else 0f, if (monochromatic) 1f else 0f, seed = seed, maskHandle = mask)
    fun vignette(
        store: Long,
        amount: Float,
        midpoint: Float,
        feather: Float,
        mask: Long = 0L,
    ) = apply(store, Kind.VIGNETTE, amount, midpoint, feather, maskHandle = mask)

    /** Native parameter count for [id] (-1 when unknown); used by tests to keep [Kind] in step with the C++ table. */
    fun nativeCount(id: Int): Int = nativeParameterCount(id)

    private external fun nativeLastError(): String
    private external fun nativeParameterCount(id: Int): Int
    private external fun nativeApply(
        storeHandle: Long,
        id: Int,
        params: FloatArray,
        maskHandle: Long,
        mapHandle: Long,
        seed: Int,
        workerThreads: Int,
    ): Long
}
