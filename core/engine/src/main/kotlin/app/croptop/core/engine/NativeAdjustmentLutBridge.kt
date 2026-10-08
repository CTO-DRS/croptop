package app.croptop.core.engine

/**
 * Optional slider-preview path. `encodedValues` uses the same per-adjustment block layout
 * documented in native `adjustment_lut_bridge.h`; existing NativeEngine CPU apply/commit calls
 * remain authoritative. A null result means the block is invalid or the adjustment is spatial.
 */
internal object NativeAdjustmentLutBridge {
    /** Returns size³ RGBA32F texels, red varying fastest, alpha fixed at 1. */
    external fun nativeBakeAdjustmentLut(specId: String, encodedValues: FloatArray, lutSize: Int): FloatArray?

    /** Diagnostic for a null return; thread-local on the calling native thread. */
    external fun nativeAdjustmentLutLastError(): String
}
