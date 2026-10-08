package app.croptop.feature.editor.session

import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.EngineEvent
import app.croptop.core.engine.api.RejectionCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class EditorActionFeedbackTest {
    @Test
    fun `synchronous command reports accepted and typed rejected outcomes`() = runTest {
        val dispatcher = EditorActionDispatcher(this)

        assertTrue(dispatcher.report("Add layer", CommandResult.Accepted(1)))
        assertEquals(ActionFeedbackPhase.ACCEPTED, dispatcher.feedback.value?.phase)
        assertEquals("Add layer queued", dispatcher.feedback.value?.statusText)

        assertFalse(
            dispatcher.report(
                "Draw gradient",
                CommandResult.Rejected(RejectionCode.UNSUPPORTED, "Gradient engine support is unavailable"),
            ),
        )
        assertEquals(ActionFeedbackPhase.REJECTED, dispatcher.feedback.value?.phase)
        assertEquals(RejectionCode.UNSUPPORTED, dispatcher.feedback.value?.rejectionCode)
        assertEquals("Gradient engine support is unavailable", dispatcher.feedback.value?.statusText)
    }

    @Test
    fun `engine events complete only the matching accepted command`() = runTest {
        val dispatcher = EditorActionDispatcher(this)
        dispatcher.report("Fill layer", CommandResult.Accepted(42))

        dispatcher.reportEngineEvent(EngineEvent.Rendered(41))
        assertEquals(ActionFeedbackPhase.ACCEPTED, dispatcher.feedback.value?.phase)

        dispatcher.reportEngineEvent(EngineEvent.Applied(42, "Fill"))
        assertEquals(ActionFeedbackPhase.RUNNING, dispatcher.feedback.value?.phase)
        dispatcher.reportEngineEvent(EngineEvent.Progress(42, 0.35f))
        assertEquals(0.35f, dispatcher.feedback.value?.progress)
        dispatcher.reportEngineEvent(EngineEvent.Rendered(42))
        assertEquals(ActionFeedbackPhase.COMPLETED, dispatcher.feedback.value?.phase)
        assertEquals("Fill layer rendered", dispatcher.feedback.value?.statusText)
        assertFalse(dispatcher.hasInFlightAction)

        dispatcher.report("Erase stroke", CommandResult.Accepted(43))
        dispatcher.reportEngineEvent(EngineEvent.Failed(43, "Native render failed"))
        assertEquals(ActionFeedbackPhase.FAILED, dispatcher.feedback.value?.phase)
        assertEquals("Native render failed", dispatcher.feedback.value?.statusText)
    }

    @Test
    fun `accepted commands without lifecycle events do not block long actions`() = runTest {
        val dispatcher = EditorActionDispatcher(this)
        dispatcher.report("Select layer", CommandResult.Accepted(17))

        assertFalse(dispatcher.hasInFlightAction)
        assertTrue(dispatcher.dispatch("Save") { ActionOutcome.Completed("Saved") } != null)
        runCurrent()
        assertEquals(ActionFeedbackPhase.COMPLETED, dispatcher.feedback.value?.phase)
    }

    @Test
    fun `completed UI action is visible while idle without invented engine state`() = runTest {
        val dispatcher = EditorActionDispatcher(this)

        dispatcher.reportCompleted("Pick brush", "Brush picker opened")

        val feedback = dispatcher.feedback.value
        assertEquals("Pick brush", feedback?.action)
        assertEquals(ActionFeedbackPhase.COMPLETED, feedback?.phase)
        assertEquals("Brush picker opened", feedback?.statusText)
        assertEquals(null, feedback?.engineSequence)
        assertEquals(null, feedback?.progress)
        assertFalse(dispatcher.hasInFlightAction)
    }

    @Test
    fun `quiet preview lifecycle consumes events without replacing status and shows render failure`() = runTest {
        val dispatcher = EditorActionDispatcher(this)
        dispatcher.reportCompleted("Select brush")
        val beforePreview = dispatcher.feedback.value

        dispatcher.reportQuietly("Preview opacity", CommandResult.Accepted(61))
        dispatcher.reportEngineEvent(EngineEvent.Applied(61, "Set Layer Properties"))
        dispatcher.reportEngineEvent(EngineEvent.Progress(61, 0.5f))
        dispatcher.reportEngineEvent(EngineEvent.Rendered(61))
        assertEquals(beforePreview, dispatcher.feedback.value)

        dispatcher.reportQuietly("Preview opacity", CommandResult.Accepted(62))
        dispatcher.reportEngineEvent(EngineEvent.Failed(62, "Preview render failed"))
        assertEquals(ActionFeedbackPhase.FAILED, dispatcher.feedback.value?.phase)
        assertEquals("Preview render failed", dispatcher.feedback.value?.statusText)
    }

    @Test
    fun `starting long work preserves correlation for the latest accepted command`() = runTest {
        val dispatcher = EditorActionDispatcher(this)
        dispatcher.report("Add shape", CommandResult.Accepted(18))

        dispatcher.dispatch("Save") { ActionOutcome.Completed("Saved") }
        assertEquals(18L, dispatcher.feedback.value?.concurrentNoticeSequence)
        dispatcher.reportEngineEvent(EngineEvent.Applied(18, "Add Shape"))
        assertEquals("Add shape applied; rendering", dispatcher.feedback.value?.concurrentNotice)
        dispatcher.reportEngineEvent(EngineEvent.Rendered(18))
        assertEquals("Add shape rendered", dispatcher.feedback.value?.concurrentNotice)
        assertEquals(ActionFeedbackPhase.ACKNOWLEDGED, dispatcher.feedback.value?.phase)
    }

    @Test
    fun `superseded accepted commands remain correlated while long work runs`() = runTest {
        val dispatcher = EditorActionDispatcher(this)
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        dispatcher.dispatch("Export") {
            entered.complete(Unit)
            finish.await()
            ActionOutcome.Completed("Exported")
        }
        runCurrent()
        entered.await()

        dispatcher.report("Add shape", CommandResult.Accepted(19))
        dispatcher.report("Move layer", CommandResult.Accepted(20))
        dispatcher.reportEngineEvent(EngineEvent.Applied(19, "Add Shape"))
        dispatcher.reportEngineEvent(EngineEvent.Rendered(19))
        assertEquals(20L, dispatcher.feedback.value?.concurrentNoticeSequence)
        assertEquals("Move layer queued", dispatcher.feedback.value?.concurrentNotice)

        dispatcher.reportEngineEvent(EngineEvent.Applied(20, "Move Layer"))
        dispatcher.reportEngineEvent(EngineEvent.Rendered(20))
        assertEquals("Move layer rendered", dispatcher.feedback.value?.concurrentNotice)
        assertEquals(ActionFeedbackPhase.RUNNING, dispatcher.feedback.value?.phase)

        finish.complete(Unit)
        runCurrent()
        assertEquals("Exported", dispatcher.feedback.value?.statusText)
        assertEquals("Move layer rendered", dispatcher.feedback.value?.concurrentNotice)
    }

    @Test
    fun `early and concurrent engine events finish the matching notice`() = runTest {
        val dispatcher = EditorActionDispatcher(this)
        dispatcher.reportEngineEvent(EngineEvent.Applied(51, "Create Shape"))
        dispatcher.reportEngineEvent(EngineEvent.Rendered(51))
        dispatcher.report("Create shape", CommandResult.Accepted(51))

        assertEquals(ActionFeedbackPhase.COMPLETED, dispatcher.feedback.value?.phase)
        assertEquals("Create shape rendered", dispatcher.feedback.value?.statusText)
        assertFalse(dispatcher.hasInFlightAction)

        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        dispatcher.dismiss()
        dispatcher.dispatch("Save") {
            entered.complete(Unit)
            finish.await()
            ActionOutcome.Completed("Saved")
        }
        runCurrent()
        entered.await()

        dispatcher.report("Edit shape", CommandResult.Accepted(52))
        assertEquals(ActionFeedbackPhase.ACCEPTED, dispatcher.feedback.value?.concurrentNoticePhase)
        dispatcher.reportEngineEvent(EngineEvent.Applied(52, "Edit Shape"))
        assertEquals("Edit shape applied; rendering", dispatcher.feedback.value?.concurrentNotice)
        assertEquals(ActionFeedbackPhase.RUNNING, dispatcher.feedback.value?.concurrentNoticePhase)
        dispatcher.reportEngineEvent(EngineEvent.Rendered(52))
        assertEquals("Edit shape rendered", dispatcher.feedback.value?.concurrentNotice)
        assertEquals(ActionFeedbackPhase.COMPLETED, dispatcher.feedback.value?.concurrentNoticePhase)
        assertEquals(ActionFeedbackPhase.RUNNING, dispatcher.feedback.value?.phase)

        finish.complete(Unit)
        runCurrent()
        assertEquals("Saved", dispatcher.feedback.value?.statusText)
    }

    @Test
    fun `async action acknowledges before running and shows only supplied progress`() = runTest {
        val frame = CompletableDeferred<Unit>()
        val operationEntered = CompletableDeferred<Unit>()
        val continueOperation = CompletableDeferred<Unit>()
        val dispatcher = EditorActionDispatcher(this) { frame.await() }

        dispatcher.dispatch("Export", cancelable = false) { reportProgress ->
            reportProgress(null)
            operationEntered.complete(Unit)
            continueOperation.await()
            reportProgress(0.42f)
            ActionOutcome.Completed("Exported image")
        }
        assertEquals(ActionFeedbackPhase.ACKNOWLEDGED, dispatcher.feedback.value?.phase)
        assertEquals(null, dispatcher.feedback.value?.progress)

        frame.complete(Unit)
        runCurrent()
        operationEntered.await()
        assertEquals(ActionFeedbackPhase.RUNNING, dispatcher.feedback.value?.phase)
        assertEquals(null, dispatcher.feedback.value?.progress)

        dispatcher.report("Toggle visibility", CommandResult.Accepted(2))
        assertEquals(ActionFeedbackPhase.RUNNING, dispatcher.feedback.value?.phase)
        assertEquals("Toggle visibility queued", dispatcher.feedback.value?.concurrentNotice)
        dispatcher.report(
            "Delete layer",
            CommandResult.Rejected(RejectionCode.UNSUPPORTED, "The base layer cannot be deleted"),
        )
        assertEquals(ActionFeedbackPhase.RUNNING, dispatcher.feedback.value?.phase)
        assertEquals("The base layer cannot be deleted", dispatcher.feedback.value?.concurrentNotice)
        assertEquals(ActionFeedbackPhase.REJECTED, dispatcher.feedback.value?.concurrentNoticePhase)
        assertEquals("Delete layer", dispatcher.feedback.value?.concurrentNoticeAction)
        assertEquals(RejectionCode.UNSUPPORTED, dispatcher.feedback.value?.concurrentNoticeRejectionCode)

        continueOperation.complete(Unit)
        runCurrent()
        assertEquals(ActionFeedbackPhase.COMPLETED, dispatcher.feedback.value?.phase)
        assertEquals("Exported image", dispatcher.feedback.value?.statusText)
        assertEquals("The base layer cannot be deleted", dispatcher.feedback.value?.concurrentNotice)
    }

    @Test
    fun `synchronous feedback cannot hide a running action`() = runTest {
        val frame = CompletableDeferred<Unit>()
        val operationEntered = CompletableDeferred<Unit>()
        val finishOperation = CompletableDeferred<Unit>()
        val dispatcher = EditorActionDispatcher(this) { frame.await() }

        dispatcher.dispatch("Export") {
            operationEntered.complete(Unit)
            finishOperation.await()
            ActionOutcome.Completed("Exported image")
        }
        frame.complete(Unit)
        runCurrent()
        operationEntered.await()

        dispatcher.reportCompleted("Swap colors")
        assertEquals("Export", dispatcher.feedback.value?.action)
        assertEquals("Swap colors completed", dispatcher.feedback.value?.concurrentNotice)
        assertEquals(ActionFeedbackPhase.COMPLETED, dispatcher.feedback.value?.concurrentNoticePhase)
        assertTrue(dispatcher.feedback.value?.cancelable == false)

        finishOperation.complete(Unit)
        runCurrent()
        assertEquals(ActionFeedbackPhase.COMPLETED, dispatcher.feedback.value?.phase)
        assertEquals("Exported image", dispatcher.feedback.value?.statusText)
    }

    @Test
    fun `dispatcher rejects a second long action and preserves the first`() = runTest {
        val frame = CompletableDeferred<Unit>()
        val operationEntered = CompletableDeferred<Unit>()
        val finishOperation = CompletableDeferred<Unit>()
        val dispatcher = EditorActionDispatcher(this) { frame.await() }
        var busyNoticeShown = false
        var secondOperationStarted = false

        dispatcher.dispatch("Export") {
            operationEntered.complete(Unit)
            finishOperation.await()
            ActionOutcome.Completed("Exported image")
        }
        frame.complete(Unit)
        runCurrent()
        operationEntered.await()

        val second = dispatcher.dispatch(
            action = "Save workspace",
            onBusy = { busyNoticeShown = true },
        ) {
            secondOperationStarted = true
            ActionOutcome.Completed()
        }

        assertEquals(null, second)
        assertTrue(busyNoticeShown)
        assertFalse(secondOperationStarted)
        assertEquals("Export", dispatcher.feedback.value?.action)
        assertTrue(dispatcher.feedback.value?.cancelable == false)

        finishOperation.complete(Unit)
        runCurrent()
        assertEquals(ActionFeedbackPhase.COMPLETED, dispatcher.feedback.value?.phase)
        assertEquals("Exported image", dispatcher.feedback.value?.statusText)
    }

    @Test
    fun `late progress cannot replace terminal feedback`() = runTest {
        val frame = CompletableDeferred<Unit>()
        val operationEntered = CompletableDeferred<Unit>()
        val finishOperation = CompletableDeferred<Unit>()
        val dispatcher = EditorActionDispatcher(this) { frame.await() }
        var reportLateProgress: ((Float?) -> Unit)? = null

        dispatcher.dispatch("Select subject") { reportProgress ->
            reportLateProgress = reportProgress
            operationEntered.complete(Unit)
            finishOperation.await()
            ActionOutcome.Completed("Selection complete")
        }
        frame.complete(Unit)
        runCurrent()
        operationEntered.await()
        finishOperation.complete(Unit)
        runCurrent()

        reportLateProgress?.invoke(0.75f)
        assertEquals(ActionFeedbackPhase.COMPLETED, dispatcher.feedback.value?.phase)
        assertEquals(null, dispatcher.feedback.value?.progress)
    }

    @Test
    fun `queued UI work remains visible until dismissed and can be attached to active work`() = runTest {
        val dispatcher = EditorActionDispatcher(this)
        dispatcher.reportQueued("Import brushes")
        assertEquals(ActionFeedbackPhase.ACCEPTED, dispatcher.feedback.value?.phase)
        assertEquals("Import brushes queued", dispatcher.feedback.value?.statusText)

        dispatcher.dismiss()
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        dispatcher.dispatch("Save") {
            entered.complete(Unit)
            finish.await()
            ActionOutcome.Completed("Saved")
        }
        runCurrent()
        entered.await()
        dispatcher.reportQueued("Export swatches")
        assertEquals(ActionFeedbackPhase.RUNNING, dispatcher.feedback.value?.phase)
        assertEquals("Export swatches queued", dispatcher.feedback.value?.concurrentNotice)
        assertEquals(ActionFeedbackPhase.ACCEPTED, dispatcher.feedback.value?.concurrentNoticePhase)

        finish.complete(Unit)
        runCurrent()
        assertEquals("Saved", dispatcher.feedback.value?.statusText)
        assertEquals("Export swatches queued", dispatcher.feedback.value?.concurrentNotice)
    }

    @Test
    fun `repository result is shown as exact completion or failure detail`() = runTest {
        val dispatcher = EditorActionDispatcher(this)
        dispatcher.dispatch("Import brushes") { ActionOutcome.Completed("Imported 3 brushes") }
        runCurrent()
        assertEquals(ActionFeedbackPhase.COMPLETED, dispatcher.feedback.value?.phase)
        assertEquals("Imported 3 brushes", dispatcher.feedback.value?.statusText)

        dispatcher.dispatch("Export swatches") { ActionOutcome.Failed("Couldn't export swatches") }
        runCurrent()
        assertEquals(ActionFeedbackPhase.FAILED, dispatcher.feedback.value?.phase)
        assertEquals("Couldn't export swatches", dispatcher.feedback.value?.statusText)
    }

    @Test
    fun `cancel is available only when declared and reaches canceled terminal state`() = runTest {
        val frame = CompletableDeferred<Unit>()
        val operationAwait = CompletableDeferred<Unit>()
        val dispatcher = EditorActionDispatcher(this) { frame.await() }
        var cancelRequested = false

        dispatcher.dispatch(
            action = "Select subject",
            cancelable = true,
            onCancel = {
                cancelRequested = true
                operationAwait.cancel()
            },
        ) {
            operationAwait.await()
            ActionOutcome.Completed()
        }
        assertTrue(dispatcher.cancel())
        assertTrue(cancelRequested)
        frame.complete(Unit)
        runCurrent()
        assertEquals(ActionFeedbackPhase.CANCELED, dispatcher.feedback.value?.phase)
        assertFalse(dispatcher.feedback.value?.cancelable ?: true)
    }
}
