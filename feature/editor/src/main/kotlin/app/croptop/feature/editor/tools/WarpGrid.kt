package app.croptop.feature.editor.tools

import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocSize
import app.croptop.core.model.WarpMesh
import kotlin.math.hypot

/** The draggable lattice of Edit ▸ Transform ▸ Warp over the whole canvas; pure state so the drag rules are unit-testable. */
data class WarpGrid(val mesh: WarpMesh, val canvas: DocSize) {
    val isIdentity: Boolean get() = mesh.isIdentity(canvas.width, canvas.height)

    /** Index of the node within [radius] document pixels of [p] (the nearest one), or null. */
    fun nodeAt(p: DocPoint, radius: Float): Int? =
        mesh.nodes.indices.minByOrNull { hypot(mesh.nodes[it].x - p.x, mesh.nodes[it].y - p.y) }
            ?.takeIf { hypot(mesh.nodes[it].x - p.x, mesh.nodes[it].y - p.y) <= radius }

    /** [index] dragged to [to]; kept within a margin around the canvas so a runaway drag cannot produce a degenerate surface. */
    fun moved(index: Int, to: DocPoint): WarpGrid {
        val marginX = canvas.width * MARGIN
        val marginY = canvas.height * MARGIN
        val clamped = DocPoint(to.x.coerceIn(-marginX, canvas.width + marginX), to.y.coerceIn(-marginY, canvas.height + marginY))
        return copy(mesh = mesh.copy(nodes = mesh.nodes.mapIndexed { i, p -> if (i == index) clamped else p }))
    }

    fun reset(): WarpGrid = copy(mesh = WarpMesh.identity(canvas.width, canvas.height, mesh.columns, mesh.rows))

    /** A fresh undeformed lattice with a different density. */
    fun withDensity(columns: Int, rows: Int): WarpGrid = copy(mesh = WarpMesh.identity(canvas.width, canvas.height, columns, rows))

    companion object {
        const val MARGIN = 0.5f
        val DENSITIES = listOf(3 to 3, 4 to 4, 5 to 5, 8 to 8)

        fun identity(canvas: DocSize, columns: Int = 4, rows: Int = 4) = WarpGrid(WarpMesh.identity(canvas.width, canvas.height, columns, rows), canvas)
    }
}
