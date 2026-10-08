package app.croptop.core.engine.fake

import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.LiquifyDab
import app.croptop.core.engine.api.LiquifyStroke
import app.croptop.core.engine.api.LiquifyTool
import app.croptop.core.model.DocSize
import app.croptop.core.model.NewDocumentSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FakeLiquifyTest {
    @Test
    fun liquifyRejectsUnrepresentableCoordinatesAndMotion() {
        assertIllegalArgument { LiquifyDab(Double.MAX_VALUE, 0.0, 10.0) }
        assertIllegalArgument { LiquifyDab(0.0, 0.0, 10.0, dx = Double.MAX_VALUE) }
    }

    @Test
    fun liquifyGestureIsOneLabeledUndoStep() {
        val engine = FakeEngine()
        val document = engine.newDocument(NewDocumentSpec("Fake Liquify", DocSize(40, 40)))
        try {
            val target = checkNotNull(document.layers.value.primary)
            val before = document.history.value.current
            val result = document.liquify(
                target,
                LiquifyStroke(LiquifyTool.FORWARD_WARP, listOf(LiquifyDab(20.0, 20.0, 14.0, dx = 4.0))),
            )
            assertTrue(result is CommandResult.Accepted)
            assertEquals(before + 1, document.history.value.current)
            assertEquals("Liquify", document.history.value.entries.last().label)
            assertTrue(document.undo() is CommandResult.Accepted)
            assertEquals(before, document.history.value.current)
        } finally {
            document.close()
            engine.close()
        }
    }

    private fun assertIllegalArgument(block: () -> Unit) {
        try {
            block()
            fail("Expected out-of-range Liquify input to be rejected")
        } catch (_: IllegalArgumentException) {
            // Typed input validation must reject values that overflow native grid coordinates.
        }
    }
}
