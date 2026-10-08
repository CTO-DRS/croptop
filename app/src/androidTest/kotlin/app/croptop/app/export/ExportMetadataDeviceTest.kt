package app.croptop.app.export

import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.croptop.core.engine.NativeEngine
import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.ExportFormat
import app.croptop.core.engine.api.ExportOptions
import app.croptop.core.engine.api.ExportTarget
import app.croptop.core.engine.api.IptcMetadata
import app.croptop.core.model.BackgroundContents
import app.croptop.core.model.DocSize
import app.croptop.core.model.FillSource
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.RgbaColor
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real JPEG and PNG exports with the Library's metadata must stay valid images that other readers can read it back from. */
@RunWith(AndroidJUnit4::class)
class ExportMetadataDeviceTest {
    private val iptc = IptcMetadata("Sunset over the bay", "A long, quiet evening & a very orange sky.", "Ada Lovelace", "© 2026 Ada", listOf("sunset", "bay"))

    private fun export(format: ExportFormat, options: ExportOptions): File = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "meta-export.${format.name.lowercase()}").also { it.delete() }
        val engine = NativeEngine(context)
        val document = engine.newDocument(NewDocumentSpec("Meta", DocSize(64, 48), background = BackgroundContents.WHITE))
        try {
            assertTrue(document.fill(FillSource.Solid(RgbaColor(0.8f, 0.4f, 0.1f, 1f))) is CommandResult.Accepted)
            val result = document.export(ExportTarget.AppFile(file.absolutePath), options)
            assertEquals("the reported size matches the file", file.length(), result.bytesWritten)
        } finally {
            document.close()
            engine.close()
        }
        file
    }

    /** The IPTC-IIM datasets of record 2 in a JPEG's APP13 block. */
    private fun iimRecords(jpeg: ByteArray): List<Pair<Int, String>> {
        var i = 2
        while (i + 4 < jpeg.size && jpeg[i] == 0xFF.toByte()) {
            val marker = jpeg[i + 1].toInt() and 0xFF
            val length = ((jpeg[i + 2].toInt() and 0xFF) shl 8) or (jpeg[i + 3].toInt() and 0xFF)
            if (marker == 0xED) {
                val block = jpeg.copyOfRange(i + 4, i + 2 + length)
                val at = String(block, Charsets.ISO_8859_1).indexOf("8BIM")
                val size = ((block[at + 8].toInt() and 0xFF) shl 24) or ((block[at + 9].toInt() and 0xFF) shl 16) or ((block[at + 10].toInt() and 0xFF) shl 8) or (block[at + 11].toInt() and 0xFF)
                val iim = block.copyOfRange(at + 12, at + 12 + size)
                val out = mutableListOf<Pair<Int, String>>()
                var j = 0
                while (j + 5 <= iim.size) {
                    val len = ((iim[j + 3].toInt() and 0xFF) shl 8) or (iim[j + 4].toInt() and 0xFF)
                    if (iim[j + 1].toInt() == 2) out += (iim[j + 2].toInt() and 0xFF) to String(iim, j + 5, len, Charsets.UTF_8)
                    j += 5 + len
                }
                return out
            }
            if (marker == 0xDA) break
            i += 2 + length
        }
        return emptyList()
    }

    @Test
    fun jpegCarriesIptcAndXmpAndStillDecodes() {
        val file = export(ExportFormat.JPEG, ExportOptions(ExportFormat.JPEG, quality = 90, iptc = iptc))
        val bitmap = BitmapFactory.decodeFile(file.path)
        assertNotNull("the JPEG still decodes", bitmap)
        assertEquals(64, bitmap.width)
        val records = iimRecords(file.readBytes())
        println("[meta] jpeg iim: $records")
        assertEquals("Sunset over the bay", records.single { it.first == 5 }.second)
        assertEquals(listOf("sunset", "bay"), records.filter { it.first == 25 }.map { it.second })
        assertEquals("Ada Lovelace", records.single { it.first == 80 }.second)
        assertEquals("© 2026 Ada", records.single { it.first == 116 }.second)
        val xmp = ExifInterface(file.path).getAttribute(ExifInterface.TAG_XMP).orEmpty()
        assertTrue("XMP is readable by ExifInterface: $xmp", "A long, quiet evening &amp; a very orange sky." in xmp && "Ada Lovelace" in xmp)
        val stripped = export(ExportFormat.JPEG, ExportOptions(ExportFormat.JPEG, quality = 90, stripMetadata = true, iptc = iptc))
        assertTrue("strip metadata drops it all", iimRecords(stripped.readBytes()).isEmpty())
        assertTrue(ExifInterface(stripped.path).getAttribute(ExifInterface.TAG_XMP).isNullOrEmpty())
    }

    @Test
    fun pngCarriesXmpAndStillDecodes() {
        val file = export(ExportFormat.PNG, ExportOptions(ExportFormat.PNG, iptc = iptc))
        assertNotNull("the PNG still decodes", BitmapFactory.decodeFile(file.path))
        val xmp = ExifInterface(file.path).getAttribute(ExifInterface.TAG_XMP).orEmpty()
        assertTrue("XMP is readable from the PNG: $xmp", "Sunset over the bay" in xmp && "<rdf:li>sunset</rdf:li>" in xmp)
    }

    @Test
    fun webpCarriesXmpInAValidRiffAndStillDecodes() {
        val file = export(ExportFormat.WEBP, ExportOptions(ExportFormat.WEBP, quality = 90, iptc = iptc))
        val bytes = file.readBytes()
        assertNotNull("the WebP still decodes", BitmapFactory.decodeFile(file.path))
        assertEquals("RIFF", String(bytes, 0, 4))
        val riffSize = (bytes[4].toInt() and 0xFF) or ((bytes[5].toInt() and 0xFF) shl 8) or ((bytes[6].toInt() and 0xFF) shl 16) or ((bytes[7].toInt() and 0xFF) shl 24)
        assertEquals("the RIFF size matches the file", bytes.size - 8, riffSize)
        assertEquals("VP8X", String(bytes, 12, 4))
        assertTrue("the XMP flag is set", (bytes[20].toInt() and 0x04) != 0)
        val text = String(bytes, Charsets.ISO_8859_1)
        assertTrue("XMP chunk with the caption and keywords", "XMP " in text && "Ada Lovelace" in text && "<rdf:li>sunset</rdf:li>" in text)
        assertEquals(64, BitmapFactory.decodeFile(file.path).width)
    }
}
