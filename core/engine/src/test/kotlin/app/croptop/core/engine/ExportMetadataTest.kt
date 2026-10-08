package app.croptop.core.engine

import app.croptop.core.engine.api.ExportFormat
import app.croptop.core.engine.api.IptcMetadata
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExportMetadataTest {
    private val meta = IptcMetadata("Sunset <over> the bay", "A long, quiet evening & a very orange sky.", "Ada Lovelace", "© 2026 Ada", listOf("sunset", "bay", "sunset", " "))

    private fun jpeg(withJfif: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        if (withJfif) out.write(byteArrayOf(0xFF.toByte(), 0xE0.toByte(), 0, 16, 'J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 0, 1, 1, 0, 0, 1, 0, 1, 0, 0))
        out.write(byteArrayOf(0xFF.toByte(), 0xE1.toByte(), 0, 8, 'E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0))
        out.write(byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 4, 1, 2, 9, 9, 9, 0xFF.toByte(), 0xD9.toByte()))
        return out.toByteArray()
    }

    /** (marker, payload) of every segment up to SOS. */
    private fun segments(bytes: ByteArray): List<Pair<Int, ByteArray>> {
        val out = mutableListOf<Pair<Int, ByteArray>>()
        var i = 2
        while (i + 4 <= bytes.size && bytes[i] == 0xFF.toByte()) {
            val marker = bytes[i + 1].toInt() and 0xFF
            val length = ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF)
            out += marker to bytes.copyOfRange(i + 4, i + 2 + length)
            if (marker == 0xDA) break
            i += 2 + length
        }
        return out
    }

    private fun run(format: ExportFormat, source: ByteArray, extra: ByteArray, chunk: Int): Pair<ByteArray, MetadataInjectingStream> {
        val sink = ByteArrayOutputStream()
        val stream = MetadataInjectingStream(sink, format, extra)
        var at = 0
        while (at < source.size) {
            val n = minOf(chunk, source.size - at)
            stream.write(source, at, n)
            at += n
        }
        stream.finish()
        return sink.toByteArray() to stream
    }

    /** IPTC-IIM records as (dataset, text). */
    private fun records(iim: ByteArray): List<Pair<Int, String>> {
        val out = mutableListOf<Pair<Int, String>>()
        var i = 0
        while (i + 5 <= iim.size) {
            assertEquals(0x1C, iim[i].toInt())
            val record = iim[i + 1].toInt()
            val dataset = iim[i + 2].toInt() and 0xFF
            val length = ((iim[i + 3].toInt() and 0xFF) shl 8) or (iim[i + 4].toInt() and 0xFF)
            if (record == 2) out += dataset to String(iim, i + 5, length, Charsets.UTF_8)
            i += 5 + length
        }
        return out
    }

    @Test
    fun `the IIM block carries the fields in UTF-8 with duplicates and blanks dropped`() {
        val records = records(ExportMetadata.iim(meta))
        assertEquals("Sunset <over> the bay", records.single { it.first == 5 }.second)
        assertEquals(listOf("sunset", "bay"), records.filter { it.first == 25 }.map { it.second })
        assertEquals("Ada Lovelace", records.single { it.first == 80 }.second)
        assertEquals("© 2026 Ada", records.single { it.first == 116 }.second)
        assertTrue(records.single { it.first == 120 }.second.startsWith("A long, quiet evening"))
    }

    @Test
    fun `long text is cut to the IIM limit without splitting a character`() {
        val records = records(ExportMetadata.iim(IptcMetadata(creator = "é".repeat(40), caption = "x".repeat(3000))))
        val creator = records.single { it.first == 80 }.second
        assertEquals(32, creator.toByteArray().size)
        assertTrue(creator.all { it == 'é' })
        assertEquals(2000, records.single { it.first == 120 }.second.length)
    }

    @Test
    fun `xmp holds the full escaped text`() {
        val xmp = String(ExportMetadata.xmp(meta), Charsets.UTF_8)
        assertTrue("Sunset &lt;over&gt; the bay" in xmp)
        assertTrue("a very orange sky" in xmp && "&amp; a very" in xmp)
        assertTrue("<dc:creator><rdf:Seq><rdf:li>Ada Lovelace</rdf:li>" in xmp)
        assertEquals(2, Regex("<rdf:li>(sunset|bay)</rdf:li>").findAll(xmp).count())
    }

    @Test
    fun `a JPEG gets the photoshop and xmp segments right after its JFIF header whatever the write sizes`() {
        val extra = ExportMetadata.jpegSegments(meta)!!
        for (withJfif in listOf(true, false)) {
            val source = jpeg(withJfif)
            for (chunk in listOf(1, 3, 7, source.size)) {
                val (result, stream) = run(ExportFormat.JPEG, source, extra, chunk)
                assertEquals(source.size + extra.size, result.size, "withJfif=$withJfif chunk=$chunk")
                assertEquals(extra.size.toLong(), stream.injectedBytes)
                val markers = segments(result).map { it.first }
                assertEquals(if (withJfif) listOf(0xE0, 0xED, 0xE1, 0xE1, 0xDA) else listOf(0xED, 0xE1, 0xE1, 0xDA), markers)
                // Everything that was there is still there, in order.
                assertContentEquals(source.copyOfRange(0, if (withJfif) 20 else 2), result.copyOfRange(0, if (withJfif) 20 else 2))
                assertContentEquals(source.copyOfRange(if (withJfif) 20 else 2, source.size), result.copyOfRange(result.size - (source.size - if (withJfif) 20 else 2), result.size))
            }
        }
        val photoshop = segments(run(ExportFormat.JPEG, jpeg(true), extra, 5).first).single { it.first == 0xED }.second
        assertTrue(String(photoshop, 0, 14, Charsets.ISO_8859_1) == "Photoshop 3.0\u0000" && String(photoshop, 14, 4, Charsets.ISO_8859_1) == "8BIM")
        assertEquals(0x04, photoshop[18].toInt())
        assertEquals(0x04, photoshop[19].toInt())
    }

    @Test
    fun `a PNG gets an iTXt xmp chunk after IHDR with a valid CRC`() {
        val ihdrData = byteArrayOf(0, 0, 0, 4, 0, 0, 0, 4, 8, 6, 0, 0, 0)
        val crc = CRC32().apply { update("IHDR".toByteArray()); update(ihdrData) }.value.toInt()
        val png = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
            write(byteArrayOf(0, 0, 0, 13)); write("IHDR".toByteArray()); write(ihdrData)
            write(byteArrayOf((crc ushr 24).toByte(), (crc ushr 16).toByte(), (crc ushr 8).toByte(), crc.toByte()))
            write(byteArrayOf(0, 0, 0, 0)); write("IEND".toByteArray()); write(byteArrayOf(0xAE.toByte(), 0x42, 0x60, 0x82.toByte()))
        }.toByteArray()
        val extra = ExportMetadata.pngChunk(meta)!!
        val (result, _) = run(ExportFormat.PNG, png, extra, 4)
        assertEquals(png.size + extra.size, result.size)
        val at = 8 + 25
        val length = ((result[at].toInt() and 0xFF) shl 24) or ((result[at + 1].toInt() and 0xFF) shl 16) or ((result[at + 2].toInt() and 0xFF) shl 8) or (result[at + 3].toInt() and 0xFF)
        assertEquals("iTXt", String(result, at + 4, 4, Charsets.ISO_8859_1))
        val check = CRC32().apply { update(result, at + 4, 4 + length) }.value.toInt()
        val stored = ((result[at + 8 + length].toInt() and 0xFF) shl 24) or ((result[at + 9 + length].toInt() and 0xFF) shl 16) or ((result[at + 10 + length].toInt() and 0xFF) shl 8) or (result[at + 11 + length].toInt() and 0xFF)
        assertEquals(check, stored)
        assertTrue("XML:com.adobe.xmp" in String(result, at + 8, length, Charsets.ISO_8859_1))
        assertEquals("IEND", String(result, result.size - 8, 4, Charsets.ISO_8859_1))
    }

    @Test
    fun `nothing to write, other formats and non-JPEG bytes pass through untouched`() {
        assertNull(ExportMetadata.jpegSegments(IptcMetadata(title = " ", keywords = listOf(""))))
        assertNull(ExportMetadata.pngChunk(IptcMetadata()))
        assertTrue(ExportMetadata.supports(ExportFormat.WEBP) && ExportMetadata.supports(ExportFormat.JPEG))
        val notJpeg = byteArrayOf(1, 2, 3, 4, 5, 6)
        assertContentEquals(notJpeg, run(ExportFormat.JPEG, notJpeg, byteArrayOf(9, 9), 2).first)
        val tiny = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
        assertContentEquals(tiny, run(ExportFormat.JPEG, tiny, byteArrayOf(9, 9), 1).first)
    }

    private fun le(v: Int, n: Int) = ByteArray(n) { ((v ushr (8 * it)) and 0xFF).toByte() }

    private fun riff(chunks: ByteArray) = "RIFF".toByteArray() + le(chunks.size + 4, 4) + "WEBP".toByteArray() + chunks

    private fun chunk(type: String, data: ByteArray) = type.toByteArray() + le(data.size, 4) + data + (if (data.size % 2 == 1) byteArrayOf(0) else byteArrayOf())

    @Test
    fun `a plain WebP gets a VP8X header and an XMP chunk, an extended one just the chunk and the flag`() {
        val xmp = ExportMetadata.webpXmp(meta)!!
        val image = chunk("VP8 ", ByteArray(21) { it.toByte() })
        val plain = riff(image)
        val sink = ByteArrayOutputStream()
        val stream = WebpXmpStream(sink, xmp, 64, 48)
        stream.write(plain, 0, 7)
        stream.write(plain, 7, plain.size - 7)
        stream.finish()
        val a = sink.toByteArray()
        assertEquals("RIFF", String(a, 0, 4))
        assertEquals(a.size - 8, (a[4].toInt() and 0xFF) or ((a[5].toInt() and 0xFF) shl 8) or ((a[6].toInt() and 0xFF) shl 16) or ((a[7].toInt() and 0xFF) shl 24))
        assertEquals("VP8X", String(a, 12, 4))
        assertEquals(0x04, a[20].toInt() and 0x04)
        assertEquals(63, (a[24].toInt() and 0xFF) or ((a[25].toInt() and 0xFF) shl 8) or ((a[26].toInt() and 0xFF) shl 16))
        assertEquals(47, (a[27].toInt() and 0xFF) or ((a[28].toInt() and 0xFF) shl 8) or ((a[29].toInt() and 0xFF) shl 16))
        assertEquals("VP8 ", String(a, 30, 4))
        assertEquals(a.size.toLong() - plain.size, stream.injectedBytes)
        val tail = String(a, a.size - xmp.size - (xmp.size % 2) - 8, 4)
        assertEquals("XMP ", tail)
        assertTrue(String(a, Charsets.ISO_8859_1).contains("Ada Lovelace"))

        val extended = riff(chunk("VP8X", byteArrayOf(0x20, 0, 0, 0, 63, 0, 0, 47, 0, 0)) + chunk("ICCP", ByteArray(6)) + image)
        val sink2 = ByteArrayOutputStream()
        WebpXmpStream(sink2, xmp, 64, 48).apply { write(extended, 0, extended.size); finish() }
        val b = sink2.toByteArray()
        assertEquals("VP8X", String(b, 12, 4))
        assertEquals(0x24, b[20].toInt() and 0xFF, "the ICC flag stays and the XMP flag joins it")
        assertEquals(b.size - 8, (b[4].toInt() and 0xFF) or ((b[5].toInt() and 0xFF) shl 8) or ((b[6].toInt() and 0xFF) shl 16) or ((b[7].toInt() and 0xFF) shl 24))
        assertEquals("VP8X", String(b, 12, 4))
        assertEquals(extended.size + 8 + xmp.size + (xmp.size % 2), b.size)
    }

    @Test
    fun `something that is not a WebP is passed through unchanged`() {
        val junk = byteArrayOf(1, 2, 3, 4, 5)
        val sink = ByteArrayOutputStream()
        WebpXmpStream(sink, byteArrayOf(9), 8, 8).apply { write(junk, 0, junk.size); finish() }
        assertContentEquals(junk, sink.toByteArray())
        assertTrue(ExportMetadata.supports(ExportFormat.WEBP) && !ExportMetadata.supports(ExportFormat.TIFF))
    }
}
