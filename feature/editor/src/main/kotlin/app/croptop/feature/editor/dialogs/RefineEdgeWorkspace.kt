package app.croptop.feature.editor.dialogs

import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.RefineEdgeOutput
import app.croptop.core.engine.api.RefineEdgeSession
import app.croptop.core.engine.api.RefineEdgeSettings
import app.croptop.core.engine.api.RefineEdgeViewMode
import app.croptop.core.engine.api.RejectionCode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** Serializes blocking engine calls while coalescing slider changes into the latest preview. */
internal class RefineEdgeWorkspace(
    private val session: RefineEdgeSession,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
) {
    data class Status(
        val updating: Boolean = true,
        val finishing: Boolean = false,
        val error: String? = null,
        val cancelling: Boolean = false,
    ) {
        val canCommit: Boolean get() = !updating && !finishing && error == null
    }

    private val mutableStatus = MutableStateFlow(Status())
    val status = mutableStatus.asStateFlow()
    private val changes = Channel<Unit>(Channel.CONFLATED)
    private var settings = RefineEdgeSettings()
    private var revision = 0L
    private var appliedRevision = -1L
    private var view: RefineEdgeViewMode? = null
    private var cancelRequested = false
    private var commitOutput: RefineEdgeOutput? = null
    private var terminal = false

    // Requests and run's bookkeeping are confined to the UI dispatcher; only engine calls use worker.
    fun update(value: RefineEdgeSettings) {
        if (mutableStatus.value.finishing || terminal) return
        settings = value
        revision++
        mutableStatus.value = Status()
        changes.trySend(Unit)
    }

    fun setViewMode(value: RefineEdgeViewMode) {
        if (mutableStatus.value.finishing || terminal) return
        view = value
        mutableStatus.value = mutableStatus.value.copy(updating = true)
        changes.trySend(Unit)
    }

    fun cancel() {
        if (mutableStatus.value.finishing || terminal) return
        cancelRequested = true
        mutableStatus.value = Status(updating = false, finishing = true, cancelling = true)
        changes.trySend(Unit)
    }

    fun canCommit(value: RefineEdgeSettings): Boolean = mutableStatus.value.canCommit && settings == value

    fun commit(output: RefineEdgeOutput) {
        if (!mutableStatus.value.canCommit || terminal) return
        commitOutput = output
        mutableStatus.value = Status(updating = false, finishing = true)
        changes.trySend(Unit)
    }

    suspend fun run(onDone: (CommandResult?, RefineEdgeOutput) -> Unit, onViewResult: (CommandResult) -> Unit) {
        changes.trySend(Unit)
        try {
            while (true) {
                changes.receive()
                if (cancelRequested) {
                    withContext(worker) { session.cancel() }
                    terminal = true
                    onDone(null, RefineEdgeOutput.SELECTION)
                    return
                }
                commitOutput?.let { output ->
                    val result = command { session.commit(output) }
                    terminal = result is CommandResult.Accepted
                    if (!terminal) withContext(worker) { session.cancel() }
                    terminal = true
                    onDone(result, output)
                    return
                }
                // Avoid a queue of expensive computations while a value is being dragged.
                delay(80)
                if (cancelRequested || commitOutput != null) {
                    changes.trySend(Unit)
                    continue
                }
                val requestedView = view
                view = null
                if (requestedView != null) {
                    val result = command { session.setViewMode(requestedView) }
                    if (cancelRequested || view != null) continue
                    onViewResult(result)
                    if (result is CommandResult.Rejected) {
                        mutableStatus.value = Status(updating = false, error = result.message.ifBlank { "Could not update the preview" })
                        continue
                    }
                }
                if (appliedRevision != revision) {
                    val requestedRevision = revision
                    val requestedSettings = settings
                    val result = command { session.update(requestedSettings) }
                    if (cancelRequested || commitOutput != null) continue
                    if (requestedRevision != revision || view != null) continue
                    if (result is CommandResult.Accepted) appliedRevision = requestedRevision
                    mutableStatus.value = Status(updating = false, error = (result as? CommandResult.Rejected)?.message?.ifBlank { "Could not update the preview" })
                } else if (view == null && !mutableStatus.value.finishing) {
                    mutableStatus.value = mutableStatus.value.copy(updating = false)
                }
            }
        } finally {
            if (!terminal) withContext(NonCancellable + worker) { session.cancel() }
        }
    }

    private suspend fun command(block: () -> CommandResult): CommandResult = withContext(worker) {
        try {
            block()
        } catch (failure: RuntimeException) {
            CommandResult.Rejected(RejectionCode.UNSUPPORTED, failure.message ?: "Could not update the refinement")
        }
    }
}
