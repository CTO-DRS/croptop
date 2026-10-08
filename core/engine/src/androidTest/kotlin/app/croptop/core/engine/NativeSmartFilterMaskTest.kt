package app.croptop.core.engine

import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.MaskFill
import app.croptop.core.engine.api.SampleMode
import app.croptop.core.model.AdjustmentInfo
import app.croptop.core.model.BackgroundContents
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocRectF
import app.croptop.core.model.DocSize
import app.croptop.core.model.FillSource
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.ParamValue
import app.croptop.core.model.RgbaColor
import app.croptop.core.model.SelectionShape
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeSmartFilterMaskTest {
    @Test
    fun maskHidesFiltersWhereBlackAndSurvivesUndoAndSaveReopen() = runBlocking {
        val engine = NativeEngine()
        val document = engine.newDocument(NewDocumentSpec("Smart filter mask", DocSize(32, 16), background = BackgroundContents.TRANSPARENT))
        try {
            val layer = checkNotNull(document.layers.value.primary)
            assertTrue(document.fill(FillSource.Solid(RgbaColor.Black)) is CommandResult.Accepted)
            assertTrue(document.select(SelectionShape.Rect(DocRectF(0f, 0f, 16f, 16f)), antiAlias = false) is CommandResult.Accepted)
            assertTrue(document.fill(FillSource.Solid(RgbaColor.White)) is CommandResult.Accepted)
            assertTrue(document.deselect() is CommandResult.Accepted)
            assertTrue(document.convertToSmartObject(layer) is CommandResult.Accepted)
            val blur = AdjustmentInfo("filter.gaussianBlur", mapOf("radius" to ParamValue.Num(5f)))
            val edge = DocPoint(15.5f, 8.5f)
            val unfiltered = document.sample(edge, SampleMode.ACTIVE_LAYER).color.r
            assertTrue(document.setSmartFilters(layer, listOf(blur)) is CommandResult.Accepted)
            val filtered = document.sample(edge, SampleMode.ACTIVE_LAYER).color.r
            assertTrue("the blur softens the edge", filtered < unfiltered)

            assertTrue(document.addSmartFilterMask(layer, MaskFill.HIDE_ALL) is CommandResult.Accepted)
            assertEquals("a black mask shows the placement", unfiltered, document.sample(edge, SampleMode.ACTIVE_LAYER).color.r, 0.02f)
            assertTrue(document.invertSmartFilterMask(layer) is CommandResult.Accepted)
            assertEquals("a white mask shows the filters", filtered, document.sample(edge, SampleMode.ACTIVE_LAYER).color.r, 0.02f)
            assertTrue(document.paintSmartFilterMask(layer, listOf(DocPoint(15.5f, 8.5f)), 6f, reveal = false) is CommandResult.Accepted)
            assertEquals("painting black over the edge hides the filter", unfiltered, document.sample(edge, SampleMode.ACTIVE_LAYER).color.r, 0.05f)
            assertTrue(document.setSmartFilterMaskEnabled(layer, false) is CommandResult.Accepted)
            assertEquals("a disabled mask lets the filters through", filtered, document.sample(edge, SampleMode.ACTIVE_LAYER).color.r, 0.02f)
            assertTrue(document.undo() is CommandResult.Accepted)
            assertEquals(unfiltered, document.sample(edge, SampleMode.ACTIVE_LAYER).color.r, 0.05f)
        } finally {
            document.close()
            engine.close()
        }
    }
}
