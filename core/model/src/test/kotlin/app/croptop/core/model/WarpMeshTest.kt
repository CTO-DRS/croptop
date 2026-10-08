package app.croptop.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WarpMeshTest {
    @Test
    fun identityLatticeSpansTheCanvasEdgeToEdge() {
        val mesh = WarpMesh.identity(101, 51, 3, 3)
        assertEquals(DocPoint(0f, 0f), mesh.nodes.first())
        assertEquals(DocPoint(100f, 50f), mesh.nodes.last())
        assertEquals(DocPoint(50f, 25f), mesh.nodes[4])
        assertTrue(mesh.isIdentity(101, 51))
    }

    @Test
    fun movedNodeIsNotIdentity() {
        val mesh = WarpMesh.identity(100, 100, 4, 4)
        val moved = mesh.copy(nodes = mesh.nodes.mapIndexed { i, p -> if (i == 5) DocPoint(p.x + 3f, p.y) else p })
        assertFalse(moved.isIdentity(100, 100))
    }

    @Test
    fun validatesShape() {
        assertFailsWith<IllegalArgumentException> { WarpMesh(1, 4, emptyList()) }
        assertFailsWith<IllegalArgumentException> { WarpMesh(2, 2, listOf(DocPoint(0f, 0f))) }
        assertFailsWith<IllegalArgumentException> { WarpMesh(2, 2, List(4) { DocPoint(Float.NaN, 0f) }) }
    }
}
