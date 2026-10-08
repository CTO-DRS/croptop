package app.croptop.core.engine

import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.EngineEvent
import app.croptop.core.engine.api.RefineEdgeBrushMode
import app.croptop.core.engine.api.RefineEdgeBrushSpec
import app.croptop.core.engine.api.RefineEdgeOutput
import app.croptop.core.engine.api.RefineEdgeViewMode
import app.croptop.core.model.BackgroundContents
import app.croptop.core.model.DocRectF
import app.croptop.core.model.DocSize
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.SelectionShape
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeRefineEdgeSessionTest {
    @Test
    fun viewModesScheduleRenderAndNewDocumentOutputCarriesRefinedPixels() = runBlocking {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec("Portrait", DocSize(48, 36), background = BackgroundContents.TRANSPARENT),
        )
        try {
            assertTrue(document.select(SelectionShape.Rect(DocRectF(4f, 5f, 24f, 25f)), antiAlias = false) is CommandResult.Accepted)
            val session = document.beginRefineEdge()
            assertEquals(RefineEdgeViewMode.OVERLAY, session.viewMode.value)
            val viewChanged = session.setViewMode(RefineEdgeViewMode.ON_BLACK) as CommandResult.Accepted
            assertEquals(RefineEdgeViewMode.ON_BLACK, session.viewMode.value)
            assertEquals(
                EngineEvent.Rendered(viewChanged.sequence),
                withTimeout(5_000) { document.events.first { it is EngineEvent.Rendered && it.sequence == viewChanged.sequence } },
            )
            assertTrue(session.update(app.croptop.core.engine.api.RefineEdgeSettings(radius = 2f)) is CommandResult.Accepted)
            assertTrue(session.setViewMode(RefineEdgeViewMode.BLACK_AND_WHITE) is CommandResult.Accepted)
            assertEquals(RefineEdgeViewMode.BLACK_AND_WHITE, session.viewMode.value)
            val add = session.beginBrushStroke(RefineEdgeBrushSpec(diameter = 10f, mode = RefineEdgeBrushMode.ADD))
            add.add(floatArrayOf(30f, 15f, 1f, 0f, 0f, 0f, 0f))
            assertTrue(add.end() is CommandResult.Accepted)
            val subtract = session.beginBrushStroke(RefineEdgeBrushSpec(diameter = 8f, mode = RefineEdgeBrushMode.SUBTRACT))
            subtract.add(floatArrayOf(10f, 10f, 1f, 0f, 0f, 0f, 1f))
            assertTrue(subtract.end() is CommandResult.Accepted)
            assertTrue(session.commit(RefineEdgeOutput.NEW_DOCUMENT) is CommandResult.Accepted)
            assertNotNull(session.outputDocument)
            val output = requireNotNull(session.outputDocument)
            assertEquals("Portrait Selection", output.info.value.name)
            assertEquals(DocSize(48, 36), output.info.value.size)
            assertEquals(1, output.layers.value.layers.size)
            output.close()
        } finally {
            document.close()
            engine.close()
        }
    }
}
