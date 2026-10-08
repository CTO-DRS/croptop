package app.croptop.core.engine

/** Native Magnetic Lasso sessions (`selection_magnetic`); handles are owned by the caller and must be destroyed. */
internal object NativeMagneticLassoBridge {
    external fun lastError(): String
    external fun begin(store: Long, x: Float, y: Float, width: Float, contrast: Float, frequency: Float): Long
    external fun update(session: Long, x: Float, y: Float): FloatArray?
    external fun fasten(session: Long, x: Float, y: Float): Boolean
    external fun undoFasten(session: Long): Boolean
    external fun committed(session: Long): FloatArray?
    external fun close(session: Long, antialias: Boolean): Long
    external fun destroy(session: Long)
}
