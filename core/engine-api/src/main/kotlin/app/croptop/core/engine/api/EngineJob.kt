package app.croptop.core.engine.api

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A long-running engine operation, such as a segmentation model run. It starts when returned, runs off
 * the calling thread, and reports real progress or null when it cannot estimate. Its result is one history
 * step like any command.
 */
interface EngineJob {
    /** 0–1, or null while the engine can't estimate. */
    val progress: StateFlow<Float?>

    /**
     * The terminal result once done. A cancellation request returns [RejectionCode.CANCELLED] if it wins before commit.
     * Cancelling the coroutine awaiting this method does not cancel the operation; call [cancel] and await its
     * terminal result to observe the engine's cleanup acknowledgment.
     */
    suspend fun await(): CommandResult

    /** Requests cancellation. [await] remains the acknowledgment barrier; no effect once finished. */
    fun cancel()

    companion object {
        /** A job that has already finished, e.g. a typed rejection or an instant fake result. */
        fun completed(result: CommandResult): EngineJob = CompletedJob(result)
    }
}

private class CompletedJob(private val result: CommandResult) : EngineJob {
    override val progress: StateFlow<Float?> = MutableStateFlow<Float?>(1f).asStateFlow()

    override suspend fun await(): CommandResult = result

    override fun cancel() = Unit
}
