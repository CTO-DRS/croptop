package app.croptop.core.engine.fake

import app.croptop.core.model.LayerId
import app.croptop.core.model.LayerNode
import app.croptop.core.model.LayerTree

/** Pure, immutable edits on [LayerTree] used by the fake engine. */
internal object LayerTreeOps {

    /** Removes [id] (with its children). Returns the new tree and the removed node, or null if absent. */
    fun remove(tree: LayerTree, id: LayerId): Pair<LayerTree, LayerNode>? {
        var removed: LayerNode? = null
        fun strip(nodes: List<LayerNode>): List<LayerNode> = nodes.mapNotNull { n ->
            if (n.id == id) {
                removed = n
                null
            } else {
                n.copy(children = strip(n.children))
            }
        }
        val layers = strip(tree.layers)
        val node = removed ?: return null
        val gone = buildSet { collectIds(node, this) }
        val active = tree.active - gone
        val primary = tree.primary?.takeIf { it !in gone } ?: active.firstOrNull()
        return tree.copy(layers = layers, active = active, primary = primary) to node
    }

    /**
     * Inserts [node] under [parentId] (null = root) at [index] in panel order (0 = top).
     * Returns null if the parent is missing, not a group, or the index is out of range.
     */
    fun insert(tree: LayerTree, node: LayerNode, parentId: LayerId?, index: Int): LayerTree? {
        if (parentId == null) {
            if (index !in 0..tree.layers.size) return null
            return tree.copy(layers = tree.layers.toMutableList().apply { add(index, node) })
        }
        val parent = tree.find(parentId) ?: return null
        if (!parent.isGroup || index !in 0..parent.children.size) return null
        return tree.copy(layers = map(tree.layers, parentId) { it.copy(children = it.children.toMutableList().apply { add(index, node) }) })
    }

    fun update(tree: LayerTree, id: LayerId, transform: (LayerNode) -> LayerNode): LayerTree =
        tree.copy(layers = map(tree.layers, id, transform))

    /** True when [candidate] is [ancestor] or nested inside it. */
    fun isWithin(tree: LayerTree, candidate: LayerId, ancestor: LayerId): Boolean {
        val a = tree.find(ancestor) ?: return false
        return LayerTree(listOf(a)).find(candidate) != null
    }

    /** Applies [f] to every node (depth-first), groups included. */
    fun mapAll(tree: LayerTree, f: (LayerNode) -> LayerNode): LayerTree {
        fun walk(nodes: List<LayerNode>): List<LayerNode> = nodes.map { n -> f(n.copy(children = walk(n.children))) }
        return tree.copy(layers = walk(tree.layers))
    }

    /** Siblings list containing [id] (root layers or its parent's children), top-most first. */
    fun siblingsOf(tree: LayerTree, id: LayerId): List<LayerNode> = tree.parentOf(id)?.children ?: tree.layers

    private fun map(nodes: List<LayerNode>, id: LayerId, transform: (LayerNode) -> LayerNode): List<LayerNode> =
        nodes.map { n -> if (n.id == id) transform(n) else n.copy(children = map(n.children, id, transform)) }

    private fun collectIds(n: LayerNode, into: MutableSet<LayerId>) {
        into += n.id
        n.children.forEach { collectIds(it, into) }
    }
}
