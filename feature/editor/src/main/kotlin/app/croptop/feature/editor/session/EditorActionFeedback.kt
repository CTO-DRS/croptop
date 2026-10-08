package app.croptop.feature.editor.session

import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.EngineEvent
import app.croptop.core.engine.api.RejectionCode
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class ActionFeedbackPhase { ACKNOWLEDGED, RUNNING, ACCEPTED, COMPLETED, REJECTED, FAILED, CANCELED }

/** One visible lifecycle for toolbar, menu, dialog, and panel actions. */
data class EditorActionFeedback(
    val id: Long,
    val action: String,
    val phase: ActionFeedbackPhase,
    /** Only values supplied by the engine are shown. Null means indeterminate while running. */
    val progress: Float? = null,
    val detail: String? = null,
    val rejectionCode: RejectionCode? = null,
    val engineSequence: Long? = null,
    val cancelable: Boolean = false,
    /** Latest synchronous action reported while this long action remains active. */
    val concurrentNotice: String? = null,
    val concurrentNoticePhase: ActionFeedbackPhase? = null,
    val concurrentNoticeAction: String? = null,
    val concurrentNoticeSequence: Long? = null,
    val concurrentNoticeRejectionCode: RejectionCode? = null,
) {
    val statusText: String
        get() = when (phase) {
            ActionFeedbackPhase.ACKNOWLEDGED -> "$action…"
            ActionFeedbackPhase.RUNNING -> detail ?: "$action…"
            ActionFeedbackPhase.ACCEPTED -> detail ?: "$action queued"
            ActionFeedbackPhase.COMPLETED -> detail ?: "$action completed"
            ActionFeedbackPhase.REJECTED -> detail ?: "$action was rejected"
            ActionFeedbackPhase.FAILED -> detail ?: "$action failed"
            ActionFeedbackPhase.CANCELED -> detail ?: "$action canceled"
        }
}

sealed interface ActionOutcome {
    data class Completed(val detail: String? = null) : ActionOutcome

    data class Rejected(val code: RejectionCode, val reason: String) : ActionOutcome

    data class Failed(val reason: String) : ActionOutcome

    data class Canceled(val detail: String) : ActionOutcome
}

/**
 * Shared lifecycle for editor actions. Long work waits one UI frame after acknowledgement, then
 * exposes running state and only reports progress fractions passed by the engine.
 */
class EditorActionDispatcher(
    private val scope: CoroutineScope,
    private val yieldAcknowledgementFrame: suspend () -> Unit = {},
) {
    private val ids = AtomicLong()
    private val stateLock = Any()
    private val _feedback = MutableStateFlow<EditorActionFeedback?>(null)
    val feedback: StateFlow<EditorActionFeedback?> = _feedback.asStateFlow()
    val hasInFlightAction: Boolean
        get() = synchronized(stateLock) { _feedback.value.isWorkInFlight() }

    @Volatile
    private var activeId: Long = 0L

    @Volatile
    private var cancelActive: (() -> Unit)? = null
    private val earlyEvents = linkedMapOf<Long, MutableList<EngineEvent>>()

    /** Retains correlation for accepted edits even when a newer action replaces their visible row. */
    private val acceptedFeedback = linkedMapOf<Long, EditorActionFeedback>()

    /** Tracks preview sequences without publishing every accepted slider sample to the status row. */
    private val quietAccepted = mutableSetOf<Long>()

    /** Reports the result of a synchronous command. Instant edits show their result directly. */
    fun report(action: String, result: CommandResult): Boolean {
        synchronized(stateLock) {
            if (_feedback.value.isWorkInFlight()) {
                val notice = result.toFeedback(ids.incrementAndGet(), action)
                rememberAccepted(notice)
                _feedback.value = _feedback.value?.copy(
                    concurrentNotice = notice.statusText,
                    concurrentNoticePhase = notice.phase,
                    concurrentNoticeAction = action,
                    concurrentNoticeSequence = (result as? CommandResult.Accepted)?.sequence,
                    concurrentNoticeRejectionCode = notice.rejectionCode,
                )
                (result as? CommandResult.Accepted)?.sequence?.let(::applyEarlyEvents)
                return result is CommandResult.Accepted
            }
            val id = ids.incrementAndGet()
            activeId = id
            cancelActive = null
            val feedback = result.toFeedback(id, action)
            rememberAccepted(feedback)
            _feedback.value = feedback
            (result as? CommandResult.Accepted)?.sequence?.let(::applyEarlyEvents)
        }
        return result is CommandResult.Accepted
    }

    /** Tracks accepted high-frequency previews silently; rejections and later render failures stay visible. */
    fun reportQuietly(action: String, result: CommandResult): Boolean {
        if (result !is CommandResult.Accepted) return report(action, result)
        synchronized(stateLock) {
            val feedback = result.toFeedback(ids.incrementAndGet(), action)
            rememberAccepted(feedback)
            quietAccepted += result.sequence
            applyEarlyEvents(result.sequence)
        }
        return true
    }

    /** Advances accepted synchronous commands from engine lifecycle events for the matching sequence. */
    fun reportEngineEvent(event: EngineEvent) {
        synchronized(stateLock) {
            val current = _feedback.value
            if (current?.engineSequence == event.sequence ||
                current?.concurrentNoticeSequence == event.sequence ||
                event.sequence in acceptedFeedback
            ) {
                applyEvent(event)
            } else {
                val pending = earlyEvents.getOrPut(event.sequence) { mutableListOf() }
                if (pending.size < MAX_EARLY_EVENTS_PER_SEQUENCE) pending += event
                while (earlyEvents.size > MAX_EARLY_SEQUENCES) earlyEvents.remove(earlyEvents.keys.first())
            }
        }
    }

    private fun applyEarlyEvents(sequence: Long) {
        earlyEvents.remove(sequence).orEmpty().forEach(::applyEvent)
    }

    private fun applyEvent(event: EngineEvent) {
        val current = _feedback.value
        val trackedAccepted = acceptedFeedback[event.sequence]
        trackedAccepted?.let { tracked ->
            val updated = tracked.withEngineEvent(event, tracked.action)
            if (updated.engineSequence == null) {
                acceptedFeedback.remove(event.sequence)
            } else {
                acceptedFeedback[event.sequence] = updated
            }
        }
        if (quietAccepted.remove(event.sequence) && event is EngineEvent.Failed) {
            reportFailed(trackedAccepted?.action ?: "Preview", event.message)
            return
        }
        if (current == null) return
        if (current.engineSequence == event.sequence) {
            _feedback.value = current.withEngineEvent(event, current.action)
        } else if (current.concurrentNoticeSequence == event.sequence) {
            val action = current.concurrentNoticeAction ?: return
            val updated = current.copy(
                action = action,
                phase = current.concurrentNoticePhase ?: ActionFeedbackPhase.ACCEPTED,
                engineSequence = event.sequence,
                detail = current.concurrentNotice,
                concurrentNotice = null,
                concurrentNoticePhase = null,
                concurrentNoticeAction = null,
                concurrentNoticeSequence = null,
                concurrentNoticeRejectionCode = null,
            ).withEngineEvent(event, action)
            _feedback.value = current.copy(
                concurrentNotice = updated.detail ?: updated.statusText,
                concurrentNoticePhase = updated.phase,
                concurrentNoticeSequence = updated.engineSequence,
                concurrentNoticeRejectionCode = updated.rejectionCode,
            )
        }
    }

    private fun EditorActionFeedback.withEngineEvent(event: EngineEvent, action: String): EditorActionFeedback = when (event) {
        is EngineEvent.Applied -> copy(
            phase = ActionFeedbackPhase.RUNNING,
            progress = null,
            detail = "$action applied; rendering",
        )

        is EngineEvent.Progress -> copy(
            phase = ActionFeedbackPhase.RUNNING,
            progress = event.fraction?.takeIf(Float::isFinite)?.coerceIn(0f, 1f),
        )

        is EngineEvent.Rendered -> copy(
            phase = ActionFeedbackPhase.COMPLETED,
            progress = null,
            detail = "$action rendered",
            engineSequence = null,
        )

        is EngineEvent.Failed -> copy(
            phase = ActionFeedbackPhase.FAILED,
            progress = null,
            detail = event.message,
            engineSequence = null,
        )
    }

    fun reportFailed(action: String, detail: String) {
        synchronized(stateLock) {
            if (_feedback.value.isWorkInFlight()) {
                _feedback.value = _feedback.value?.copy(
                    concurrentNotice = "$action failed: $detail",
                    concurrentNoticePhase = ActionFeedbackPhase.FAILED,
                    concurrentNoticeAction = null,
                    concurrentNoticeSequence = null,
                    concurrentNoticeRejectionCode = null,
                )
                return
            }
            val id = ids.incrementAndGet()
            activeId = id
            cancelActive = null
            _feedback.value = EditorActionFeedback(id, action, ActionFeedbackPhase.FAILED, detail = detail)
        }
    }

    /** Reports an acknowledged cancellation distinctly from a rejected or failed operation. */
    fun reportCanceled(action: String, detail: String = "$action canceled") {
        synchronized(stateLock) {
            if (_feedback.value.isWorkInFlight()) {
                _feedback.value = _feedback.value?.copy(
                    concurrentNotice = detail,
                    concurrentNoticePhase = ActionFeedbackPhase.CANCELED,
                    concurrentNoticeAction = null,
                    concurrentNoticeSequence = null,
                    concurrentNoticeRejectionCode = RejectionCode.CANCELLED,
                )
                return
            }
            val id = ids.incrementAndGet()
            activeId = id
            cancelActive = null
            _feedback.value = EditorActionFeedback(
                id = id,
                action = action,
                phase = ActionFeedbackPhase.CANCELED,
                detail = detail,
                rejectionCode = RejectionCode.CANCELLED,
            )
        }
    }

    /** Reports a completed non-engine UI action without fabricating an engine sequence or progress. */
    fun reportCompleted(action: String, detail: String = "$action completed") {
        synchronized(stateLock) {
            if (_feedback.value.isWorkInFlight()) {
                _feedback.value = _feedback.value?.copy(
                    concurrentNotice = detail,
                    concurrentNoticePhase = ActionFeedbackPhase.COMPLETED,
                    concurrentNoticeAction = null,
                    concurrentNoticeSequence = null,
                    concurrentNoticeRejectionCode = null,
                )
                return
            }
            val id = ids.incrementAndGet()
            activeId = id
            cancelActive = null
            _feedback.value = EditorActionFeedback(id, action, ActionFeedbackPhase.COMPLETED, detail = detail)
        }
    }

    /** Keeps fire-and-forget app work labeled as queued until the user dismisses it. */
    fun reportQueued(action: String, detail: String = "$action queued") {
        synchronized(stateLock) {
            if (_feedback.value.isWorkInFlight()) {
                _feedback.value = _feedback.value?.copy(
                    concurrentNotice = detail,
                    concurrentNoticePhase = ActionFeedbackPhase.ACCEPTED,
                    concurrentNoticeAction = null,
                    concurrentNoticeSequence = null,
                    concurrentNoticeRejectionCode = null,
                )
                return
            }
            val id = ids.incrementAndGet()
            activeId = id
            cancelActive = null
            _feedback.value = EditorActionFeedback(id, action, ActionFeedbackPhase.ACCEPTED, detail = detail)
        }
    }

    /** Starts a suspend operation after the acknowledged state has had a frame to render. */
    fun dispatch(
        action: String,
        cancelable: Boolean = false,
        onCancel: (() -> Unit)? = null,
        onBusy: (() -> Unit)? = null,
        operation: suspend (reportProgress: (Float?) -> Unit) -> ActionOutcome,
    ): Job? {
        val id = synchronized(stateLock) {
            val existing = _feedback.value
            if (existing.isWorkInFlight()) {
                onBusy?.invoke()
                return null
            }
            ids.incrementAndGet().also { newId ->
                activeId = newId
                cancelActive = onCancel
                val carried = existing?.let(::latestAcceptedNotice)
                _feedback.value = EditorActionFeedback(
                    id = newId,
                    action = action,
                    phase = ActionFeedbackPhase.ACKNOWLEDGED,
                    cancelable = cancelable,
                    concurrentNotice = carried?.statusText,
                    concurrentNoticePhase = carried?.phase,
                    concurrentNoticeAction = carried?.action,
                    concurrentNoticeSequence = carried?.engineSequence,
                    concurrentNoticeRejectionCode = carried?.rejectionCode,
                )
            }
        }
        return scope.launch {
            try {
                yieldAcknowledgementFrame()
                updateIfCurrent(id) {
                    if (it.phase == ActionFeedbackPhase.ACKNOWLEDGED) {
                        it.copy(phase = ActionFeedbackPhase.RUNNING, progress = null)
                    } else {
                        it
                    }
                }
                val outcome = operation { progress ->
                    updateRunningIfCurrent(id) {
                        it.copy(phase = ActionFeedbackPhase.RUNNING, progress = progress?.takeIf(Float::isFinite)?.coerceIn(0f, 1f))
                    }
                }
                finish(id, outcome)
            } catch (cancelled: CancellationException) {
                updateIfCurrent(id) {
                    it.copy(
                        phase = ActionFeedbackPhase.CANCELED,
                        progress = null,
                        detail = "${it.action} canceled",
                        cancelable = false,
                    )
                }
                throw cancelled
            } catch (failure: Exception) {
                updateIfCurrent(id) {
                    it.copy(
                        phase = ActionFeedbackPhase.FAILED,
                        progress = null,
                        detail = failure.message?.takeIf(String::isNotBlank)?.let { reason -> "$action failed: $reason" } ?: "$action failed",
                        cancelable = false,
                    )
                }
            } finally {
                synchronized(stateLock) {
                    if (activeId == id) cancelActive = null
                }
            }
        }
    }

    /** Requests cancellation only when the active operation explicitly supports it. */
    fun cancel(): Boolean {
        val cancel = synchronized(stateLock) {
            val current = _feedback.value ?: return false
            val callback = cancelActive?.takeIf { current.cancelable } ?: return false
            _feedback.value = current.copy(detail = "Canceling ${current.action.lowercase()}…")
            callback
        }
        cancel()
        return true
    }

    fun dismiss() {
        synchronized(stateLock) {
            if (_feedback.value.isWorkInFlight()) return
            _feedback.value = null
            cancelActive = null
        }
    }

    private fun finish(id: Long, outcome: ActionOutcome) {
        when (outcome) {
            is ActionOutcome.Completed -> updateIfCurrent(id) {
                it.copy(phase = ActionFeedbackPhase.COMPLETED, progress = null, detail = outcome.detail, cancelable = false)
            }

            is ActionOutcome.Rejected -> updateIfCurrent(id) {
                it.copy(
                    phase = ActionFeedbackPhase.REJECTED,
                    progress = null,
                    detail = outcome.reason,
                    rejectionCode = outcome.code,
                    cancelable = false,
                )
            }

            is ActionOutcome.Failed -> updateIfCurrent(id) {
                it.copy(phase = ActionFeedbackPhase.FAILED, progress = null, detail = outcome.reason, cancelable = false)
            }

            is ActionOutcome.Canceled -> updateIfCurrent(id) {
                it.copy(
                    phase = ActionFeedbackPhase.CANCELED,
                    progress = null,
                    detail = outcome.detail,
                    rejectionCode = RejectionCode.CANCELLED,
                    cancelable = false,
                )
            }
        }
    }

    private inline fun updateIfCurrent(id: Long, update: (EditorActionFeedback) -> EditorActionFeedback) {
        synchronized(stateLock) {
            if (activeId == id) _feedback.value = _feedback.value?.let(update)
        }
    }

    private inline fun updateRunningIfCurrent(id: Long, update: (EditorActionFeedback) -> EditorActionFeedback) {
        synchronized(stateLock) {
            val current = _feedback.value
            if (activeId == id && current?.phase.isInFlight()) _feedback.value = current?.let(update)
        }
    }

    private fun ActionFeedbackPhase?.isInFlight(): Boolean =
        this == ActionFeedbackPhase.ACKNOWLEDGED || this == ActionFeedbackPhase.RUNNING

    private fun rememberAccepted(feedback: EditorActionFeedback) {
        val sequence = feedback.engineSequence ?: return
        acceptedFeedback[sequence] = feedback
        while (acceptedFeedback.size > MAX_TRACKED_ACCEPTED_SEQUENCES) {
            val oldest = acceptedFeedback.keys.first()
            acceptedFeedback.remove(oldest)
            quietAccepted.remove(oldest)
        }
    }

    private fun latestAcceptedNotice(feedback: EditorActionFeedback): EditorActionFeedback? = when {
        feedback.phase == ActionFeedbackPhase.ACCEPTED && feedback.engineSequence != null -> acceptedFeedback[feedback.engineSequence] ?: feedback

        feedback.concurrentNoticeSequence != null -> acceptedFeedback[feedback.concurrentNoticeSequence]
            ?: EditorActionFeedback(
                id = feedback.id,
                action = feedback.concurrentNoticeAction ?: feedback.action,
                phase = feedback.concurrentNoticePhase ?: ActionFeedbackPhase.ACCEPTED,
                detail = feedback.concurrentNotice,
                engineSequence = feedback.concurrentNoticeSequence,
                rejectionCode = feedback.concurrentNoticeRejectionCode,
            )

        else -> null
    }

    private fun EditorActionFeedback?.isWorkInFlight(): Boolean =
        this?.phase.isInFlight()

    private fun CommandResult.toFeedback(id: Long, action: String): EditorActionFeedback = when (this) {
        is CommandResult.Accepted -> EditorActionFeedback(
            id,
            action,
            ActionFeedbackPhase.ACCEPTED,
            detail = "$action queued",
            engineSequence = sequence,
        )

        is CommandResult.Rejected -> EditorActionFeedback(
            id = id,
            action = action,
            phase = ActionFeedbackPhase.REJECTED,
            detail = message,
            rejectionCode = code,
        )
    }

    private companion object {
        const val MAX_EARLY_SEQUENCES = 64
        const val MAX_EARLY_EVENTS_PER_SEQUENCE = 8
        const val MAX_TRACKED_ACCEPTED_SEQUENCES = 64
    }
}
