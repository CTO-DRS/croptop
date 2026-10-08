package app.croptop.feature.editor.tools

import app.croptop.core.input.Modifiers
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocSize
import app.croptop.core.model.LayerId
import app.croptop.core.model.ViewPoint
import app.croptop.core.model.ViewportState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TextToolTest {
    private class Ctx(override var viewport: ViewportState = ViewportState(zoom = 1f, viewWidth = 1000, viewHeight = 800)) : ToolContext {
        override val document = DocSize(4000, 3000)

        override fun updateViewport(state: ViewportState) {
            viewport = state
        }
    }

    private fun ev(phase: PointerPhase, view: ViewPoint, down: ViewPoint, cancelled: Boolean = false) =
        ToolPointerEvent(phase, view, DocPoint(view.x, view.y), 1f, Modifiers.None, down, cancelled = cancelled)

    private fun tool(onPlace: (TextPlacement) -> Unit, textAt: (DocPoint) -> LayerId? = { null }) = TextTool(textAt, onPlace)

    @Test
    fun `a click places point text where it landed`() {
        val placed = mutableListOf<TextPlacement>()
        val t = tool(placed::add)
        val p = ViewPoint(300f, 200f)
        t.onPointer(ev(PointerPhase.DOWN, p, p), Ctx())
        t.onPointer(ev(PointerPhase.UP, p, p), Ctx())
        assertEquals(listOf(TextPlacement(DocPoint(300f, 200f), null, null)), placed)
    }

    @Test
    fun `a drag defines a paragraph box from its top left corner, whichever way it is dragged`() {
        val placed = mutableListOf<TextPlacement>()
        val t = tool(placed::add)
        val down = ViewPoint(500f, 400f)
        t.onPointer(ev(PointerPhase.DOWN, down, down), Ctx())
        t.onPointer(ev(PointerPhase.MOVE, ViewPoint(300f, 450f), down), Ctx())
        t.onPointer(ev(PointerPhase.UP, ViewPoint(300f, 450f), down), Ctx())
        assertEquals(TextPlacement(DocPoint(300f, 400f), 200f, null), placed.single())
    }

    @Test
    fun `a narrow drag is a click, and a cancelled gesture places nothing`() {
        val placed = mutableListOf<TextPlacement>()
        val t = tool(placed::add)
        val down = ViewPoint(100f, 100f)
        val end = ViewPoint(110f, 160f)
        t.onPointer(ev(PointerPhase.DOWN, down, down), Ctx())
        t.onPointer(ev(PointerPhase.UP, end, down), Ctx())
        assertNull(placed.single().boxWidth, "10 px wide is below the minimum box width")
        placed.clear()
        t.onPointer(ev(PointerPhase.DOWN, down, down), Ctx())
        t.onPointer(ev(PointerPhase.UP, end, down, cancelled = true), Ctx())
        assertTrue(placed.isEmpty())
    }

    @Test
    fun `clicking existing text edits it, but dragging over it still makes a new box`() {
        val placed = mutableListOf<TextPlacement>()
        val existing = LayerId(7)
        val t = tool(placed::add, textAt = { existing })
        val p = ViewPoint(40f, 40f)
        t.onPointer(ev(PointerPhase.DOWN, p, p), Ctx())
        t.onPointer(ev(PointerPhase.UP, p, p), Ctx())
        assertEquals(existing, placed.single().editing)
        placed.clear()
        t.onPointer(ev(PointerPhase.DOWN, p, p), Ctx())
        t.onPointer(ev(PointerPhase.MOVE, ViewPoint(300f, 200f), p), Ctx())
        t.onPointer(ev(PointerPhase.UP, ViewPoint(300f, 200f), p), Ctx())
        assertNull(placed.single().editing)
        assertEquals(260f, placed.single().boxWidth)
    }

    @Test
    fun `the minimum box width scales with the zoom`() {
        val zoomedOut = TextTool.placementFor(DocPoint(0f, 0f), DocPoint(100f, 50f), dragged = true, zoom = 0.1f)
        assertNull(zoomedOut?.boxWidth, "100 document px at 10% is only 10 px on screen")
        val zoomedIn = TextTool.placementFor(DocPoint(0f, 0f), DocPoint(100f, 50f), dragged = true, zoom = 1f)
        assertEquals(100f, zoomedIn?.boxWidth)
    }

    @Test
    fun `presses on the open editor belong to its field, not the tool`() {
        val placed = mutableListOf<TextPlacement>()
        val bounds = androidx.compose.ui.geometry.Rect(100f, 100f, 400f, 300f)
        val t = TextTool({ null }, placed::add, editorBounds = { bounds })
        val inside = ViewPoint(200f, 200f)
        assertTrue(!t.onPointer(ev(PointerPhase.DOWN, inside, inside), Ctx()))
        assertTrue(!t.onPointer(ev(PointerPhase.UP, inside, inside), Ctx()))
        assertTrue(placed.isEmpty())
        val outside = ViewPoint(600f, 500f)
        t.onPointer(ev(PointerPhase.DOWN, outside, outside), Ctx())
        t.onPointer(ev(PointerPhase.UP, outside, outside), Ctx())
        assertEquals(1, placed.size, "the gesture after an ignored one is handled normally")
    }
}
