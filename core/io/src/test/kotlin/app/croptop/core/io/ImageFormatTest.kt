package app.croptop.core.io

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ImageFormatTest {
    @Test
    fun detectsRasterSignaturesWithoutFilenames() {
        assertEquals(ImageFormat.JPEG, ImageFormat.detect(bytes(0xff, 0xd8, 0xff, 0xe0)))
        assertEquals(ImageFormat.PNG, ImageFormat.detect(bytes(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)))
        assertEquals(ImageFormat.WEBP, ImageFormat.detect(ascii("RIFF", "0000", "WEBP")))
        assertEquals(ImageFormat.GIF, ImageFormat.detect(ascii("GIF89a")))
        assertEquals(ImageFormat.BMP, ImageFormat.detect(ascii("BM")))
    }

    @Test
    fun detectsHeifAndAvifMajorOrCompatibleBrand() {
        assertEquals(ImageFormat.HEIF, ImageFormat.detect(isobmff("heic")))
        assertEquals(ImageFormat.HEIF, ImageFormat.detect(isobmff("mif1", "heix")))
        assertEquals(ImageFormat.AVIF, ImageFormat.detect(isobmff("avif")))
        assertEquals(ImageFormat.AVIF, ImageFormat.detect(isobmff("mif1", "avis")))
    }

    @Test
    fun rejectsTruncatedOrUnrecognizedHeadersAndInvalidLength() {
        assertEquals(ImageFormat.UNKNOWN, ImageFormat.detect(ascii("RIFF0000WEBP"), 11))
        assertEquals(ImageFormat.UNKNOWN, ImageFormat.detect(ascii("ftypheic")))
        assertEquals(ImageFormat.UNKNOWN, ImageFormat.detect(byteArrayOf(0, 1, 2, 3)))
        assertFailsWith<IllegalArgumentException> { ImageFormat.detect(byteArrayOf(), 1) }
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun ascii(vararg parts: String) = parts.joinToString("").encodeToByteArray()

    private fun isobmff(vararg brands: String): ByteArray =
        ascii("\u0000\u0000\u0000\u0018", "ftyp", brands.first(), "\u0000\u0000\u0000\u0000", *brands.drop(1).toTypedArray())
}
