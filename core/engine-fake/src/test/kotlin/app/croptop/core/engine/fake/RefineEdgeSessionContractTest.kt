package app.croptop.core.engine.fake

import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.RefineEdgeBrushSpec
import app.croptop.core.engine.api.RefineEdgeOutput
import app.croptop.core.engine.api.RefineEdgeViewMode
import app.croptop.core.model.DocRectF
import app.croptop.core.model.DocSize
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.SelectionShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class RefineEdgeSessionContractTest {
    @Test
    fun viewModesAndNewDocumentOutputAreAvailableFromSession() {
        val engine = FakeEngine()
        val document = engine.newDocument(NewDocumentSpec("Portrait", DocSize(48, 36)))
        try {
            assertIs<CommandResult.Accepted>(document.select(SelectionShape.Rect(DocRectF(4f, 5f, 24f, 25f))))
            val session = document.beginRefineEdge()
            assertEquals(RefineEdgeViewMode.OVERLAY, session.viewMode.value)
            assertIs<CommandResult.Accepted>(session.setViewMode(RefineEdgeViewMode.ON_BLACK))
            assertEquals(RefineEdgeViewMode.ON_BLACK, session.viewMode.value)
            val brush = session.beginBrushStroke(RefineEdgeBrushSpec())
            brush.add(floatArrayOf(12f, 12f, 1f, 0f, 0f, 0f, 0f))
            assertIs<CommandResult.Accepted>(brush.end())
            assertIs<CommandResult.Accepted>(session.commit(RefineEdgeOutput.NEW_DOCUMENT))
            val output = assertNotNull(session.outputDocument)
            assertEquals(DocSize(48, 36), output.info.value.size)
            assertEquals("Portrait Selection", output.info.value.name)
            output.close()
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun refineEdgeSessionOutputsToLayerMaskAndNewLayer() {
        val engine = FakeEngine()
        val document = engine.newDocument(NewDocumentSpec("Portrait", DocSize(48, 36)))
        try {
            assertIs<CommandResult.Accepted>(document.select(SelectionShape.Rect(DocRectF(4f, 5f, 24f, 25f))))
            val session = document.beginRefineEdge()
            assertIs<CommandResult.Accepted>(session.commit(RefineEdgeOutput.LAYER_MASK))
            val primary = assertNotNull(document.layers.value.primary)
            val primaryNode = assertNotNull(document.layers.value.find(primary))
            assertNotNull(primaryNode.mask)

            val session2 = document.beginRefineEdge()
            val layersBefore = document.layers.value.flatten().size
            assertIs<CommandResult.Accepted>(session2.commit(RefineEdgeOutput.NEW_LAYER))
            assertEquals(layersBefore + 1, document.layers.value.flatten().size)
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun refineEdgeSessionCancelsCleanly() {
        val engine = FakeEngine()
        val document = engine.newDocument(NewDocumentSpec("Portrait", DocSize(48, 36)))
        try {
            assertIs<CommandResult.Accepted>(document.select(SelectionShape.Rect(DocRectF(4f, 5f, 24f, 25f))))
            val initialSelection = document.selection.value
            val session = document.beginRefineEdge()
            assertIs<CommandResult.Accepted>(session.setViewMode(RefineEdgeViewMode.ON_WHITE))
            session.cancel()
            assertEquals(initialSelection, document.selection.value)
        } finally {
            document.close()
            engine.close()
        }
    }
}
