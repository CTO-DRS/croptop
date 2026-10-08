package app.croptop.core.engine

import app.croptop.core.engine.api.RejectionCode

/** Native CPU renderer for rebuildable shape-layer RGBA8 caches. */
internal object NativeShapeRendererBridge {
    fun render(encodedBatch: ByteArray, width: Int, height: Int, memoryBudgetHandle: Long): ShapeLayerBridgeResult<Long> {
        if (width <= 0 || height <= 0) return failure(RejectionCode.INVALID_ARGUMENT, "Shape render dimensions must be positive")
        if (memoryBudgetHandle == 0L) return failure(RejectionCode.CLOSED, "Native memory budget is unavailable")
        return try {
            val store = nativeRenderBatch(encodedBatch, width, height, memoryBudgetHandle)
            if (store == 0L) {
                failure(RejectionCode.INVALID_ARGUMENT, nativeLastError().ifBlank { "Shape rendering failed" })
            } else {
                ShapeLayerBridgeResult.Success(store)
            }
        } catch (_: UnsatisfiedLinkError) {
            failure(RejectionCode.UNSUPPORTED, "Native shape renderer is unavailable")
        } catch (exception: RuntimeException) {
            failure(RejectionCode.INVALID_ARGUMENT, exception.message ?: "Shape rendering failed")
        }
    }

    private fun failure(code: RejectionCode, message: String) = ShapeLayerBridgeResult.Failure(code, message)

    private external fun nativeRenderBatch(encodedBatch: ByteArray, width: Int, height: Int, memoryBudgetHandle: Long): Long
    private external fun nativeLastError(): String
}
