package app.croptop.core.engine

import app.croptop.core.engine.api.CommandResult
import app.croptop.core.model.BackgroundContents
import app.croptop.core.model.DocRectF
import app.croptop.core.model.DocSize
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.SelectionInfo
import app.croptop.core.model.SelectionModify
import app.croptop.core.model.SelectionOp
import app.croptop.core.model.SelectionShape
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeSelectionBudgetBridgeTest {
    @Test
    fun allocatorAwareCombineInvertAndEveryModifyOperationReachNativeEngine() {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec("Allocator-aware selection", DocSize(64, 64), background = BackgroundContents.TRANSPARENT),
        )
        try {
            assertAccepted(document.select(SelectionShape.Rect(DocRectF(8f, 8f, 32f, 32f)), antiAlias = false))
            assertTrue("replace installs a selection", document.selection.value is SelectionInfo.Active)
            assertAccepted(document.select(SelectionShape.Ellipse(DocRectF(16f, 16f, 48f, 48f)), SelectionOp.ADD))
            assertTrue("add retains selected pixels", document.selection.value is SelectionInfo.Active)
            assertAccepted(document.select(SelectionShape.Rect(DocRectF(20f, 20f, 24f, 24f)), SelectionOp.SUBTRACT))
            assertTrue("subtract retains uncovered pixels", document.selection.value is SelectionInfo.Active)
            assertAccepted(document.select(SelectionShape.Rect(DocRectF(12f, 12f, 44f, 44f)), SelectionOp.INTERSECT))
            assertTrue("intersect retains overlapping pixels", document.selection.value is SelectionInfo.Active)

            SelectionModify.entries.forEach { operation ->
                assertAccepted(document.select(SelectionShape.Rect(DocRectF(12f, 12f, 40f, 40f)), antiAlias = false))
                assertAccepted(document.modifySelection(operation, 2f))
                assertTrue("$operation retains mask pixels", document.selection.value is SelectionInfo.Active)
            }
            assertAccepted(document.invertSelection())
        } finally {
            document.close()
            engine.close()
        }
    }

    private fun assertAccepted(result: CommandResult) {
        assertTrue("native selection operation rejected: $result", result is CommandResult.Accepted)
    }
}
