package app.croptop.core.engine

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.croptop.core.engine.api.SampleMode
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeLensBlurSelectionBlendTest {
    @Test
    fun featheredSelectionBlendsTranslucentPixelsInPremultipliedAlpha() {
        val engine = NativeEngine()
        var source = 0L
        var processed = 0L
        var selection = 0L
        var mixed = 0L
        fun png(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().also {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            bitmap.recycle()
        }.toByteArray()

        val sourceBytes = png(
            Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.TRANSPARENT)
                setPixel(0, 1, Color.argb(128, 255, 0, 0))
            },
        )
        val processedBytes = png(
            Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.BLUE)
            },
        )

        try {
            source = engine.createTileStore(4, 4, 0)
            processed = engine.createTileStore(4, 4, 0)
            assertEquals(null, engine.decodeToTileStore(sourceBytes, sourceBytes.size, source, codec = 1, exifOrientation = 1))
            assertEquals(null, engine.decodeToTileStore(processedBytes, processedBytes.size, processed, codec = 1, exifOrientation = 1))
            selection = NativeSelectionBridge.rasterizeRect(4, 4, 0.25, 0.0, 4.0, 4.0, true)
            assertTrue(selection != 0L)

            mixed = NativeLensBlurBridge.blendSelection(source, processed, selection)
            fun sample(handle: Long) = checkNotNull(
                engine.samplePixels(
                    listOf(NativeRenderLayer(handle, 1, blendMode = 0, opacity = 255, fillOpacity = 255, visible = true)),
                    SampleMode.ACTIVE_LAYER,
                    primaryLayerId = 1,
                    x = 0,
                    y = 1,
                    size = 1,
                ),
            )

            val before = sample(source)
            val after = sample(processed)
            val actual = sample(mixed)
            val coverage = (actual.a - before.a) / (after.a - before.a)
            val expectedAlpha = before.a + coverage * (after.a - before.a)
            val expectedRed = before.r * before.a * (1f - coverage) / expectedAlpha
            val expectedBlue = after.b * after.a * coverage / expectedAlpha

            assertTrue("test pixel should have partial selection coverage", coverage in 0.05f..0.95f)
            assertEquals(expectedAlpha, actual.a, 0.01f)
            assertEquals(expectedRed, actual.r, 0.02f)
            assertEquals(expectedBlue, actual.b, 0.02f)
        } finally {
            if (mixed != 0L) engine.destroyTileStore(mixed)
            if (selection != 0L) NativeSelectionBridge.destroyStore(selection)
            if (processed != 0L) engine.destroyTileStore(processed)
            if (source != 0L) engine.destroyTileStore(source)
            engine.close()
        }
    }
}
