package app.croptop.feature.editor.tools

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MoveNudgeBurstTest {
    @Test
    fun `arrow repeats retain one pixel steps and commit once on release`() {
        val state = RecordingNudge()
        val burst = state.burst

        repeat(4) { assertTrue(burst.keyDown(Key.DirectionRight, shift = false)) }
        assertEquals(0, state.commits)
        assertTrue(burst.keyUp(Key.DirectionRight))

        assertEquals(1, state.commits)
        assertEquals(4f to 0f, state.offsets.single())
    }

    @Test
    fun `shift nudge uses ten pixels and overlapping arrows form one burst`() {
        val state = RecordingNudge()
        val burst = state.burst

        assertTrue(burst.keyDown(Key.DirectionLeft, shift = false))
        assertTrue(burst.keyDown(Key.DirectionDown, shift = true))
        assertTrue(burst.keyUp(Key.DirectionLeft))
        assertEquals(0, state.commits)
        assertTrue(burst.keyUp(Key.DirectionDown))

        assertEquals(1, state.commits)
        assertEquals(-1f to 10f, state.offsets.single())
    }

    @Test
    fun `focus or tool loss flushes pending arrows and unknown keys are ignored`() {
        val state = RecordingNudge()
        val burst = state.burst

        assertFalse(burst.keyDown(Key.A, shift = false))
        assertTrue(burst.keyDown(Key.DirectionUp, shift = true))
        burst.flush()
        burst.flush()

        assertEquals(1, state.commits)
        assertEquals(0f to -10f, state.offsets.single())
    }

    private class RecordingNudge {
        val offsets = mutableListOf<Pair<Float, Float>>()
        var commits = 0
        val burst = MoveNudgeBurst { dx, dy ->
            commits++
            offsets += dx to dy
            true
        }
    }
}
