package app.croptop.core.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.SampleMode
import app.croptop.core.model.AdjustmentInfo
import app.croptop.core.model.BackgroundContents
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocSize
import app.croptop.core.model.FillSource
import app.croptop.core.model.FilterCatalog
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.ParamValue
import app.croptop.core.model.RgbaColor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeLensFlareFilterTest {
    @Test
    fun lensFlareFilterPreviewsBrightensTheLightCommitsAndUndoes() = runBlocking {
        val engine = NativeEngine()
        val document = engine.newDocument(NewDocumentSpec("Lens flare", DocSize(128, 128), background = BackgroundContents.TRANSPARENT))
        try {
            assertTrue(document.fill(FillSource.Solid(RgbaColor(0.05f, 0.05f, 0.08f, 1f))) is CommandResult.Accepted)
            val id = FilterCatalog.PREFIX + "lensFlare"
            assertTrue(document.supportsDestructiveAdjustment(id))
            val spec = checkNotNull(FilterCatalog.get(id))
            val values = spec.defaults() + ("lens" to ParamValue.Choice("movie"))
            val before = document.history.value
            val session = document.beginAdjustmentPreview(AdjustmentInfo(id, values))
            assertTrue(session.preview(values) is CommandResult.Accepted)
            // The default light is at 25% / 30% of the frame.
            val light = document.sample(DocPoint(32f, 38f), SampleMode.ACTIVE_LAYER).color
            val far = document.sample(DocPoint(125f, 125f), SampleMode.ACTIVE_LAYER).color
            assertTrue("the light is nearly white: $light", light.r > 0.8f && light.g > 0.7f)
            assertEquals("far from every element the picture is as it was", 0.05f, far.r, 0.03f)
            assertEquals(1f, light.a, 0.001f)
            assertEquals(before, document.history.value)
            assertTrue(session.commit() is CommandResult.Accepted)
            assertEquals(before.entries.size + 1, document.history.value.entries.size)
            assertEquals("Lens Flare", document.history.value.undoLabel)
            assertTrue(document.undo() is CommandResult.Accepted)
            assertEquals(0.05f, document.sample(DocPoint(32f, 38f), SampleMode.ACTIVE_LAYER).color.r, 0.01f)
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun displaceShiftsTheLayerByAnotherLayersBrightnessAndNeedsAMap() = runBlocking {
        val engine = NativeEngine()
        val document = engine.newDocument(NewDocumentSpec("Displace", DocSize(64, 64), background = BackgroundContents.TRANSPARENT))
        try {
            // Target: a red left half and blue right half; map: solid white, which pushes every sample by the full scale.
            assertTrue(document.select(app.croptop.core.model.SelectionShape.Rect(app.croptop.core.model.DocRectF(0f, 0f, 32f, 64f)), antiAlias = false) is CommandResult.Accepted)
            assertTrue(document.fill(FillSource.Solid(RgbaColor(1f, 0f, 0f, 1f))) is CommandResult.Accepted)
            assertTrue(document.select(app.croptop.core.model.SelectionShape.Rect(app.croptop.core.model.DocRectF(32f, 0f, 64f, 64f)), antiAlias = false) is CommandResult.Accepted)
            assertTrue(document.fill(FillSource.Solid(RgbaColor(0f, 0f, 1f, 1f))) is CommandResult.Accepted)
            assertTrue(document.deselect() is CommandResult.Accepted)
            val target = checkNotNull(document.layers.value.primary)
            val mapLayer = app.croptop.core.model.LayerId(900_001)
            assertTrue(document.execute(app.croptop.core.engine.api.EditCommand.AddLayer(mapLayer, "Map", app.croptop.core.model.LayerKind.PIXEL)) is CommandResult.Accepted)
            assertTrue(document.fill(FillSource.Solid(RgbaColor.White)) is CommandResult.Accepted)
            assertTrue(document.setActiveLayers(setOf(target), target) is CommandResult.Accepted)

            val id = FilterCatalog.PREFIX + "displace"
            val spec = checkNotNull(FilterCatalog.get(id))
            // Without a map the preview is refused with a clear reason instead of doing nothing.
            val noMap = document.beginAdjustmentPreview(AdjustmentInfo(id, spec.defaults()))
            assertTrue("a map is required", noMap.preview(spec.defaults()) is CommandResult.Rejected)
            noMap.cancel()

            val values = spec.defaults() + ("h" to ParamValue.Num(20f)) + ("v" to ParamValue.Num(0f)) +
                ("map" to ParamValue.Choice(app.croptop.core.model.ChoiceParam.LAYER_PREFIX + mapLayer.value))
            val session = document.beginAdjustmentPreview(AdjustmentInfo(id, values))
            assertTrue(session.preview(values) is CommandResult.Accepted)
            // A white map offsets samples by about scale·1.28·(1 − 0.5)·2 px; the border between red and blue moves sideways.
            val nearBorder = document.sample(DocPoint(30f, 32f), SampleMode.ACTIVE_LAYER).color
            println("[displace] pixel just left of the border: $nearBorder")
            assertTrue("the border moved: $nearBorder", nearBorder.b > 0.5f && nearBorder.r < 0.5f)
            assertTrue(session.commit() is CommandResult.Accepted)
            assertEquals("Displace", document.history.value.undoLabel)
        } finally {
            document.close()
            engine.close()
        }
    }
}
