package app.croptop.feature.editor.tools

import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WarpGridTest {
    private val canvas = DocSize(300, 200)

    @Test
    fun startsAsIdentityAndPicksTheNearestNodeWithinReach() {
        val grid = WarpGrid.identity(canvas, 4, 4)
        assertTrue(grid.isIdentity)
        assertEquals(0, grid.nodeAt(DocPoint(3f, 2f), 10f))
        assertEquals(5, grid.nodeAt(DocPoint(100f + 4f, 66.3f + 3f), 12f))
        assertNull(grid.nodeAt(DocPoint(50f, 33f), 10f))
    }

    @Test
    fun dragMovesOnlyThatNodeAndResetRestoresIdentity() {
        val grid = WarpGrid.identity(canvas, 3, 3)
        val moved = grid.moved(4, DocPoint(160f, 90f))
        assertFalse(moved.isIdentity)
        assertEquals(DocPoint(160f, 90f), moved.mesh.nodes[4])
        assertEquals(grid.mesh.nodes[0], moved.mesh.nodes[0])
        assertTrue(moved.reset().isIdentity)
    }

    @Test
    fun dragIsClampedNearTheCanvas() {
        val moved = WarpGrid.identity(canvas).moved(0, DocPoint(-9999f, 9999f))
        assertEquals(-150f, moved.mesh.nodes[0].x)
        assertEquals(300f, moved.mesh.nodes[0].y)
    }

    @Test
    fun densityChangeDropsEdits() {
        val edited = WarpGrid.identity(canvas).moved(1, DocPoint(90f, 20f))
        val denser = edited.withDensity(5, 5)
        assertTrue(denser.isIdentity)
        assertEquals(25, denser.mesh.nodes.size)
    }
}
