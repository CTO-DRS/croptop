package app.croptop.feature.editor.panels

import app.croptop.core.model.LayerId
import app.croptop.core.model.LayerKind
import app.croptop.core.model.LayerNode
import app.croptop.core.model.LayerTree
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LayerDropTest {
    private fun px(id: Long) = LayerNode(LayerId(id), "L$id", LayerKind.PIXEL)
    private val tree = LayerTree(
        listOf(
            px(1),
            LayerNode(LayerId(2), "G", LayerKind.GROUP, children = listOf(px(3), px(4))),
            px(5),
            px(6),
        ),
    )
    private fun id(v: Long) = LayerId(v)

    @Test
    fun `move down within root accounts for removal`() {
        assertEquals(DropTarget(null, 2), resolveDrop(tree, id(1), id(5), DropZone.AFTER))
    }

    @Test
    fun `move up within root`() {
        assertEquals(DropTarget(null, 0), resolveDrop(tree, id(6), id(1), DropZone.BEFORE))
    }

    @Test
    fun `no-op drops return null`() {
        assertNull(resolveDrop(tree, id(5), id(5), DropZone.BEFORE))
        assertNull(resolveDrop(tree, id(5), id(6), DropZone.BEFORE))
        assertNull(resolveDrop(tree, id(5), id(2), DropZone.AFTER))
    }

    @Test
    fun `into group and out of group`() {
        assertEquals(DropTarget(id(2), 0), resolveDrop(tree, id(6), id(2), DropZone.INTO))
        assertEquals(DropTarget(id(2), 1), resolveDrop(tree, id(1), id(3), DropZone.AFTER))
        assertEquals(DropTarget(null, 3), resolveDrop(tree, id(3), id(6), DropZone.BEFORE))
    }

    @Test
    fun `cannot drop into itself or non-group`() {
        assertNull(resolveDrop(tree, id(2), id(3), DropZone.AFTER))
        assertNull(resolveDrop(tree, id(1), id(5), DropZone.INTO))
    }
}
