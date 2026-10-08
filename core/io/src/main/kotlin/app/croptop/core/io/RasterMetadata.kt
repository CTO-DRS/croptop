package app.croptop.core.io

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** Bounded metadata extracted before pixel decode. ICC bytes are preserved verbatim. */
data class RasterMetadata(
    val exifOrientation: Int? = null,
    val exifBytes: ByteArray? = null,
    val iccProfile: ByteArray? = null,
    /** TIFF payload for PNG output, with Orientation reset because pixels are already oriented. */
    val exifTiffForOrientedPixels: ByteArray? = null,
    val warnings: List<String> = emptyList(),
)

/** Reads orientation and embedded ICC data without trusting marker or chunk lengths. */
object RasterMetadataReader {
    private const val MAX_EXIF_BYTES = 1024 * 1024
    private const val MAX_ICC_BYTES = 4 * 1024 * 1024
    private const val JPEG_APP1 = 0xe1
    private const val JPEG_APP2 = 0xe2

    fun read(encoded: ByteArray, length: Int = encoded.size, format: ImageFormat): RasterMetadata {
        require(length in 0..encoded.size) { "length must be within the supplied image bytes" }
        val result = MetadataBuilder()
        when (format) {
            ImageFormat.JPEG -> readJpeg(encoded, length, result)
            ImageFormat.PNG -> readPng(encoded, length, result)
            ImageFormat.WEBP -> readWebp(encoded, length, result)
            else -> return RasterMetadata()
        }
        return result.build()
    }

    private fun readJpeg(bytes: ByteArray, length: Int, result: MetadataBuilder) {
        if (length < 2 || bytes.u8(0) != 0xff || bytes.u8(1) != 0xd8) return
        var offset = 2
        val iccSegments = mutableMapOf<Int, ByteArray>()
        var iccSegmentCount: Int? = null
        while (offset < length) {
            if (bytes.u8(offset) != 0xff) {
                result.warn("JPEG metadata markers are malformed")
                break
            }
            while (offset < length && bytes.u8(offset) == 0xff) offset++
            if (offset >= length) break
            val marker = bytes.u8(offset++)
            if (marker == 0xd9 || marker == 0xda) break
            if (marker == 0x01 || marker in 0xd0..0xd7) continue
            if (offset + 2 > length) {
                result.warn("JPEG metadata segment length is truncated")
                break
            }
            val segmentLength = bytes.u16be(offset)
            if (segmentLength < 2 || segmentLength > length - offset) {
                result.warn("JPEG metadata segment exceeds the input")
                break
            }
            val dataStart = offset + 2
            val dataLength = segmentLength - 2
            val dataEnd = dataStart + dataLength
            when (marker) {
                JPEG_APP1 -> if (bytes.matches(dataStart, dataEnd, EXIF_ID)) {
                    parseExif(bytes, dataStart + EXIF_ID.size, dataEnd, result, dataStart, dataEnd)
                }

                JPEG_APP2 -> if (bytes.matches(dataStart, dataEnd, ICC_ID) && dataLength >= ICC_ID.size + 2) {
                    val sequence = bytes.u8(dataStart + ICC_ID.size)
                    val count = bytes.u8(dataStart + ICC_ID.size + 1)
                    val payloadStart = dataStart + ICC_ID.size + 2
                    if (sequence == 0 || count == 0 || sequence > count || iccSegmentCount?.let { it != count } == true ||
                        iccSegments.containsKey(sequence)
                    ) {
                        result.warn("JPEG ICC profile segments are inconsistent")
                    } else if (dataEnd - payloadStart > MAX_ICC_BYTES ||
                        iccSegments.values.sumOf(ByteArray::size) > MAX_ICC_BYTES - (dataEnd - payloadStart)
                    ) {
                        result.warn("JPEG ICC profile exceeds the 4 MiB limit")
                    } else {
                        iccSegmentCount = count
                        iccSegments[sequence] = bytes.copyOfRange(payloadStart, dataEnd)
                    }
                }
            }
            offset += segmentLength
        }
        val count = iccSegmentCount
        if (count != null) {
            if (iccSegments.size != count || (1..count).any { it !in iccSegments }) {
                result.warn("JPEG ICC profile is incomplete")
            } else {
                val profile = ByteArray(iccSegments.values.sumOf(ByteArray::size)).also { profile ->
                    var targetOffset = 0
                    for (sequence in 1..count) {
                        val part = iccSegments.getValue(sequence)
                        part.copyInto(profile, targetOffset)
                        targetOffset += part.size
                    }
                }
                if (profile.isValidIccProfile()) result.iccProfile = profile else result.warn("JPEG ICC profile header is invalid")
            }
        }
    }

    private fun readPng(bytes: ByteArray, length: Int, result: MetadataBuilder) {
        if (length < PNG_SIGNATURE.size || !bytes.matches(0, PNG_SIGNATURE.size, PNG_SIGNATURE)) return
        var offset = PNG_SIGNATURE.size
        while (offset + 12 <= length) {
            val chunkLength = bytes.u32be(offset)
            if (chunkLength > Int.MAX_VALUE || chunkLength > length - offset - 12L) {
                result.warn("PNG metadata chunk exceeds the input")
                return
            }
            val dataLength = chunkLength.toInt()
            val dataStart = offset + 8
            val dataEnd = dataStart + dataLength
            when (bytes.ascii(dataStart - 4, 4)) {
                "eXIf" -> parseExif(bytes, dataStart, dataEnd, result, dataStart, dataEnd)

                "iCCP" -> {
                    val profile = parsePngIcc(bytes, dataStart, dataEnd, result)
                    if (profile != null) result.iccProfile = profile
                }

                "IEND" -> return
            }
            offset = dataEnd + 4 // Skip CRC; pixel decode validates chunk checksums.
        }
        if (offset != length && offset + 12 > length) result.warn("PNG metadata chunk header is truncated")
    }

    /** Reads bounded ICCP and EXIF RIFF chunks without trusting the declared RIFF size. */
    private fun readWebp(bytes: ByteArray, length: Int, result: MetadataBuilder) {
        if (length < 12 || bytes.ascii(0, 4) != "RIFF" || bytes.ascii(8, 4) != "WEBP") return
        val riffEnd = minOf(length.toLong(), 8L + bytes.u32le(4)).toInt()
        var offset = 12
        while (offset + 8 <= riffEnd) {
            val chunkLength = bytes.u32le(offset + 4)
            if (chunkLength > Int.MAX_VALUE || chunkLength > riffEnd - offset - 8L) {
                result.warn("WebP metadata chunk exceeds the RIFF input")
                return
            }
            val start = offset + 8
            val end = start + chunkLength.toInt()
            when (bytes.ascii(offset, 4)) {
                "ICCP" -> {
                    if (chunkLength > MAX_ICC_BYTES) {
                        result.warn("WebP ICC profile exceeds the 4 MiB limit")
                    } else {
                        val profile = bytes.copyOfRange(start, end)
                        if (profile.isValidIccProfile()) {
                            result.iccProfile = profile
                        } else {
                            result.warn("WebP ICC profile header is invalid")
                        }
                    }
                }

                "EXIF" -> {
                    val tiffStart = if (bytes.matches(start, end, EXIF_ID)) start + EXIF_ID.size else start
                    parseExif(bytes, tiffStart, end, result, start, end)
                }
            }
            offset = end + (chunkLength.toInt() and 1)
        }
        if (offset < riffEnd && riffEnd - offset < 8) result.warn("WebP metadata chunk header is truncated")
    }

    private fun parsePngIcc(bytes: ByteArray, start: Int, end: Int, result: MetadataBuilder): ByteArray? {
        var terminator = start
        while (terminator < end && terminator - start <= 79 && bytes[terminator] != 0.toByte()) terminator++
        if (terminator >= end || terminator - start > 79 || terminator + 2 > end || bytes.u8(terminator + 1) != 0) {
            result.warn("PNG ICC profile header is malformed")
            return null
        }
        val compressedStart = terminator + 2
        val inflater = Inflater()
        try {
            inflater.setInput(bytes, compressedStart, end - compressedStart)
            val output = ByteArrayOutputStream(minOf(MAX_ICC_BYTES, 16 * 1024))
            val buffer = ByteArray(16 * 1024)
            while (!inflater.finished()) {
                val count = try {
                    inflater.inflate(buffer)
                } catch (_: DataFormatException) {
                    result.warn("PNG ICC profile compression is invalid")
                    return null
                }
                if (count > MAX_ICC_BYTES - output.size()) {
                    result.warn("PNG ICC profile exceeds the 4 MiB limit")
                    return null
                }
                if (count > 0) {
                    output.write(buffer, 0, count)
                } else if (inflater.needsDictionary() || inflater.needsInput()) {
                    result.warn("PNG ICC profile stream is incomplete")
                    return null
                }
            }
            val profile = output.toByteArray()
            if (profile.isValidIccProfile()) return profile
            result.warn("PNG ICC profile header is invalid")
            return null
        } finally {
            inflater.end()
        }
    }

    private fun parseExif(bytes: ByteArray, start: Int, end: Int, result: MetadataBuilder, rawStart: Int, rawEnd: Int) {
        try {
            val parsed = findExifOrientation(bytes, start, end)
            if (!result.exifSeen) {
                result.exifSeen = true
                if (rawEnd - rawStart > MAX_EXIF_BYTES) {
                    result.warn("EXIF block exceeds the 1 MiB preservation limit")
                } else {
                    result.exifBytes = bytes.copyOfRange(rawStart, rawEnd)
                    result.exifTiffForOrientedPixels = bytes.copyOfRange(start, end).also { tiff ->
                        parsed.orientationValueOffset?.let { valueOffset ->
                            val normalizedOffset = valueOffset - start
                            if (parsed.littleEndian) {
                                tiff[normalizedOffset] = 1
                                tiff[normalizedOffset + 1] = 0
                            } else {
                                tiff[normalizedOffset] = 0
                                tiff[normalizedOffset + 1] = 1
                            }
                            tiff[normalizedOffset + 2] = 0
                            tiff[normalizedOffset + 3] = 0
                        }
                    }
                }
            }
            if (parsed.orientation != null && result.exifOrientation == null) {
                result.exifOrientation = parsed.orientation
            }
        } catch (_: IllegalArgumentException) {
            result.warn("EXIF orientation metadata is malformed")
        }
    }

    private data class ParsedExif(val orientation: Int?, val orientationValueOffset: Int?, val littleEndian: Boolean)

    private fun findExifOrientation(bytes: ByteArray, start: Int, end: Int): ParsedExif {
        if (end - start < 8) throw IllegalArgumentException()
        val littleEndian = when {
            bytes.u8(start) == 'I'.code && bytes.u8(start + 1) == 'I'.code -> true
            bytes.u8(start) == 'M'.code && bytes.u8(start + 1) == 'M'.code -> false
            else -> throw IllegalArgumentException()
        }
        fun read16(offset: Int): Int {
            require(offset >= start && offset + 2 <= end)
            return if (littleEndian) {
                bytes.u8(offset) or (bytes.u8(offset + 1) shl 8)
            } else {
                (bytes.u8(offset) shl 8) or bytes.u8(offset + 1)
            }
        }
        fun read32(offset: Int): Long {
            require(offset >= start && offset + 4 <= end)
            return if (littleEndian) {
                bytes.u8(offset).toLong() or (bytes.u8(offset + 1).toLong() shl 8) or
                    (bytes.u8(offset + 2).toLong() shl 16) or (bytes.u8(offset + 3).toLong() shl 24)
            } else {
                (bytes.u8(offset).toLong() shl 24) or (bytes.u8(offset + 1).toLong() shl 16) or
                    (bytes.u8(offset + 2).toLong() shl 8) or bytes.u8(offset + 3).toLong()
            }
        }
        if (read16(start + 2) != 42) throw IllegalArgumentException()
        val ifdOffset = read32(start + 4)
        if (ifdOffset > end - start - 2L) throw IllegalArgumentException()
        val ifdStart = start + ifdOffset.toInt()
        val entryCount = read16(ifdStart)
        if (entryCount > (end - ifdStart - 2) / 12) throw IllegalArgumentException()
        repeat(entryCount) { index ->
            val entry = ifdStart + 2 + index * 12
            if (read16(entry) == 0x0112) {
                if (read16(entry + 2) != 3 || read32(entry + 4) != 1L) throw IllegalArgumentException()
                val orientation = read16(entry + 8)
                if (orientation !in 1..8) throw IllegalArgumentException()
                return ParsedExif(orientation, entry + 8, littleEndian)
            }
        }
        return ParsedExif(null, null, littleEndian)
    }

    private class MetadataBuilder {
        var exifOrientation: Int? = null
        var exifBytes: ByteArray? = null
        var exifSeen = false
        var iccProfile: ByteArray? = null
        var exifTiffForOrientedPixels: ByteArray? = null
        private val warnings = mutableListOf<String>()

        fun warn(message: String) {
            if (message !in warnings) warnings += message
        }

        fun build() = RasterMetadata(exifOrientation, exifBytes, iccProfile, exifTiffForOrientedPixels, warnings.toList())
    }

    private fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and 0xff

    private fun ByteArray.u16be(offset: Int): Int = (u8(offset) shl 8) or u8(offset + 1)

    private fun ByteArray.u32be(offset: Int): Long =
        (u8(offset).toLong() shl 24) or (u8(offset + 1).toLong() shl 16) or
            (u8(offset + 2).toLong() shl 8) or u8(offset + 3).toLong()

    private fun ByteArray.u32le(offset: Int): Long =
        u8(offset).toLong() or (u8(offset + 1).toLong() shl 8) or
            (u8(offset + 2).toLong() shl 16) or (u8(offset + 3).toLong() shl 24)

    private fun ByteArray.matches(start: Int, end: Int, signature: ByteArray): Boolean =
        end - start >= signature.size && signature.indices.all { this[start + it] == signature[it] }

    private fun ByteArray.isValidIccProfile(): Boolean =
        size >= 132 && u32be(0) == size.toLong() && matches(36, 40, ICC_SIGNATURE)

    private fun ByteArray.ascii(start: Int, length: Int): String = String(this, start, length, Charsets.US_ASCII)

    private val EXIF_ID = byteArrayOf('E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0)
    private val ICC_ID = byteArrayOf(
        'I'.code.toByte(), 'C'.code.toByte(), 'C'.code.toByte(), '_'.code.toByte(),
        'P'.code.toByte(), 'R'.code.toByte(), 'O'.code.toByte(), 'F'.code.toByte(),
        'I'.code.toByte(), 'L'.code.toByte(), 'E'.code.toByte(), 0,
    )
    private val ICC_SIGNATURE = "acsp".encodeToByteArray()
    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
}
