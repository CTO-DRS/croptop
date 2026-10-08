package app.croptop.feature.editor

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EditorShortcutTest {
    @Test
    fun hidePanelsTabIsLimitedToCanvasFocusAndPlainKeyDown() {
        fun matches(canvasFocused: Boolean, isTab: Boolean = true, isKeyDown: Boolean = true, shift: Boolean = false) =
            shouldTogglePanelsOnTab(canvasFocused, isTab, isKeyDown, shift, false, false, false)

        assertTrue(matches(canvasFocused = true))
        assertFalse(matches(canvasFocused = false)) // A focused text field keeps Tab for focus traversal.
        assertFalse(matches(canvasFocused = true, isTab = false))
        assertFalse(matches(canvasFocused = true, isKeyDown = false))
        assertFalse(matches(canvasFocused = true, shift = true))
        assertFalse(shouldTogglePanelsOnTab(true, true, true, false, true, false, false))
        assertFalse(shouldTogglePanelsOnTab(true, true, true, false, false, true, false))
        assertFalse(shouldTogglePanelsOnTab(true, true, true, false, false, false, true))
    }
}
