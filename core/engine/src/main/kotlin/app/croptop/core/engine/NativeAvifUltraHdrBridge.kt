package app.croptop.core.engine

import java.io.OutputStream

/**
 * AVIF import/export and Ultra HDR JPEG export (native `avif_codec`, `ultra_hdr`). Stores are native handles as elsewhere
 * (`NativeSelectionBridge.destroyStore` releases a handle this object returns).
 *
 * Ultra HDR takes a scene-linear RGBA32F store in linear sRGB primaries where 1.0 is SDR white; values above 1.0 become the HDR
 * headroom carried by the gain map. Pass an RGBA8 [sdrStoreHandle] to control exactly what SDR viewers see; 0 lets the native
 * tone mapper derive it.
 */
internal object NativeAvifUltraHdrBridge {
    data class AvifInfo(val width: Int, val height: Int, val bitDepth: Int, val hasAlpha: Boolean)

    class AvifImage(val storeHandle: Long, val iccProfile: ByteArray)

    /** Signature check (ISO-BMFF `ftyp` with an avif/avis brand). */
    fun isAvif(bytes: ByteArray, size: Int): Boolean = nativeIsAvif(bytes, size)

    /** True for a JPEG carrying an Ultra HDR gain map. */
    fun isUltraHdr(bytes: ByteArray, size: Int): Boolean = nativeIsUltraHdr(bytes, size)

    fun probeAvif(bytes: ByteArray, size: Int): AvifInfo {
        val v = nativeAvifProbe(bytes, size) ?: throw IllegalArgumentException(nativeLastError().ifBlank { "Unreadable AVIF file" })
        return AvifInfo(v[0], v[1], v[2], v[3] != 0)
    }

    /** RGBA8 store for 8-bit files, RGBA16 for 10/12-bit files; the caller owns the handle. */
    fun decodeAvif(bytes: ByteArray, size: Int): AvifImage {
        val handle = nativeAvifDecode(bytes, size)
        check(handle != 0L) { nativeLastError().ifBlank { "AVIF decoding failed" } }
        return AvifImage(handle, nativeTakeIcc())
    }

    /** [quality] 0-100 (100 = lossless), [speed] 0-10, [bitDepth] 8/10/12. Returns the bytes written to [output]. */
    fun encodeAvif(
        storeHandle: Long,
        output: OutputStream,
        quality: Int = 80,
        speed: Int = 6,
        bitDepth: Int = 8,
        iccProfile: ByteArray? = null,
        workerThreads: Int = 0,
    ): Long {
        val written = nativeAvifEncode(storeHandle, quality, speed, bitDepth, iccProfile, workerThreads, output)
        check(written >= 0) { nativeLastError().ifBlank { "AVIF export failed" } }
        return written
    }

    /** Linear RGBA32F store (1.0 = SDR white) with the gain map applied; the caller owns the handle. */
    fun decodeUltraHdr(bytes: ByteArray, size: Int): Long {
        val handle = nativeUltraHdrDecode(bytes, size)
        check(handle != 0L) { nativeLastError().ifBlank { "Ultra HDR decoding failed" } }
        return handle
    }

    fun encodeUltraHdr(
        hdrStoreHandle: Long,
        output: OutputStream,
        sdrStoreHandle: Long = 0L,
        baseQuality: Int = 90,
        gainMapQuality: Int = 85,
        multiChannelGainMap: Boolean = false,
        targetPeakNits: Float = 1000f,
        exif: ByteArray? = null,
    ): Long {
        val written =
            nativeUltraHdrEncode(hdrStoreHandle, sdrStoreHandle, baseQuality, gainMapQuality, multiChannelGainMap, targetPeakNits, exif, output)
        check(written >= 0) { nativeLastError().ifBlank { "Ultra HDR export failed" } }
        return written
    }

    private external fun nativeLastError(): String
    private external fun nativeIsAvif(bytes: ByteArray, size: Int): Boolean
    private external fun nativeIsUltraHdr(bytes: ByteArray, size: Int): Boolean
    private external fun nativeUltraHdrDecode(bytes: ByteArray, size: Int): Long
    private external fun nativeAvifProbe(bytes: ByteArray, size: Int): IntArray?
    private external fun nativeAvifDecode(bytes: ByteArray, size: Int): Long
    private external fun nativeTakeIcc(): ByteArray
    private external fun nativeAvifEncode(
        storeHandle: Long,
        quality: Int,
        speed: Int,
        bitDepth: Int,
        iccProfile: ByteArray?,
        workerThreads: Int,
        output: OutputStream,
    ): Long
    private external fun nativeUltraHdrEncode(
        hdrHandle: Long,
        sdrHandle: Long,
        baseQuality: Int,
        gainMapQuality: Int,
        multiChannel: Boolean,
        peakNits: Float,
        exif: ByteArray?,
        output: OutputStream,
    ): Long
}
