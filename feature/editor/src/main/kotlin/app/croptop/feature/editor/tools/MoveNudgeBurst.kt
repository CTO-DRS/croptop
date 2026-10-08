package app.croptop.feature.editor.tools

import androidx.compose.ui.input.key.Key

/** Groups repeated and overlapping arrow-key events into one Move-tool commit on release. */
class MoveNudgeBurst(
    private val commit: (dx: Float, dy: Float) -> Boolean,
) {
    private val pressedArrows = mutableSetOf<Key>()
    private var dx = 0f
    private var dy = 0f

    fun keyDown(key: Key, shift: Boolean): Boolean {
        val (x, y) = when (key) {
            Key.DirectionLeft -> -1f to 0f
            Key.DirectionRight -> 1f to 0f
            Key.DirectionUp -> 0f to -1f
            Key.DirectionDown -> 0f to 1f
            else -> return false
        }
        val step = if (shift) 10f else 1f
        dx += x * step
        dy += y * step
        pressedArrows += key
        return true
    }

    fun keyUp(key: Key): Boolean {
        if (key !in ArrowKeys) return false
        pressedArrows -= key
        if (pressedArrows.isEmpty()) flush()
        return true
    }

    /** Called when the canvas loses focus, the tool changes, or the editor leaves composition. */
    fun flush() {
        pressedArrows.clear()
        val deltaX = dx
        val deltaY = dy
        dx = 0f
        dy = 0f
        if (deltaX != 0f || deltaY != 0f) commit(deltaX, deltaY)
    }

    private companion object {
        val ArrowKeys = setOf(Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown)
    }
}
