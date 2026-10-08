package app.croptop.core.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.EditCommand
import app.croptop.core.model.DocSize
import app.croptop.core.model.LayerId
import app.croptop.core.model.LayerKind
import app.croptop.core.model.NewDocumentSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeTransactionTest {
    @Test
    fun transactionGroupsCommandsIntoOneUndoStep() {
        val engine = NativeEngine()
        val document = engine.newDocument(NewDocumentSpec(name = "Transaction", size = DocSize(16, 16)))
        try {
            val first = LayerId(91_001)
            val second = LayerId(91_002)
            val baseline = document.history.value.current
            val result = document.transaction("Agent · setup") {
                execute(EditCommand.AddLayer(first, "One", LayerKind.PIXEL))
                execute(EditCommand.AddLayer(second, "Two", LayerKind.PIXEL))
            }
            assertNotNull(result as? CommandResult.Accepted)
            assertEquals(baseline + 1, document.history.value.current)
            assertEquals("Agent · setup", document.history.value.entries.last().label)
            assertNotNull(document.layers.value.find(first))
            assertNotNull(document.layers.value.find(second))
            assertNotNull(document.undo() as? CommandResult.Accepted)
            assertEquals(baseline, document.history.value.current)
            assertNull(document.layers.value.find(first))
            assertNull(document.layers.value.find(second))
        } finally {
            document.close()
            engine.close()
        }
    }
}
