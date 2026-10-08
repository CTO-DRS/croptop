package app.croptop.feature.editor.canvas

import androidx.compose.ui.unit.IntSize
import app.croptop.core.model.DocSize
import app.croptop.core.model.ViewportState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class CanvasViewportTest {
    private data class GestureEvent(val phase: String, val x: Int)

    @Test
    fun `initial fit uses settled compact viewport bounds`() {
        val fitted = initialViewportForSize(
            viewport = ViewportState(viewWidth = 480, viewHeight = 100),
            document = DocSize(640, 420),
            size = IntSize(480, 800),
            alreadyFit = false,
        )

        assertEquals(0.675f, fitted.zoom, 0.001f)
        assertEquals(480, fitted.viewWidth)
        assertEquals(800, fitted.viewHeight)
    }

    @Test
    fun `viewport resize after initial fit preserves user zoom and pan`() {
        val userViewport = ViewportState(zoom = 0.55f, panX = 27f, panY = -14f, viewWidth = 480, viewHeight = 800)
        val resized = initialViewportForSize(
            viewport = userViewport,
            document = DocSize(640, 420),
            size = IntSize(600, 900),
            alreadyFit = true,
        )

        assertEquals(0.55f, resized.zoom)
        assertEquals(27f, resized.panX)
        assertEquals(-14f, resized.panY)
        assertEquals(600, resized.viewWidth)
        assertEquals(900, resized.viewHeight)
    }

    @Test
    fun `brush HUD cleanup runs when pointer gesture is canceled`() = runTest {
        var visible = true

        val gesture = launch {
            val cleanup = BrushHudCleanup { visible = false }
            try {
                awaitCancellation()
            } finally {
                cleanup.close()
            }
        }

        runCurrent()
        assertTrue(visible)
        gesture.cancelAndJoin()
        assertFalse(visible)
    }

    @Test
    fun `lost pointer cancels active tool at its last known position exactly once`() {
        val received = mutableListOf<GestureEvent>()
        val lifecycle = PointerGestureLifecycle(
            initialEvent = GestureEvent("DOWN", 10),
            asCancel = { it.copy(phase = "CANCEL") },
            dispatch = { received += it },
        )

        lifecycle.send(GestureEvent("DOWN", 10))
        lifecycle.send(GestureEvent("MOVE", 24))
        lifecycle.cancel() // The pointer disappeared from the next event's changes.
        lifecycle.cancel() // The enclosing finally block is idempotent.

        assertEquals(
            listOf(GestureEvent("DOWN", 10), GestureEvent("MOVE", 24), GestureEvent("CANCEL", 24)),
            received,
        )
    }

    @Test
    fun `completed gesture does not send a cancellation from finally`() {
        val received = mutableListOf<GestureEvent>()
        val lifecycle = PointerGestureLifecycle(
            initialEvent = GestureEvent("DOWN", 1),
            asCancel = { it.copy(phase = "CANCEL") },
            dispatch = { received += it },
        )

        lifecycle.send(GestureEvent("DOWN", 1))
        lifecycle.finish(GestureEvent("UP", 5))
        lifecycle.cancel()

        assertEquals(listOf(GestureEvent("DOWN", 1), GestureEvent("UP", 5)), received)
    }

    @Test
    fun `viewport navigation feedback reports once after a changed gesture`() {
        val actions = mutableListOf<String>()
        val feedback = ViewportGestureFeedback(actions::add)

        feedback.begin("Pan canvas view")
        feedback.update(false)
        feedback.update(true)
        feedback.finish()
        feedback.finish()

        assertEquals(listOf("Pan canvas view"), actions)
    }

    @Test
    fun `viewport navigation feedback ignores no-op and canceled gestures`() {
        val actions = mutableListOf<String>()
        val feedback = ViewportGestureFeedback(actions::add)

        feedback.begin("Pan canvas view")
        feedback.finish()
        feedback.begin("Pan and zoom canvas view")
        feedback.update(true)
        feedback.finish(cancelled = true)

        assertTrue(actions.isEmpty())
    }

    @Test
    fun `wheel feedback coalesces samples until the scroll burst settles`() = runTest {
        val actions = mutableListOf<String>()
        val feedback = ViewportScrollFeedback(backgroundScope, settleMillis = 350, onActionCompleted = actions::add)

        feedback.schedule("Scroll canvas view")
        runCurrent()
        advanceTimeBy(200)
        feedback.schedule("Zoom canvas view")
        runCurrent()
        advanceTimeBy(349)
        assertTrue(actions.isEmpty())
        advanceTimeBy(1)
        runCurrent()

        assertEquals(listOf("Zoom canvas view"), actions)
    }
}
