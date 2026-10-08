package app.croptop.core.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.DocumentSource
import app.croptop.core.engine.api.ExportFormat
import app.croptop.core.engine.api.ExportOptions
import app.croptop.core.engine.api.ExportTarget
import app.croptop.core.engine.api.SampleMode
import app.croptop.core.model.BackgroundContents
import app.croptop.core.model.ColorDepth
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocSize
import app.croptop.core.model.FillSource
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.RgbaColor
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** AVIF export/open round trip and the Ultra HDR 32-bit requirement, through the real NativeEngine. */
class NativeAvifUltraHdrFlowTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun avifExportReopensWithTheSameColour() = runBlocking {
        val engine = NativeEngine(context)
        val document = engine.newDocument(NewDocumentSpec("AVIF", DocSize(64, 48), background = BackgroundContents.TRANSPARENT))
        val file = File(context.cacheDir, "flow-test.avif")
        try {
            assertTrue(document.fill(FillSource.Solid(RgbaColor(0.8f, 0.2f, 0.1f, 1f))) is CommandResult.Accepted)
            document.export(ExportTarget.AppFile(file.absolutePath), ExportOptions(ExportFormat.AVIF, quality = 100))
            assertTrue(file.length() > 0)
            val reopened = engine.open(DocumentSource.AppFile(file.absolutePath))
            try {
                assertEquals(DocSize(64, 48), reopened.info.value.size)
                val c = reopened.sample(DocPoint(32f, 24f), SampleMode.ACTIVE_LAYER).color
                assertEquals(0.8f, c.r, 0.03f)
                assertEquals(0.2f, c.g, 0.03f)
            } finally {
                reopened.close()
            }
        } finally {
            document.close()
            engine.close()
            file.delete()
        }
    }

    @Test
    fun ultraHdrExportNeedsAThirtyTwoBitDocument() = runBlocking {
        val engine = NativeEngine(context)
        val eightBit = engine.newDocument(NewDocumentSpec("SDR", DocSize(32, 32), background = BackgroundContents.WHITE))
        val hdr = engine.newDocument(NewDocumentSpec("HDR", DocSize(32, 32), depth = ColorDepth.F32, background = BackgroundContents.WHITE))
        val sdrFile = File(context.cacheDir, "flow-sdr.jpg")
        val hdrFile = File(context.cacheDir, "flow-hdr.jpg")
        try {
            val rejected = runCatching { eightBit.export(ExportTarget.AppFile(sdrFile.absolutePath), ExportOptions(ExportFormat.ULTRA_HDR)) }
            assertTrue("an 8-bit document cannot make an Ultra HDR file", rejected.isFailure)
            hdr.export(ExportTarget.AppFile(hdrFile.absolutePath), ExportOptions(ExportFormat.ULTRA_HDR))
            val bytes = hdrFile.readBytes()
            assertTrue(NativeAvifUltraHdrBridge.isUltraHdr(bytes, bytes.size))
        } finally {
            eightBit.close()
            hdr.close()
            engine.close()
            sdrFile.delete()
            hdrFile.delete()
        }
    }
}
