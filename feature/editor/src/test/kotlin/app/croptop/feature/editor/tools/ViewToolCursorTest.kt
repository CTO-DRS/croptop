package app.croptop.feature.editor.tools

import androidx.compose.ui.input.pointer.PointerIcon
import app.croptop.core.input.Modifiers
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ViewToolCursorTest {
    @Test
    fun `Move uses all scroll cursor`() {
        val tool = MoveTool({ null }, {}, { null }) { _, _ -> true }

        assertEquals(PointerIcon(android.view.PointerIcon.TYPE_ALL_SCROLL), tool.cursor(Modifiers.None))
    }

    @Test
    fun `Hand uses hand cursor`() {
        assertEquals(PointerIcon.Hand, HandTool().cursor(Modifiers.None))
    }

    @Test
    fun `Zoom cursor reflects zoom direction modifier`() {
        val tool = ZoomTool()

        assertEquals(PointerIcon(android.view.PointerIcon.TYPE_ZOOM_IN), tool.cursor(Modifiers.None))
        assertEquals(PointerIcon(android.view.PointerIcon.TYPE_ZOOM_OUT), tool.cursor(Modifiers(alt = true)))
    }
}
