package app.croptop.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelTest {
    private val tree = LayerTree(
        layers = listOf(
            LayerNode(LayerId(1), "Top", LayerKind.PIXEL),
            LayerNode(
                LayerId(2),
                "Group",
                LayerKind.GROUP,
                children = listOf(
                    LayerNode(LayerId(3), "Curves", LayerKind.ADJUSTMENT),
                    LayerNode(LayerId(4), "Inner", LayerKind.GROUP, children = listOf(LayerNode(LayerId(5), "Deep", LayerKind.PIXEL))),
                ),
            ),
            LayerNode(LayerId(6), "Background", LayerKind.PIXEL, locks = LayerLocks(transparency = true, position = true)),
        ),
    )

    @Test
    fun `flatten is panel order`() {
        assertEquals(listOf(1L, 2, 3, 4, 5, 6), tree.flatten().map { it.id.value })
        assertEquals(6, tree.size)
    }

    @Test
    fun `parent and depth`() {
        assertEquals(LayerId(4), tree.parentOf(LayerId(5))?.id)
        assertEquals(2, tree.depthOf(LayerId(5)))
        assertEquals(0, tree.depthOf(LayerId(1)))
        assertNull(tree.parentOf(LayerId(1)))
    }

    @Test
    fun `history undo redo labels`() {
        val h = HistoryState(listOf(HistoryEntry("Open"), HistoryEntry("Brush"), HistoryEntry("Curves")), current = 1)
        assertTrue(h.canUndo)
        assertTrue(h.canRedo)
        assertEquals("Brush", h.undoLabel)
        assertEquals("Curves", h.redoLabel)
        assertFalse(HistoryState().canUndo)
    }

    @Test
    fun `hex parsing`() {
        assertEquals(0xFF4C8DFF.toInt(), RgbaColor.parseHex("#4C8DFF")!!.toArgb())
        assertEquals(0xFFFFFFFF.toInt(), RgbaColor.parseHex("fff")!!.toArgb())
        assertEquals("4C8DFF", RgbaColor.parseHex("4c8dff")!!.hex)
        assertNull(RgbaColor.parseHex("xyz123"))
        assertNull(RgbaColor.parseHex("#12345"))
    }

    @Test
    fun `tool cycling stays in key group`() {
        assertEquals(ToolId.ELLIPSE_MARQUEE, ToolId.cycle(ToolId.RECT_MARQUEE))
        assertEquals(ToolId.RECT_MARQUEE, ToolId.cycle(ToolId.ELLIPSE_MARQUEE))
        assertEquals(ToolId.BRUSH, ToolId.cycle(ToolId.BRUSH))
    }

    @Test
    fun `layer locks`() {
        assertTrue(LayerLocks.All.all)
        assertFalse(tree.find(LayerId(6))!!.locks.all)
    }
}
