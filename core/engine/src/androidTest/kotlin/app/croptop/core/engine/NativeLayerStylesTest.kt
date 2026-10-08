package app.croptop.core.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.DocumentSource
import app.croptop.core.engine.api.SampleMode
import app.croptop.core.engine.api.SaveTarget
import app.croptop.core.model.BackgroundContents
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocSize
import app.croptop.core.model.FillSource
import app.croptop.core.model.LayerStyles
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.RgbaColor
import app.croptop.core.model.StyleColorOverlay
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeLayerStylesTest {
    @Test
    fun previewCommitCancelHistoryAndArchiveRestoreRenderAttachedColorOverlay() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val project = File.createTempFile("croptop-layer-style-", ".croptop", context.cacheDir)
        val engine = NativeEngine(context)
        val document = engine.newDocument(NewDocumentSpec("Layer style", DocSize(32, 24), background = BackgroundContents.TRANSPARENT))
        try {
            val layer = checkNotNull(document.layers.value.primary)
            accepted(document.fill(FillSource.Solid(RgbaColor.White)))
            val point = DocPoint(16f, 12f)
            val base = document.sample(point, SampleMode.MERGED).color
            assertTrue(base.r > 0.9f && base.g > 0.9f && base.b > 0.9f)

            val red = colorOverlay(RgbaColor(1f, 0f, 0f, 1f))
            accepted(document.previewLayerStyles(layer, red))
            assertColor(document.sample(point, SampleMode.MERGED).color, r = 1f, g = 0f, b = 0f)

            accepted(document.previewLayerStyles(layer, null))
            assertColor(document.sample(point, SampleMode.MERGED).color, r = 1f, g = 1f, b = 1f)

            val history = document.history.value.current
            accepted(document.setLayerStyles(layer, red))
            assertEquals(history + 1, document.history.value.current)
            assertColor(document.sample(point, SampleMode.MERGED).color, r = 1f, g = 0f, b = 0f)

            accepted(document.undo())
            assertColor(document.sample(point, SampleMode.MERGED).color, r = 1f, g = 1f, b = 1f)
            accepted(document.redo())
            assertColor(document.sample(point, SampleMode.MERGED).color, r = 1f, g = 0f, b = 0f)

            document.save(SaveTarget.AppFile(project.absolutePath))
            val reopened = engine.open(DocumentSource.AppFile(project.absolutePath))
            try {
                assertEquals(red, reopened.layers.value.flatten().single().styles)
                assertColor(reopened.sample(point, SampleMode.MERGED).color, r = 1f, g = 0f, b = 0f)
            } finally {
                reopened.close()
            }
        } finally {
            document.close()
            engine.close()
            project.delete()
        }
    }

    private fun colorOverlay(color: RgbaColor) = LayerStyles(
        colorOverlay = StyleColorOverlay(enabled = true, color = color),
    )

    private fun accepted(result: CommandResult) = assertTrue(result.toString(), result is CommandResult.Accepted)

    private fun assertColor(color: RgbaColor, r: Float, g: Float, b: Float) {
        assertEquals(r, color.r, 0.02f)
        assertEquals(g, color.g, 0.02f)
        assertEquals(b, color.b, 0.02f)
    }
}
