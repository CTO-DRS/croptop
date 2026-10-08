package app.croptop.core.engine

/**
 * Puppet Warp (native `puppet_warp`): a session over one layer. Add pins, drag them with [movePin], call [solve] after each pointer move (it is
 * warm-started, so a drag converges in a few rounds), draw the deformed [vertices] over [triangles] for the live preview and [render] the
 * final layer. Close the session when done.
 */
internal object NativePuppetWarpBridge {
    enum class Mode { RIGID, NORMAL, DISTORT }
    enum class Density { FEWER, NORMAL, MORE }

    class Session(layerHandle: Long, density: Density = Density.NORMAL, expansion: Float = 2f) : AutoCloseable {
        private var handle = nativeBuild(layerHandle, density.ordinal, expansion).also {
            check(it != 0L) { nativeLastError().ifBlank { "Puppet Warp failed" } }
        }

        private fun live(): Long = handle.also { check(it != 0L) { "Puppet Warp session is closed" } }

        /** Index of the new pin, or -1 when (x, y) is off the mesh. */
        fun addPin(x: Double, y: Double): Int = nativeAddPin(live(), x, y)
        fun removePin(index: Int): Boolean = nativeRemovePin(live(), index)
        val pinCount: Int get() = nativePinCount(live())

        fun movePin(index: Int, x: Double, y: Double, depth: Int = 0) {
            check(nativeMovePin(live(), index, x, y, depth) == 1L) { nativeLastError().ifBlank { "No such pin" } }
        }

        /** Rounds run by the solver. */
        fun solve(mode: Mode = Mode.NORMAL, maxIterations: Int = 64, tolerance: Double = 0.02): Int {
            val rounds = nativeSolve(live(), mode.ordinal, maxIterations, tolerance)
            check(rounds >= 0) { nativeLastError().ifBlank { "Solve failed" } }
            return rounds
        }

        /** Deformed vertex positions, x then y per vertex. */
        fun vertices(): FloatArray = checkNotNull(nativeDeformedVertices(live()))
        fun restVertices(): FloatArray = checkNotNull(nativeRestVertices(live()))

        /** Vertex indices, three per triangle. */
        fun triangles(): IntArray = checkNotNull(nativeTriangles(live()))

        /** A new store with the layer rendered through the deformation; the caller owns it. */
        fun render(workerThreads: Int = 0): Long {
            val out = nativeRender(live(), workerThreads)
            check(out != 0L) { nativeLastError().ifBlank { "Puppet Warp render failed" } }
            return out
        }

        override fun close() {
            if (handle != 0L) nativeDestroy(handle)
            handle = 0L
        }
    }

    private external fun nativeLastError(): String
    private external fun nativeBuild(layerHandle: Long, density: Int, expansion: Float): Long
    private external fun nativeDestroy(mesh: Long)
    private external fun nativeAddPin(mesh: Long, x: Double, y: Double): Int
    private external fun nativeRemovePin(mesh: Long, index: Int): Boolean
    private external fun nativePinCount(mesh: Long): Int
    private external fun nativeMovePin(mesh: Long, index: Int, x: Double, y: Double, depth: Int): Long
    private external fun nativeSolve(mesh: Long, mode: Int, maxIterations: Int, tolerance: Double): Int
    private external fun nativeDeformedVertices(mesh: Long): FloatArray?
    private external fun nativeRestVertices(mesh: Long): FloatArray?
    private external fun nativeTriangles(mesh: Long): IntArray?
    private external fun nativeRender(mesh: Long, workerThreads: Int): Long
}
