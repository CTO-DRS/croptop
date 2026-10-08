package app.croptop.core.engine

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.DocumentPreview
import app.croptop.core.engine.api.DocumentSource
import app.croptop.core.model.AdjustmentInfo
import app.croptop.core.model.DocRect
import app.croptop.core.model.ParamValue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeDocumentPreviewTest {
    @Test
    fun adjustmentPreviewRendersNativeResolutionRegionAcrossTileBoundary() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = NativeEngine(context)
        val source = java.io.File(context.cacheDir, "native-document-region-preview.png")
        Bitmap.createBitmap(520, 3, Bitmap.Config.ARGB_8888).apply {
            for (x in 0 until width) {
                val color = android.graphics.Color.rgb(x % 256, (x * 3) % 256, 71)
                for (y in 0 until height) setPixel(x, y, color)
            }
            source.outputStream().use { output -> check(compress(Bitmap.CompressFormat.PNG, 100, output)) }
            recycle()
        }
        val document = engine.open(DocumentSource.AppFile(source.absolutePath))
        val session = document.beginAdjustmentPreview(
            AdjustmentInfo("exposure", mapOf("exposure" to ParamValue.Num(0f))),
        )
        try {
            val region = DocRect(250, 1, 270, 3)
            val baseline = checkNotNull(session.previewRegion(region))
            for (x in 0 until region.width) {
                val offset = x * 4
                val sourceX = region.left + x
                assertEquals(sourceX % 256, baseline.rgba[offset].toInt() and 0xff)
                assertEquals((sourceX * 3) % 256, baseline.rgba[offset + 1].toInt() and 0xff)
                assertEquals(71, baseline.rgba[offset + 2].toInt() and 0xff)
                assertEquals(255, baseline.rgba[offset + 3].toInt() and 0xff)
            }
            assertTrue(session.preview(mapOf("exposure" to ParamValue.Num(2f))) is CommandResult.Accepted)
            val preview = checkNotNull(session.previewRegion(region))
            assertEquals(region, preview.region)
            assertEquals(region.width * region.height * 4, preview.rgba.size)
            assertFalse("region preview must reflect the active adjustment values", baseline.rgba.contentEquals(preview.rgba))
            for (offset in 3 until preview.rgba.size step 4) assertEquals(255, preview.rgba[offset].toInt() and 0xff)
        } finally {
            session.cancel()
            document.close()
            engine.close()
            source.delete()
        }
    }

    @Test
    fun previewRendersMergedCanvasAsBoundedRgbaAndReleasesAfterClose() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = NativeEngine(context)
        val source = java.io.File(context.cacheDir, "native-document-preview.png")
        Bitmap.createBitmap(4, 2, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.MAGENTA)
            source.outputStream().use { output -> check(compress(Bitmap.CompressFormat.PNG, 100, output)) }
            recycle()
        }
        val document = engine.open(DocumentSource.AppFile(source.absolutePath))
        try {
            val preview = checkNotNull(document.preview(2))
            val cachedPreview = checkNotNull(document.preview(2))

            assertEquals(2, preview.width)
            assertEquals(1, preview.height)
            assertEquals(8, preview.rgba.size)
            assertEquals(preview.width, cachedPreview.width)
            assertEquals(preview.height, cachedPreview.height)
            assertTrue("repeated document preview must stay pixel-identical", preview.rgba.contentEquals(cachedPreview.rgba))
            for (offset in preview.rgba.indices step 4) {
                assertEquals(255, preview.rgba[offset].toInt() and 0xff)
                assertEquals(0, preview.rgba[offset + 1].toInt() and 0xff)
                assertEquals(255, preview.rgba[offset + 2].toInt() and 0xff)
                assertEquals(255, preview.rgba[offset + 3].toInt() and 0xff)
            }
        } finally {
            document.close()
            assertEquals(null, document.preview(2))
            engine.close()
            source.delete()
        }
    }

    @Test
    fun previewEnforcesTheSharedThumbnailDimensionBound() = runBlocking {
        val engine = NativeEngine()
        val document = engine.newDocument(
            app.croptop.core.model.NewDocumentSpec("Preview bound", app.croptop.core.model.DocSize(4, 2)),
        )
        try {
            val failure = runCatching { document.preview(DocumentPreview.MAX_DIMENSION + 1) }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
        } finally {
            document.close()
            engine.close()
        }
    }
}
