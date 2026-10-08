package app.croptop.core.engine

import app.croptop.core.model.LayerId
import app.croptop.core.model.LayerNode
import app.croptop.core.model.LayerTree

/** Immutable layer-tree edits used by the native document command path. */
internal object NativeLayerTreeOps {
    fun remove(tree: LayerTree, id: LayerId): Pair<LayerTree, LayerNode>? {
        var removed: LayerNode? = null
        fun strip(nodes: List<LayerNode>): List<LayerNode> = nodes.mapNotNull { node ->
            if (node.id == id) {
                removed = node
                null
            } else {
                node.copy(children = strip(node.children))
            }
        }
        val layers = strip(tree.layers)
        val removedNode = removed ?: return null
        val removedIds = buildSet {
            fun collect(node: LayerNode) {
                add(node.id)
                node.children.forEach(::collect)
            }
            collect(removedNode)
        }
        val active = tree.active - removedIds
        val primary = tree.primary?.takeUnless(removedIds::contains) ?: active.firstOrNull()
        return tree.copy(layers = layers, active = active, primary = primary) to removedNode
    }

    fun insert(tree: LayerTree, node: LayerNode, parentId: LayerId?, index: Int): LayerTree? {
        if (parentId == null) {
            if (index !in 0..tree.layers.size) return null
            return tree.copy(layers = tree.layers.toMutableList().apply { add(index, node) })
        }
        val parent = tree.find(parentId) ?: return null
        if (!parent.isGroup || index !in 0..parent.children.size) return null
        return tree.copy(
            layers = update(tree.layers, parentId) { target ->
                target.copy(children = target.children.toMutableList().apply { add(index, node) })
            },
        )
    }

    fun update(tree: LayerTree, id: LayerId, transform: (LayerNode) -> LayerNode): LayerTree =
        tree.copy(layers = update(tree.layers, id, transform))

    fun isWithin(tree: LayerTree, candidate: LayerId, ancestor: LayerId): Boolean {
        val ancestorNode = tree.find(ancestor) ?: return false
        return LayerTree(listOf(ancestorNode)).find(candidate) != null
    }

    private fun update(nodes: List<LayerNode>, id: LayerId, transform: (LayerNode) -> LayerNode): List<LayerNode> =
        nodes.map { node ->
            if (node.id == id) transform(node) else node.copy(children = update(node.children, id, transform))
        }
}
