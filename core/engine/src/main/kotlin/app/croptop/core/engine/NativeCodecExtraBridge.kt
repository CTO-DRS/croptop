package app.croptop.core.engine

/**
 * TIFF and PSD/PSB import and export (native `codec_extra`), next to the JPEG/PNG/BMP/WebP/GIF path in
 * [NativeEngine.open]. Usage: [probe] the encoded bytes, create the document with the probed size and
 * depth, then [decode] into its primary layer's store. PSD opens flattened (the stored merged image,
 * or rebuilt from the layers when the file has none). ICC bytes remain available through
 * [decodeWithProfile], while layered PSD import holders expose them through [psdIccProfile].
 */
internal object NativeCodecExtraBridge {
    enum class Kind { TIFF, PSD }

    data class Probe(val kind: Kind, val width: Int, val height: Int, val bitDepth: Int)

    data class Decoded(val warnings: List<String>, val iccProfile: ByteArray?)

    /** Signature check only (no JNI): TIFF "II*\u0000" / "MM\u0000*" (and BigTIFF), PSD "8BPS". */
    fun detect(bytes: ByteArray, size: Int): Kind? {
        if (size < 4) return null
        val b0 = bytes[0].toInt() and 0xff
        val b1 = bytes[1].toInt() and 0xff
        val b2 = bytes[2].toInt() and 0xff
        val b3 = bytes[3].toInt() and 0xff
        val littleTiff = b0 == 'I'.code && b1 == 'I'.code && (b2 == 42 || b2 == 43) && b3 == 0
        val bigTiff = b0 == 'M'.code && b1 == 'M'.code && b2 == 0 && (b3 == 42 || b3 == 43)
        return when {
            littleTiff || bigTiff -> Kind.TIFF
            b0 == '8'.code && b1 == 'B'.code && b2 == 'P'.code && b3 == 'S'.code -> Kind.PSD
            else -> null
        }
    }

    /** Header probe; throws [IllegalArgumentException] with the native reason for malformed files. */
    fun probe(bytes: ByteArray, size: Int): Probe {
        val values = nativeProbe(bytes, size) ?: throw IllegalArgumentException(nativeLastError().ifBlank { "Unreadable TIFF/PSD header" })
        val kind = when (values[0]) {
            1 -> Kind.TIFF
            2 -> Kind.PSD
            else -> throw IllegalArgumentException("Unknown image kind")
        }
        return Probe(kind, values[1], values[2], values[3])
    }

    /** Decodes into [storeHandle]; returns non-fatal warnings (e.g. "composite rebuilt"), throws on failure. */
    fun decode(bytes: ByteArray, size: Int, storeHandle: Long): List<String> {
        val error = nativeDecode(bytes, size, storeHandle)
        check(error == null) { error ?: "TIFF/PSD decoding failed" }
        return nativeLastError().lines().filter(String::isNotBlank)
    }

    /** Decodes pixels and returns their retained ICC payload (null means the source had no profile). */
    fun decodeWithProfile(bytes: ByteArray, size: Int, storeHandle: Long): Decoded {
        val warnings = decode(bytes, size, storeHandle)
        return Decoded(warnings, nativeDecodedIccProfile())
    }

    /** Copies the ICC bytes retained by a live layered-PSD import holder (empty means untagged). */
    fun psdIccProfile(importHolder: Long): ByteArray? {
        require(importHolder != 0L) { "PSD import holder is null" }
        val profile = nativePsdIccProfile(importHolder)
        if (profile == null) {
            val error = nativePsdIccLastError()
            check(error.isBlank()) { error }
        }
        return profile?.takeUnless(ByteArray::isEmpty)
    }

    /**
     * Exports the store as TIFF (at its depth) or flattened PSD to [output]; returns the bytes written.
     * A Java IOException from [output] propagates.
     */
    fun encode(storeHandle: Long, kind: Kind, output: java.io.OutputStream, iccProfile: ByteArray? = null, deflate: Boolean = true): Long {
        val written = nativeEncode(storeHandle, if (kind == Kind.TIFF) 1 else 2, iccProfile, deflate, output)
        check(written >= 0) { nativeLastError().ifBlank { "Export failed" } }
        return written
    }

    /** One layered-PSD record in file order (bottom to top): a pixel layer, a group start or a group end. */
    data class PsdRecord(
        val kind: Int, // 0 pixel, 1 group start, 2 group end, 3 adjustment (no pixels; [store] only supplies its mask)
        val store: Long,
        val name: String,
        val blendKey: String,
        val opacity: Int,
        val fill: Int,
        val visible: Boolean,
        val clipped: Boolean,
        val passThrough: Boolean = false,
        val groupOpen: Boolean = true,
        /** Extra tagged block written into the layer record (adjustment settings), e.g. "levl". */
        val extraKey: String? = null,
        val extraData: ByteArray? = null,
    )

    /** Writes a layered PSD/PSB with [composite] as the merged image; returns the bytes written. */
    fun encodeLayered(records: List<PsdRecord>, composite: Long, output: java.io.OutputStream, iccProfile: ByteArray? = null): Long {
        val flags = BooleanArray(records.size * 4)
        records.forEachIndexed { i, r ->
            flags[i * 4] = r.visible
            flags[i * 4 + 1] = r.clipped
            flags[i * 4 + 2] = r.passThrough
            flags[i * 4 + 3] = r.groupOpen
        }
        val written = nativeEncodePsdLayered(
            records.map { it.store }.toLongArray(),
            records.map { it.kind }.toIntArray(),
            records.map { it.name }.toTypedArray(),
            records.map { it.blendKey }.toTypedArray(),
            records.map { it.opacity }.toIntArray(),
            records.map { it.fill }.toIntArray(),
            flags,
            records.map { it.extraKey }.toTypedArray(),
            records.map { it.extraData }.toTypedArray(),
            composite,
            iccProfile,
            output,
        )
        check(written >= 0) { nativeLastError().ifBlank { "Layered PSD export failed" } }
        return written
    }

    private external fun nativeLastError(): String
    private external fun nativeProbe(bytes: ByteArray, size: Int): IntArray?
    private external fun nativeDecode(bytes: ByteArray, size: Int, storeHandle: Long): String?
    private external fun nativeDecodedIccProfile(): ByteArray?
    private external fun nativePsdIccProfile(importHolder: Long): ByteArray?
    private external fun nativePsdIccLastError(): String
    private external fun nativeEncode(storeHandle: Long, kind: Int, iccProfile: ByteArray?, deflate: Boolean, output: java.io.OutputStream): Long
    private external fun nativeEncodePsdLayered(
        stores: LongArray,
        kinds: IntArray,
        names: Array<String>,
        blendKeys: Array<String>,
        opacities: IntArray,
        fills: IntArray,
        flags: BooleanArray,
        extraKeys: Array<String?>,
        extraData: Array<ByteArray?>,
        composite: Long,
        iccProfile: ByteArray?,
        output: java.io.OutputStream,
    ): Long
}
