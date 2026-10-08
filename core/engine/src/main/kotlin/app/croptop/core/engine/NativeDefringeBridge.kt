package app.croptop.core.engine

/** Manual purple/green edge-fringe removal over a layer, optionally limited by an A16 selection. */
internal object NativeDefringeBridge {
    data class Spec(
        val purpleAmount: Float = 0f,
        val purpleHueLow: Float = 270f,
        val purpleHueHigh: Float = 340f,
        val greenAmount: Float = 0f,
        val greenHueLow: Float = 110f,
        val greenHueHigh: Float = 170f,
        val edgeThreshold: Float = 0.08f,
    )

    /** Returns an owned native tile-store handle; destroy it with [NativeSelectionBridge.destroyStore]. */
    fun apply(layerHandle: Long, spec: Spec, selectionHandle: Long = 0L, workerThreads: Int = 0): Long {
        val output = nativeApply(
            layerHandle,
            selectionHandle,
            spec.purpleAmount,
            spec.purpleHueLow,
            spec.purpleHueHigh,
            spec.greenAmount,
            spec.greenHueLow,
            spec.greenHueHigh,
            spec.edgeThreshold,
            workerThreads,
        )
        check(output != 0L) { nativeLastError().ifBlank { "Defringe failed" } }
        return output
    }

    private external fun nativeLastError(): String
    private external fun nativeApply(
        sourceHandle: Long,
        maskHandle: Long,
        purpleAmount: Float,
        purpleHueLow: Float,
        purpleHueHigh: Float,
        greenAmount: Float,
        greenHueLow: Float,
        greenHueHigh: Float,
        edgeThreshold: Float,
        workerThreads: Int,
    ): Long
}
