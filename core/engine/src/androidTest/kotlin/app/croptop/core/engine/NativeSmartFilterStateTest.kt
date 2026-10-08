package app.croptop.core.engine

import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.SampleMode
import app.croptop.core.model.AdjustmentInfo
import app.croptop.core.model.BackgroundContents
import app.croptop.core.model.BlendMode
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocRectF
import app.croptop.core.model.DocSize
import app.croptop.core.model.FillSource
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.RgbaColor
import app.croptop.core.model.SelectionShape
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeSmartFilterStateTest {
    @Test
    fun enabledOpacityAndBlendModeAffectRenderingAndUndoTogether() = runBlocking {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec("Smart filter blend state", DocSize(32, 16), background = BackgroundContents.TRANSPARENT),
        )
        try {
            val layer = checkNotNull(document.layers.value.primary)
            assertTrue(document.fill(FillSource.Solid(RgbaColor.Black)) is CommandResult.Accepted)
            assertTrue(document.select(SelectionShape.Rect(DocRectF(0f, 0f, 16f, 16f)), antiAlias = false) is CommandResult.Accepted)
            assertTrue(document.fill(FillSource.Solid(RgbaColor.White)) is CommandResult.Accepted)
            assertTrue(document.deselect() is CommandResult.Accepted)
            assertTrue(document.convertToSmartObject(layer) is CommandResult.Accepted)

            val specId = "filter.gaussianBlur"
            val values = mapOf("radius" to app.croptop.core.model.ParamValue.Num(5f))
            val baseline = document.sample(DocPoint(15.5f, 8.5f), SampleMode.ACTIVE_LAYER).color.r

            assertTrue(
                document.setSmartFilters(layer, listOf(AdjustmentInfo(specId, values, enabled = false))) is CommandResult.Accepted,
            )
            val disabled = document.sample(DocPoint(15.5f, 8.5f), SampleMode.ACTIVE_LAYER).color.r
            assertEquals(baseline, disabled, 0.01f)

            assertTrue(
                document.setSmartFilters(layer, listOf(AdjustmentInfo(specId, values))) is CommandResult.Accepted,
            )
            val fullOpacity = document.sample(DocPoint(15.5f, 8.5f), SampleMode.ACTIVE_LAYER).color.r

            assertTrue(
                document.setSmartFilters(layer, listOf(AdjustmentInfo(specId, values, opacity = 0.5f))) is CommandResult.Accepted,
            )
            val halfOpacity = document.sample(DocPoint(15.5f, 8.5f), SampleMode.ACTIVE_LAYER).color.r
            assertTrue("partial opacity should blend the filtered and unfiltered edge", fullOpacity < halfOpacity && halfOpacity < disabled)

            assertTrue(
                document.setSmartFilters(
                    layer,
                    listOf(AdjustmentInfo(specId, values, blendMode = BlendMode.MULTIPLY)),
                ) is CommandResult.Accepted,
            )
            val multiply = document.sample(DocPoint(15.5f, 8.5f), SampleMode.ACTIVE_LAYER).color.r
            assertTrue("Multiply should darken the filter result against this grayscale source", multiply < disabled)

            assertTrue(document.undo() is CommandResult.Accepted)
            assertEquals(halfOpacity, document.sample(DocPoint(15.5f, 8.5f), SampleMode.ACTIVE_LAYER).color.r, 0.02f)
            assertTrue(document.redo() is CommandResult.Accepted)
            assertEquals(multiply, document.sample(DocPoint(15.5f, 8.5f), SampleMode.ACTIVE_LAYER).color.r, 0.02f)
        } finally {
            document.close()
            engine.close()
        }
    }
}
