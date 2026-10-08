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
import app.croptop.core.model.ParamValue
import app.croptop.core.model.RgbaColor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeLensCorrectionFilterTest {
    @Test
    fun catalogFilterPreviewsCommitsAndUndoesManualScaleCorrection() = runBlocking {
        val engine = NativeEngine()
        val document = engine.newDocument(
            app.croptop.core.model.NewDocumentSpec("Lens correction", DocSize(16, 16), background = BackgroundContents.TRANSPARENT),
        )
        try {
            assertTrue(document.fill(FillSource.Solid(RgbaColor.White)) is CommandResult.Accepted)
            val id = FilterCatalog.PREFIX + "lensCorrection"
            assertTrue(document.supportsDestructiveAdjustment(id))
            val values = FilterCatalog.get(id)!!.defaults() + ("scale" to ParamValue.Num(0.5f))
            val before = document.history.value
            val session = document.beginAdjustmentPreview(AdjustmentInfo(id, values))

            assertTrue(session.preview(values) is CommandResult.Accepted)
            val center = document.sample(DocPoint(8f, 8f), SampleMode.ACTIVE_LAYER).color
            val corner = document.sample(DocPoint(0f, 0f), SampleMode.ACTIVE_LAYER).color
            assertTrue("scaled center remains opaque", center.a > 0.99f)
            assertEquals("inverse mapping reveals transparent canvas around the scaled image", 0f, corner.a, 0.001f)
            assertEquals(before, document.history.value)

            assertTrue(session.commit() is CommandResult.Accepted)
            assertEquals(before.entries.size + 1, document.history.value.entries.size)
            assertTrue(document.undo() is CommandResult.Accepted)
            assertTrue(document.redo() is CommandResult.Accepted)
        } finally {
            document.close()
            engine.close()
        }
    }
}
