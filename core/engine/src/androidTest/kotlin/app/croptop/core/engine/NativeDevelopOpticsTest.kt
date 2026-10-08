package app.croptop.core.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.croptop.core.engine.api.CommandResult
import app.croptop.core.model.BackgroundContents
import app.croptop.core.model.ColorDepth
import app.croptop.core.model.DevelopSettings
import app.croptop.core.model.DocSize
import app.croptop.core.model.FillSource
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.Optics
import app.croptop.core.model.RgbaColor
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeDevelopOpticsTest {
    @Test
    fun manualVignettingAndDistortionRenderThroughDevelopJni() {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec("Develop optics", DocSize(32, 24), depth = ColorDepth.U16, background = BackgroundContents.TRANSPARENT),
        ) as NativeDocument
        var renderedStore = 0L
        try {
            assertTrue(document.fill(FillSource.Solid(RgbaColor.White)) is CommandResult.Accepted)
            val layer = checkNotNull(document.layers.value.primary)
            val source = checkNotNull(document.pixelStore(layer))
            val rendered = NativeDevelopBridge.render(
                source,
                DevelopSettings(optics = Optics(vignetting = -60f, distortion = 70f)),
                useGpu = false,
            )
            renderedStore = rendered.storeHandle
            assertTrue("manual Develop optics should not be rejected", renderedStore != 0L)
            assertTrue("unsupported profile correction should remain visible to the caller", rendered.warnings.any { "Lens-profile" in it })
        } finally {
            if (renderedStore != 0L) NativeSelectionBridge.destroyStore(renderedStore)
            document.close()
            engine.close()
        }
    }
}
