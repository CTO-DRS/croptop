package app.croptop.core.engine

import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.SampleMode
import app.croptop.core.model.BackgroundContents
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocSize
import app.croptop.core.model.FillSource
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.RgbaColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeSampleFastPathTest {
    @Test
    fun singleLayerSamplingMatchesForPointAndMaximumWindow() {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec("Sampler fast path", DocSize(200, 200), background = BackgroundContents.TRANSPARENT),
        )
        try {
            assertTrue(document.fill(FillSource.Solid(RgbaColor(0.35f, 0.6f, 0.8f))) is CommandResult.Accepted)
            val point = DocPoint(100f, 100f)
            val pointStart = System.nanoTime()
            val pointSample = document.sample(point, SampleMode.MERGED, 1).color
            val pointNanos = System.nanoTime() - pointStart
            val windowStart = System.nanoTime()
            val windowSample = document.sample(point, SampleMode.MERGED, 101).color
            val windowNanos = System.nanoTime() - windowStart

            assertEquals(pointSample.r, windowSample.r, 0.002f)
            assertEquals(pointSample.g, windowSample.g, 0.002f)
            assertEquals(pointSample.b, windowSample.b, 0.002f)
            assertEquals(pointSample.a, windowSample.a, 0.002f)
            assertEquals(0.35f, windowSample.r, 0.01f)
            assertEquals(0.6f, windowSample.g, 0.01f)
            assertEquals(0.8f, windowSample.b, 0.01f)
            android.util.Log.i(
                "NativeSampleFastPath",
                "singleLayerRgba8 point1x1Ms=${pointNanos / 1_000_000.0} window101x101Ms=${windowNanos / 1_000_000.0}",
            )
        } finally {
            document.close()
            engine.close()
        }
    }
}
