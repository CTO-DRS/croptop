package app.croptop.core.engine

import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.MaskFill
import app.croptop.core.engine.api.SampleMode
import app.croptop.core.engine.api.StrokeSpec
import app.croptop.core.model.BackgroundContents
import app.croptop.core.model.BrushSettings as ModelBrushSettings
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocSize
import app.croptop.core.model.Dynamics
import app.croptop.core.model.DynamicsInput
import app.croptop.core.model.FillSource
import app.croptop.core.model.LayerMaskInfo
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.RgbaColor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeLayerMaskBrushTest {
    @Test
    fun brushStrokeChangesMaskOnlyAndUndoRestoresCoverageAndMetadata() = runBlocking {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec("Mask brush", DocSize(32, 32), background = BackgroundContents.TRANSPARENT),
        )
        try {
            val layer = checkNotNull(document.layers.value.primary)
            val layerColor = RgbaColor(0.8f, 0.2f, 0.1f, 1f)
            assertTrue(document.fill(FillSource.Solid(layerColor)) is CommandResult.Accepted)
            assertTrue(document.addLayerMask(layer, MaskFill.HIDE_ALL) is CommandResult.Accepted)
            val maskInfo = LayerMaskInfo(enabled = true, density = 0.75f, feather = 0f)
            assertTrue(document.setLayerMask(layer, maskInfo) is CommandResult.Accepted)
            val pixelBefore = document.sample(DocPoint(16f, 16f), SampleMode.ACTIVE_LAYER).color
            val historyBefore = document.history.value.current

            val brush = ModelBrushSettings(
                size = 10f,
                hardness = 1f,
                opacity = 1f,
                flow = 1f,
                sizeDynamics = Dynamics(DynamicsInput.OFF),
            )
            val stroke = document.beginMaskStroke(
                StrokeSpec(
                    target = layer,
                    color = RgbaColor.White,
                    diameter = brush.size,
                    hardness = brush.hardness,
                    opacity = brush.opacity,
                    brush = brush,
                ),
            )
            stroke.add(floatArrayOf(16f, 16f, 1f, 0f, 0f, 0f, 0f))
            assertTrue(stroke.end() is CommandResult.Accepted)

            assertEquals(historyBefore + 1, document.history.value.current)
            assertEquals(maskInfo, document.layers.value.find(layer)?.mask)
            assertEquals(0.25f, document.sample(DocPoint(3f, 3f), SampleMode.MERGED).color.a, 0.01f)
            assertTrue(document.sample(DocPoint(16f, 16f), SampleMode.MERGED).color.a > 0.7f)
            assertEquals(pixelBefore, document.sample(DocPoint(16f, 16f), SampleMode.ACTIVE_LAYER).color)

            assertTrue(document.undo() is CommandResult.Accepted)
            assertEquals(maskInfo, document.layers.value.find(layer)?.mask)
            assertEquals(0.25f, document.sample(DocPoint(16f, 16f), SampleMode.MERGED).color.a, 0.01f)
            assertEquals(pixelBefore, document.sample(DocPoint(16f, 16f), SampleMode.ACTIVE_LAYER).color)
            assertTrue(document.redo() is CommandResult.Accepted)
            assertEquals(maskInfo, document.layers.value.find(layer)?.mask)
            assertTrue(document.sample(DocPoint(16f, 16f), SampleMode.MERGED).color.a > 0.7f)
        } finally {
            document.close()
            engine.close()
        }
    }
}
