package app.croptop.core.engine.api

/** Document work lifecycle events. Sequence values match [CommandResult.Accepted]. */
sealed interface EngineEvent {
    val sequence: Long

    /** The document state and history snapshot now contain the accepted edit. */
    data class Applied(override val sequence: Long, val label: String) : EngineEvent

    /** The latest composed viewport buffer for this edit is ready for the renderer. */
    data class Rendered(override val sequence: Long) : EngineEvent

    /** A real engine-reported fraction; omit it when the operation cannot estimate progress. */
    data class Progress(override val sequence: Long, val fraction: Float?) : EngineEvent {
        init {
            require(fraction == null || (fraction.isFinite() && fraction in 0f..1f))
        }
    }

    /** Work accepted by the engine could not complete; the message is suitable for the editor status UI. */
    data class Failed(override val sequence: Long, val message: String) : EngineEvent
}
