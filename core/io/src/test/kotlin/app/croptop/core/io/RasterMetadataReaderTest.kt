package app.croptop.core.io

import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RasterMetadataReaderTest {
    @Test
    fun readsJpegExifOrientationAndReordersSplitIccSegments() {
        val profile = validIccProfile()
        val jpeg = jpeg(
            app1(exif(orientation = 6, littleEndian = false)),
            app2(2, 2, profile.copyOfRange(66, profile.size)),
            app2(1, 2, profile.copyOfRange(0, 66)),
        )

        val metadata = RasterMetadataReader.read(jpeg, format = ImageFormat.JPEG)

        assertEquals(6, metadata.exifOrientation)
        assertTrue(metadata.exifBytes?.isNotEmpty() == true)
        assertEquals(1, metadata.exifTiffForOrientedPixels?.get(19)?.toInt()?.and(0xff))
        assertContentEquals(profile, metadata.iccProfile)
        assertTrue(metadata.warnings.isEmpty())
    }

    @Test
    fun readsPngExifAndInflatesBoundedIccProfile() {
        val profile = validIccProfile()
        val png = png(
            chunk("eXIf", exif(orientation = 8, littleEndian = true)),
            chunk("iCCP", "Display P3".encodeToByteArray() + byteArrayOf(0, 0) + deflate(profile)),
            chunk("IEND", byteArrayOf()),
        )

        val metadata = RasterMetadataReader.read(png, format = ImageFormat.PNG)

        assertEquals(8, metadata.exifOrientation)
        assertTrue(metadata.exifBytes?.isNotEmpty() == true)
        assertEquals(1, metadata.exifTiffForOrientedPixels?.get(18)?.toInt()?.and(0xff))
        assertContentEquals(profile, metadata.iccProfile)
        assertTrue(metadata.warnings.isEmpty())
    }

    @Test
    fun readsWebpExifOrientationAndIccpFromRiffChunks() {
        val profile = validIccProfile()
        val metadata = RasterMetadataReader.read(
            webp(
                riffChunk("ICCP", profile),
                riffChunk("EXIF", "Exif\u0000\u0000".encodeToByteArray() + exif(orientation = 6, littleEndian = false)),
            ),
            format = ImageFormat.WEBP,
        )
        assertEquals(6, metadata.exifOrientation)
        assertContentEquals(profile, metadata.iccProfile)
        assertEquals(1, metadata.exifTiffForOrientedPixels?.get(19)?.toInt()?.and(0xff))
        assertTrue(metadata.warnings.isEmpty())
    }

    @Test
    fun malformedOrOversizedMetadataProducesWarningsWithoutThrowing() {
        val truncatedJpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe1.toByte(), 0, 12, 1)
        val jpegMetadata = RasterMetadataReader.read(truncatedJpeg, format = ImageFormat.JPEG)
        assertTrue(jpegMetadata.warnings.isNotEmpty())
        assertNull(jpegMetadata.iccProfile)

        val malformedExif = jpeg(app1(ByteArray(12)))
        val malformedExifMetadata = RasterMetadataReader.read(malformedExif, format = ImageFormat.JPEG)
        assertTrue(malformedExifMetadata.warnings.any { "EXIF orientation" in it })

        val oversizedExif = png(
            chunk("eXIf", exif(orientation = 6, littleEndian = true) + ByteArray(1024 * 1024)),
            chunk("IEND", byteArrayOf()),
        )
        val oversizedExifMetadata = RasterMetadataReader.read(oversizedExif, format = ImageFormat.PNG)
        assertEquals(6, oversizedExifMetadata.exifOrientation)
        assertNull(oversizedExifMetadata.exifBytes)
        assertTrue(oversizedExifMetadata.warnings.any { "1 MiB" in it })

        val oversizedProfile = ByteArray(4 * 1024 * 1024 + 1)
        val png = png(
            chunk("iCCP", "oversize".encodeToByteArray() + byteArrayOf(0, 0) + deflate(oversizedProfile)),
            chunk("IEND", byteArrayOf()),
        )
        val pngMetadata = RasterMetadataReader.read(png, format = ImageFormat.PNG)
        assertNull(pngMetadata.iccProfile)
        assertTrue(pngMetadata.warnings.any { "4 MiB" in it })

        val invalidProfilePng = png(
            chunk("iCCP", "invalid".encodeToByteArray() + byteArrayOf(0, 0) + deflate(byteArrayOf(1, 2, 3))),
            chunk("IEND", byteArrayOf()),
        )
        val invalidProfileMetadata = RasterMetadataReader.read(invalidProfilePng, format = ImageFormat.PNG)
        assertNull(invalidProfileMetadata.iccProfile)
        assertTrue(invalidProfileMetadata.warnings.any { "header is invalid" in it })
    }

    @Test
    fun respectsEncodedLengthAndDoesNotParseUnknownFormats() {
        val jpeg = jpeg(app1(exif(orientation = 3, littleEndian = true)))
        val metadata = RasterMetadataReader.read(jpeg, length = 2, format = ImageFormat.JPEG)
        assertNull(metadata.exifOrientation)
        assertEquals(RasterMetadata(), RasterMetadataReader.read(jpeg, format = ImageFormat.GIF))
    }

    private fun exif(orientation: Int, littleEndian: Boolean): ByteArray {
        val bytes = ByteArray(26)
        if (littleEndian) {
            bytes[0] = 'I'.code.toByte()
            bytes[1] = 'I'.code.toByte()
            bytes[2] = 42
            bytes[4] = 8
            bytes[8] = 1
            bytes[10] = 0x12
            bytes[11] = 0x01
            bytes[12] = 3
            bytes[14] = 1
            bytes[18] = orientation.toByte()
        } else {
            bytes[0] = 'M'.code.toByte()
            bytes[1] = 'M'.code.toByte()
            bytes[3] = 42
            bytes[7] = 8
            bytes[9] = 1
            bytes[10] = 0x01
            bytes[11] = 0x12
            bytes[13] = 3
            bytes[17] = 1
            bytes[19] = orientation.toByte()
        }
        return bytes
    }

    private fun validIccProfile(): ByteArray = ByteArray(132).apply {
        this[3] = 132.toByte()
        this[16] = 'R'.code.toByte()
        this[17] = 'G'.code.toByte()
        this[18] = 'B'.code.toByte()
        this[20] = 'X'.code.toByte()
        this[21] = 'Y'.code.toByte()
        this[22] = 'Z'.code.toByte()
        this[36] = 'a'.code.toByte()
        this[37] = 'c'.code.toByte()
        this[38] = 's'.code.toByte()
        this[39] = 'p'.code.toByte()
    }

    private fun jpeg(vararg segments: ByteArray): ByteArray = byteArrayOf(0xff.toByte(), 0xd8.toByte()) +
        segments.fold(ByteArray(0)) { acc, segment -> acc + segment } + byteArrayOf(0xff.toByte(), 0xd9.toByte())

    private fun app1(payload: ByteArray) = segment(0xe1, "Exif\u0000\u0000".encodeToByteArray() + payload)

    private fun app2(sequence: Int, count: Int, payload: ByteArray) =
        segment(0xe2, "ICC_PROFILE\u0000".encodeToByteArray() + byteArrayOf(sequence.toByte(), count.toByte()) + payload)

    private fun segment(marker: Int, payload: ByteArray): ByteArray {
        val length = payload.size + 2
        return byteArrayOf(0xff.toByte(), marker.toByte(), (length shr 8).toByte(), length.toByte()) + payload
    }

    private fun png(vararg chunks: ByteArray): ByteArray = byteArrayOf(
        0x89.toByte(),
        0x50,
        0x4e,
        0x47,
        0x0d,
        0x0a,
        0x1a,
        0x0a,
    ) + chunks.fold(ByteArray(0)) { acc, chunk -> acc + chunk }

    private fun webp(vararg chunks: ByteArray): ByteArray {
        val body = "WEBP".encodeToByteArray() + chunks.fold(ByteArray(0)) { acc, chunk -> acc + chunk }
        return "RIFF".encodeToByteArray() + le32(body.size) + body
    }

    private fun riffChunk(type: String, data: ByteArray): ByteArray =
        type.encodeToByteArray() + le32(data.size) + data + if (data.size % 2 == 0) ByteArray(0) else byteArrayOf(0)

    private fun le32(value: Int): ByteArray = byteArrayOf(
        value.toByte(),
        (value ushr 8).toByte(),
        (value ushr 16).toByte(),
        (value ushr 24).toByte(),
    )

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val length = data.size
        return byteArrayOf(
            (length ushr 24).toByte(),
            (length ushr 16).toByte(),
            (length ushr 8).toByte(),
            length.toByte(),
        ) + type.encodeToByteArray() + data + ByteArray(4)
    }

    private fun deflate(data: ByteArray): ByteArray = ByteArrayOutputStream().use { output ->
        DeflaterOutputStream(output).use { it.write(data) }
        output.toByteArray()
    }
}
