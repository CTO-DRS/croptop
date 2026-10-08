package app.croptop.feature.editor.tools

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import app.croptop.core.model.BrushSettings
import app.croptop.core.model.RgbaColor
import app.croptop.core.model.ToolId
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrushToolCursorTest {
    @Test
    fun `caps lock toggles precision cursor only once on key up`() {
        val brush = BrushTool(ToolId.BRUSH, false, { BrushSettings() }, { RgbaColor.Black }, { _, _ -> null }, {})

        assertTrue(brush.handlePrecisionCursorKey(Key.CapsLock, KeyEventType.KeyDown))
        assertFalse(brush.precisionCursor)
        assertTrue(brush.handlePrecisionCursorKey(Key.CapsLock, KeyEventType.KeyUp))
        assertTrue(brush.precisionCursor)
        assertFalse(brush.handlePrecisionCursorKey(Key.A, KeyEventType.KeyUp))
        assertTrue(brush.precisionCursor)
        assertTrue(brush.handlePrecisionCursorKey(Key.CapsLock, KeyEventType.KeyUp))
        assertFalse(brush.precisionCursor)
    }
}
