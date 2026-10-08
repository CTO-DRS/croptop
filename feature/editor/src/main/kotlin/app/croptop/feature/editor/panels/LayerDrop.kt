package app.croptop.feature.editor.panels

import app.croptop.core.model.LayerId
import app.croptop.core.model.LayerTree

enum class DropZone { BEFORE, AFTER, INTO }

/** Where a dragged layer lands: MoveLayer(parentId, index) where index is after removing the layer. */
data class DropTarget(val parentId: LayerId?, val index: Int)

/**
 * Resolves a drop of [dragged] relative to [over] (panel order, top first).
 * Returns null for no-op or invalid drops (onto itself, into its own descendants, INTO a non-group).
 */
fun resolveDrop(tree: LayerTree, dragged: LayerId, over: LayerId, zone: DropZone): DropTarget? {
    if (dragged == over) return null
    val draggedNode = tree.find(dragged) ?: return null
    if (LayerTree(listOf(draggedNode)).find(over) != null) return null // over is inside dragged
    val overNode = tree.find(over) ?: return null

    if (zone == DropZone.INTO) {
        if (!overNode.isGroup) return null
        return DropTarget(over, 0)
    }
    val parent = tree.parentOf(over)
    val siblings = (parent?.children ?: tree.layers).map { it.id }
    var index = siblings.indexOf(over) + if (zone == DropZone.AFTER) 1 else 0
    // Same parent: the engine removes the layer first, so later positions shift up by one.
    val from = siblings.indexOf(dragged)
    if (from in 0 until index) index -= 1
    if (from >= 0 && from == index) return null
    return DropTarget(parent?.id, index)
}
