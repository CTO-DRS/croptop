package app.croptop.core.engine.fake

import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.RejectionCode
import app.croptop.core.model.DocRect
import app.croptop.core.model.DocRectF
import app.croptop.core.model.DocSize
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.SelectionInfo
import app.croptop.core.model.SelectionModify
import app.croptop.core.model.SelectionShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FakeSelectionModifySessionTest {

    @Test
    fun sessionRejectsWhenNothingIsSelected() {
        val engine = FakeEngine()
        val doc = engine.newDocument(NewDocumentSpec(size = DocSize(100, 100)))

        val session = doc.beginModifySelection(SelectionModify.EXPAND)
        val result = session.update(10f)
        assertIs<CommandResult.Rejected>(result)
        assertEquals(RejectionCode.INVALID_ARGUMENT, result.code)
    }

    @Test
    fun sessionRejectsInvalidRadii() {
        val engine = FakeEngine()
        val doc = engine.newDocument(NewDocumentSpec(size = DocSize(100, 100)))
        doc.select(SelectionShape.Rect(DocRectF(10f, 10f, 50f, 50f)))

        val session = doc.beginModifySelection(SelectionModify.EXPAND)
        val neg = session.update(-5f)
        assertIs<CommandResult.Rejected>(neg)
        assertEquals(RejectionCode.INVALID_ARGUMENT, neg.code)

        val overflow = session.update(9999f)
        assertIs<CommandResult.Rejected>(overflow)
        assertEquals(RejectionCode.INVALID_ARGUMENT, overflow.code)
    }

    @Test
    fun sessionPreviewsWithoutHistoryAndCommitsOneStep() {
        val engine = FakeEngine()
        val doc = engine.newDocument(NewDocumentSpec(size = DocSize(100, 100)))
        doc.select(SelectionShape.Rect(DocRectF(20f, 20f, 40f, 40f)))
        val historyBefore = doc.history.value.entries.size

        val session = doc.beginModifySelection(SelectionModify.EXPAND)
        val updateResult = session.update(5f)
        assertIs<CommandResult.Accepted>(updateResult)

        val previewSelection = doc.selection.value
        assertIs<SelectionInfo.Active>(previewSelection)
        assertEquals(DocRect(15, 15, 45, 45), previewSelection.bounds)
        assertEquals(historyBefore, doc.history.value.entries.size)

        val commitResult = session.commit()
        assertIs<CommandResult.Accepted>(commitResult)
        assertEquals(historyBefore + 1, doc.history.value.entries.size)
        assertEquals("Expand", doc.history.value.entries.last().label)
    }

    @Test
    fun sessionCancelsAndRestoresPreviousSelection() {
        val engine = FakeEngine()
        val doc = engine.newDocument(NewDocumentSpec(size = DocSize(100, 100)))
        doc.select(SelectionShape.Rect(DocRectF(20f, 20f, 40f, 40f)))
        val initialSelection = doc.selection.value
        val historyBefore = doc.history.value.entries.size

        val session = doc.beginModifySelection(SelectionModify.CONTRACT)
        session.update(5f)
        val preview = doc.selection.value
        assertIs<SelectionInfo.Active>(preview)
        assertEquals(DocRect(25, 25, 35, 35), preview.bounds)

        session.cancel()
        assertEquals(initialSelection, doc.selection.value)
        assertEquals(historyBefore, doc.history.value.entries.size)
    }
}
