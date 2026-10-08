package app.croptop.core.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.SampleMode
import app.croptop.core.model.AdjustmentInfo
import app.croptop.core.model.BackgroundContents
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocRectF
import app.croptop.core.model.DocSize
import app.croptop.core.model.FillSource
import app.croptop.core.model.FilterCatalog
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.ParamValue
import app.croptop.core.model.RgbaColor
import app.croptop.core.model.SelectionShape
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeDefringeFilterTest {
    @Test
    fun catalogFilterPreviewsSelectionCommitsAndUndoesDefringe() = runBlocking {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec("Defringe filter", DocSize(32, 16), background = BackgroundContents.TRANSPARENT),
        )
        try {
            assertTrue(document.fill(FillSource.Solid(RgbaColor.White)) is CommandResult.Accepted)
            assertTrue(document.select(SelectionShape.Rect(DocRectF(0f, 0f, 16f, 16f)), antiAlias = false) is CommandResult.Accepted)
            assertTrue(document.fill(FillSource.Solid(RgbaColor(1f, 0f, 1f, 1f))) is CommandResult.Accepted)
            assertTrue(document.deselect() is CommandResult.Accepted)

            val id = FilterCatalog.PREFIX + "defringe"
            assertTrue("catalog filter is reachable", document.supportsDestructiveAdjustment(id))
            val values = FilterCatalog.get(id)!!.defaults() + ("purpleAmount" to ParamValue.Num(20f))
            val original = document.sample(DocPoint(15f, 8f), SampleMode.ACTIVE_LAYER).color

            assertTrue(
                document.select(SelectionShape.Rect(DocRectF(24f, 0f, 26f, 16f)), antiAlias = false) is CommandResult.Accepted,
            )
            val maskedBaseline = document.history.value
            val maskedSession = document.beginAdjustmentPreview(AdjustmentInfo(id, values))
            assertTrue(maskedSession.preview(values) is CommandResult.Accepted)
            val outsideMask = document.sample(DocPoint(15f, 8f), SampleMode.ACTIVE_LAYER).color
            assertEquals(original.r, outsideMask.r, 0.01f)
            assertEquals(original.g, outsideMask.g, 0.01f)
            assertEquals(original.b, outsideMask.b, 0.01f)
            assertEquals(maskedBaseline.entries.size, document.history.value.entries.size)
            maskedSession.cancel()
            assertTrue(document.deselect() is CommandResult.Accepted)

            val before = document.history.value
            val session = document.beginAdjustmentPreview(AdjustmentInfo(id, values))
            assertTrue(session.preview(values) is CommandResult.Accepted)
            val changed = document.sample(DocPoint(15f, 8f), SampleMode.ACTIVE_LAYER).color
            val originalChroma = maxOf(original.r, original.g, original.b) - minOf(original.r, original.g, original.b)
            val changedChroma = maxOf(changed.r, changed.g, changed.b) - minOf(changed.r, changed.g, changed.b)
            assertTrue("preview runs native defringe: $original -> $changed", changedChroma < originalChroma)
            assertEquals(before.entries.size, document.history.value.entries.size)

            assertTrue(session.commit() is CommandResult.Accepted)
            assertEquals(before.entries.size + 1, document.history.value.entries.size)
            assertTrue(document.undo() is CommandResult.Accepted)
            val undone = document.sample(DocPoint(15f, 8f), SampleMode.ACTIVE_LAYER).color
            assertEquals(original.r, undone.r, 0.01f)
            assertEquals(original.g, undone.g, 0.01f)
            assertEquals(original.b, undone.b, 0.01f)
            assertTrue(document.redo() is CommandResult.Accepted)
            val redone = document.sample(DocPoint(15f, 8f), SampleMode.ACTIVE_LAYER).color
            val redoneChroma = maxOf(redone.r, redone.g, redone.b) - minOf(redone.r, redone.g, redone.b)
            assertTrue("redo reapplies the filter", redoneChroma < originalChroma)
        } finally {
            document.close()
            engine.close()
        }
    }
}
