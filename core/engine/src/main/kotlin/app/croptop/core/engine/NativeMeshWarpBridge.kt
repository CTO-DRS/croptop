package app.croptop.core.engine

/**
 * Edit ▸ Transform ▸ Warp for a layer's pixels (native mesh warp). The lattice is [columns] × [rows] control points laid evenly
 * over the layer in pixel-index space (identity: x = column · (width − 1) / (columns − 1), y likewise); [nodes] gives where each
 * point is dragged to. Returns a new native store handle the caller owns (`NativeSelectionBridge.destroyStore`); the source is
 * untouched, so a history step can keep both.
 */
internal object NativeMeshWarpBridge {
    class Mesh(val columns: Int, val rows: Int, val nodes: FloatArray) {
        init {
            require(columns in 2..64 && rows in 2..64) { "a warp mesh needs 2-64 columns and rows" }
            require(nodes.size == columns * rows * 2) { "expected ${columns * rows * 2} node coordinates, got ${nodes.size}" }
            require(nodes.all { it.isFinite() }) { "warp node coordinates must be finite" }
        }

        companion object {
            /** The undeformed mesh over a [width] × [height] layer. */
            fun identity(width: Int, height: Int, columns: Int = 4, rows: Int = 4): Mesh {
                val nodes = FloatArray(columns * rows * 2)
                for (i in 0 until rows) {
                    for (j in 0 until columns) {
                        nodes[(i * columns + j) * 2] = j * (width - 1f) / (columns - 1)
                        nodes[(i * columns + j) * 2 + 1] = i * (height - 1f) / (rows - 1)
                    }
                }
                return Mesh(columns, rows, nodes)
            }
        }
    }

    /** Warps [storeHandle] through [mesh]; [spacing] px between inverted-field samples (smaller = more exact, slower). */
    fun apply(storeHandle: Long, mesh: Mesh, spacing: Int = 8, workerThreads: Int = 0): Long {
        val result = nativeApply(storeHandle, mesh.columns, mesh.rows, mesh.nodes, spacing, workerThreads)
        check(result != 0L) { nativeLastError().ifBlank { "Mesh warp failed" } }
        return result
    }

    private external fun nativeLastError(): String
    private external fun nativeApply(storeHandle: Long, columns: Int, rows: Int, nodes: FloatArray, spacing: Int, workerThreads: Int): Long
}
