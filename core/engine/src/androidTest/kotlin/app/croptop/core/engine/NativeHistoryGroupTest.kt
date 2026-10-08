package app.croptop.core.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.EditCommand
import app.croptop.core.model.BackgroundContents
import app.croptop.core.model.DocSize
import app.croptop.core.model.FillSource
import app.croptop.core.model.LayerId
import app.croptop.core.model.LayerKind
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.RgbaColor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeHistoryGroupTest {
    private fun newDocument(engine: NativeEngine) = engine.newDocument(NewDocumentSpec("Group", DocSize(64, 48), background = BackgroundContents.WHITE))

    @Test
    fun aGroupOfEditsIsOneUndoStepAndCancelLeavesNoTrace() = runBlocking {
        val engine = NativeEngine()
        val document = newDocument(engine)
        try {
            val start = document.history.value
            assertTrue(document.beginHistoryGroup("Big job") is CommandResult.Accepted)
            assertTrue("groups do not nest", document.beginHistoryGroup("Again") is CommandResult.Rejected)
            assertTrue(document.resizeImage(DocSize(128, 96)) is CommandResult.Accepted)
            assertTrue(document.execute(EditCommand.AddLayer(LayerId(900_100), "Result", LayerKind.PIXEL)) is CommandResult.Accepted)
            assertTrue(document.fill(FillSource.Solid(RgbaColor(1f, 0f, 0f, 1f))) is CommandResult.Accepted)
            assertTrue(document.endHistoryGroup() is CommandResult.Accepted)
            assertEquals(start.entries.size + 1, document.history.value.entries.size)
            assertEquals("Big job", document.history.value.undoLabel)
            assertEquals(DocSize(128, 96), document.info.value.size)
            assertTrue(document.undo() is CommandResult.Accepted)
            assertEquals("one undo reverts the resize and the layer together", DocSize(64, 48), document.info.value.size)
            assertEquals(1, document.layers.value.layers.size)
            assertTrue(document.redo() is CommandResult.Accepted)
            assertEquals(DocSize(128, 96), document.info.value.size)
            assertTrue(document.undo() is CommandResult.Accepted)

            val clean = document.history.value
            assertTrue(document.beginHistoryGroup("Abandoned") is CommandResult.Accepted)
            assertTrue(document.resizeImage(DocSize(200, 150)) is CommandResult.Accepted)
            assertTrue(document.execute(EditCommand.AddLayer(LayerId(900_101), "Gone", LayerKind.PIXEL)) is CommandResult.Accepted)
            assertTrue(document.cancelHistoryGroup() is CommandResult.Accepted)
            assertEquals(DocSize(64, 48), document.info.value.size)
            assertEquals(1, document.layers.value.layers.size)
            assertEquals(clean.current, document.history.value.current)
            assertTrue("nothing from the abandoned group can be redone", !document.history.value.canRedo || document.history.value.entries.size <= clean.entries.size)
            assertTrue(document.endHistoryGroup() is CommandResult.Rejected)
            // The document still edits normally afterwards.
            assertTrue(document.fill(FillSource.Solid(RgbaColor(0f, 1f, 0f, 1f))) is CommandResult.Accepted)
        } finally {
            document.close()
            engine.close()
        }
    }
}
