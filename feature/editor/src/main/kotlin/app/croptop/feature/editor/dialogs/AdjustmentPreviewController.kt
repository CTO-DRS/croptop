package app.croptop.feature.editor.dialogs

import app.croptop.core.engine.api.AdjustmentPreviewSession
import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.DocumentRegionPreview
import app.croptop.core.engine.api.RejectionCode
import app.croptop.core.model.DocRect
import app.croptop.core.model.ParamValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Serializes one engine-owned destructive preview transaction off the UI thread. */
internal class AdjustmentPreviewController(
    private val session: AdjustmentPreviewSession,
    private val region: DocRect? = null,
) : AutoCloseable {
    private sealed interface Request {
        data class Preview(val generation: Long, val values: Map<String, ParamValue>) : Request
        data class Apply(val values: Map<String, ParamValue>, val result: CompletableDeferred<CommandResult>) : Request
        data object Cancel : Request
    }

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val requests = Channel<Request>(Channel.UNLIMITED)
    private val mutablePreviewResult = MutableStateFlow<CommandResult?>(null)
    private val mutableRegionPreview = MutableStateFlow<DocumentRegionPreview?>(null)
    private val mutableApplying = MutableStateFlow(false)
    val previewResult: StateFlow<CommandResult?> = mutablePreviewResult.asStateFlow()
    val regionPreview: StateFlow<DocumentRegionPreview?> = mutableRegionPreview.asStateFlow()
    val applying: StateFlow<Boolean> = mutableApplying.asStateFlow()

    private var active = true
    private var applyPending = false
    private var generation = 0L

    init {
        scope.launch {
            var deferred: Request? = null
            var lastSuccessfulValues: Map<String, ParamValue>? = null
            while (true) {
                val request = deferred ?: requests.receiveCatching().getOrNull() ?: break
                deferred = null
                when (request) {
                    is Request.Preview -> {
                        var latest = request
                        var applyBarrier: Request? = null
                        while (true) {
                            val next = requests.tryReceive().getOrNull() ?: break
                            when (next) {
                                is Request.Preview -> latest = next

                                is Request.Apply, Request.Cancel -> {
                                    applyBarrier = next
                                    break
                                }
                            }
                        }
                        if (applyBarrier != null) {
                            // Apply contains the newest dialog values and supersedes previews still queued.
                            deferred = applyBarrier
                        } else if (isActive()) {
                            val result = runPreview(latest.values)
                            val regionImage = if (result is CommandResult.Accepted) {
                                lastSuccessfulValues = latest.values
                                region?.let { target -> runCatching { session.previewRegion(target) }.getOrNull() }
                            } else {
                                null
                            }
                            synchronized(lock) {
                                if (active && latest.generation == generation) {
                                    mutablePreviewResult.value = result
                                    mutableRegionPreview.value = regionImage
                                }
                            }
                        }
                    }

                    is Request.Apply -> {
                        val result = if (!isActive()) {
                            canceledResult()
                        } else {
                            val preview = if (lastSuccessfulValues == request.values) {
                                CommandResult.Accepted(0L)
                            } else {
                                runPreview(request.values).also { previewResult ->
                                    if (previewResult is CommandResult.Accepted) {
                                        lastSuccessfulValues = request.values
                                    }
                                    synchronized(lock) {
                                        if (active) mutablePreviewResult.value = previewResult
                                    }
                                }
                            }
                            if (preview !is CommandResult.Accepted || !isActive()) {
                                if (isActive()) preview else canceledResult()
                            } else {
                                try {
                                    session.commit()
                                } catch (failure: Throwable) {
                                    rejected(failure)
                                }
                            }
                        }
                        synchronized(lock) {
                            applyPending = false
                            mutableApplying.value = false
                            if (result is CommandResult.Accepted) active = false
                            if (active) mutablePreviewResult.value = result
                        }
                        request.result.complete(result)
                        if (result is CommandResult.Accepted) {
                            requests.close()
                            break
                        }
                    }

                    Request.Cancel -> {
                        try {
                            session.cancel()
                        } catch (_: Throwable) {
                            // Dismissal remains immediate; engine sessions own their rollback cleanup.
                        }
                        requests.close()
                        break
                    }
                }
            }
        }
    }

    /** Schedules a preview. Consecutive pending slider values collapse to the newest request. */
    fun preview(values: Map<String, ParamValue>) {
        synchronized(lock) {
            if (!active || applyPending) return
            val copied = values.toMap()
            generation++
            mutablePreviewResult.value = null
            if (region != null) mutableRegionPreview.value = null
            if (requests.trySend(Request.Preview(generation, copied)).isFailure) {
                mutablePreviewResult.value = closedResult()
            }
        }
    }

    /** Previews [values] if needed and commits only after that exact preview succeeds. */
    suspend fun apply(values: Map<String, ParamValue>): CommandResult {
        val result = CompletableDeferred<CommandResult>()
        synchronized(lock) {
            if (!active) return closedResult()
            if (applyPending) return CommandResult.Rejected(RejectionCode.QUEUE_FULL, "Adjustment Apply is already running")
            applyPending = true
            mutableApplying.value = true
            if (requests.trySend(Request.Apply(values.toMap(), result)).isFailure) {
                applyPending = false
                mutableApplying.value = false
                return closedResult()
            }
        }
        return result.await()
    }

    /** Drops queued preview requests; a running native call is allowed to return before rollback. */
    fun cancel() {
        synchronized(lock) {
            if (!active) return
            active = false
            applyPending = false
            mutableApplying.value = false
            requests.trySend(Request.Cancel)
            requests.close()
        }
    }

    override fun close() = cancel()

    private fun runPreview(values: Map<String, ParamValue>): CommandResult = try {
        session.preview(values)
    } catch (failure: Throwable) {
        rejected(failure)
    }

    private fun isActive(): Boolean = synchronized(lock) { active }

    private fun canceledResult() = CommandResult.Rejected(RejectionCode.CANCELLED, "Adjustment preview was canceled")

    private fun closedResult() = CommandResult.Rejected(RejectionCode.CLOSED, "Adjustment preview is closed")

    private fun rejected(failure: Throwable) = CommandResult.Rejected(
        RejectionCode.UNSUPPORTED,
        failure.message?.takeIf(String::isNotBlank) ?: "Adjustment preview failed",
    )
}
