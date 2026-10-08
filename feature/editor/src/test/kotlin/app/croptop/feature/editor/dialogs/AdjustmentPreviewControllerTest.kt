package app.croptop.feature.editor.dialogs

import app.croptop.core.engine.api.AdjustmentPreviewSession
import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.DocumentRegionPreview
import app.croptop.core.engine.api.RejectionCode
import app.croptop.core.model.DocRect
import app.croptop.core.model.ParamValue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test

class AdjustmentPreviewControllerTest {
    @Test
    fun successfulPreviewPublishesItsNativeResolutionRegion() = runBlocking {
        val region = DocRect(4, 6, 6, 8)
        val rgba = ByteArray(16) { it.toByte() }
        val session = RecordingPreviewSession().apply { regionImage = DocumentRegionPreview(region, rgba) }
        val controller = AdjustmentPreviewController(session, region)

        controller.preview(values(3f))
        val preview = withTimeout(5_000) { controller.regionPreview.filterNotNull().first() }

        assertEquals(region, preview.region)
        assertTrue(rgba.contentEquals(preview.rgba))
        assertEquals(listOf(region), session.regionRequests.toList())
        controller.close()
        withTimeout(5_000) { session.canceled.await() }
    }

    @Test
    fun burstCoalescesPendingValuesToTheLatestPreview() = runBlocking {
        val session = RecordingPreviewSession()
        val gate = session.blockNextPreview()
        val controller = AdjustmentPreviewController(session)
        val first = values(1f)
        val second = values(2f)
        val latest = values(3f)

        controller.preview(first)
        assertEquals(first, withTimeout(5_000) { session.started.receive() })
        controller.preview(second)
        controller.preview(latest)
        gate.release.countDown()

        assertEquals(first, withTimeout(5_000) { session.finished.receive() }.first)
        assertEquals(latest, withTimeout(5_000) { session.finished.receive() }.first)
        assertEquals(listOf(first, latest), session.previewCalls.toList())
        controller.close()
        withTimeout(5_000) { session.canceled.await() }
    }

    @Test
    fun applyWaitsForLatestSubmittedValuesAndCommitsAfterTheirPreview() = runBlocking {
        val session = RecordingPreviewSession()
        val gate = session.blockNextPreview()
        val controller = AdjustmentPreviewController(session)
        val first = values(1f)
        val latest = values(9f)

        controller.preview(first)
        assertEquals(first, withTimeout(5_000) { session.started.receive() })
        controller.preview(latest)
        val applyResult = async(Dispatchers.Default) { controller.apply(latest) }
        gate.release.countDown()

        assertEquals(first, withTimeout(5_000) { session.finished.receive() }.first)
        assertEquals(latest, withTimeout(5_000) { session.finished.receive() }.first)
        assertIs<CommandResult.Accepted>(withTimeout(5_000) { applyResult.await() })
        val firstEvent = "preview:${first.values.single().v}"
        val latestEvent = "preview:${latest.values.single().v}"
        assertEquals(listOf(firstEvent, latestEvent, "commit"), session.events.toList())
        assertEquals(1, session.commitCalls)
    }

    @Test
    fun previewRejectionIsTypedAndApplyDoesNotCommitUntilPreviewSucceeds() = runBlocking {
        val session = RecordingPreviewSession().apply { rejectedValues += values(4f) }
        val controller = AdjustmentPreviewController(session)
        val rejected = withTimeout(5_000) { controller.apply(values(4f)) }

        assertIs<CommandResult.Rejected>(rejected)
        assertEquals(RejectionCode.INVALID_ARGUMENT, rejected.code)
        assertEquals(0, session.commitCalls)
        assertEquals(rejected, controller.previewResult.value)

        assertIs<CommandResult.Accepted>(withTimeout(5_000) { controller.apply(values(5f)) })
        assertEquals(1, session.commitCalls)
    }

    @Test
    fun rejectedCommitCanRetryWithoutReplayingSuccessfulPreview() = runBlocking {
        val session = RecordingPreviewSession().apply { rejectNextCommit = true }
        val controller = AdjustmentPreviewController(session)
        val submitted = values(7f)

        val rejected = withTimeout(5_000) { controller.apply(submitted) }
        assertIs<CommandResult.Rejected>(rejected)
        assertEquals(1, session.commitCalls)

        assertIs<CommandResult.Accepted>(withTimeout(5_000) { controller.apply(submitted) })
        assertEquals(listOf(submitted), session.previewCalls.toList())
        assertEquals(2, session.commitCalls)
        controller.close()
        assertEquals(0, session.cancelCalls)
    }

    @Test
    fun cancelDropsPendingPreviewAndRestoresAfterRunningPreviewReturns() = runBlocking {
        verifyCancelAndClose(close = false)
    }

    @Test
    fun closeDropsPendingPreviewAndRestoresAfterRunningPreviewReturns() = runBlocking {
        verifyCancelAndClose(close = true)
    }

    @Test
    fun cancelCompletesQueuedApplyAndRestoresAfterRunningPreviewReturns() = runBlocking {
        val session = RecordingPreviewSession()
        val gate = session.blockNextPreview()
        val controller = AdjustmentPreviewController(session)
        val running = values(1f)
        val applyValues = values(2f)

        controller.preview(running)
        assertEquals(running, withTimeout(5_000) { session.started.receive() })
        val applyResult = async(Dispatchers.Default) { controller.apply(applyValues) }
        withTimeout(5_000) {
            while (!controller.applying.value) kotlinx.coroutines.yield()
        }
        controller.cancel()
        gate.release.countDown()

        assertEquals(running, withTimeout(5_000) { session.finished.receive() }.first)
        val rejected = withTimeout(5_000) { applyResult.await() }
        assertIs<CommandResult.Rejected>(rejected)
        assertEquals(RejectionCode.CANCELLED, rejected.code)
        withTimeout(5_000) { session.canceled.await() }
        assertEquals(0, session.commitCalls)
        assertEquals(listOf(running), session.previewCalls.toList())
    }

    private suspend fun verifyCancelAndClose(close: Boolean) {
        val session = RecordingPreviewSession()
        val gate = session.blockNextPreview()
        val controller = AdjustmentPreviewController(session)
        val running = values(1f)
        val pending = values(2f)

        controller.preview(running)
        assertEquals(running, withTimeout(5_000) { session.started.receive() })
        controller.preview(pending)
        if (close) controller.close() else controller.cancel()
        gate.release.countDown()

        assertEquals(running, withTimeout(5_000) { session.finished.receive() }.first)
        withTimeout(5_000) { session.canceled.await() }
        assertEquals(listOf(running), session.previewCalls.toList())
        assertEquals(1, session.cancelCalls)
    }

    private fun values(value: Float) = mapOf("brightness" to ParamValue.Num(value))

    private class RecordingPreviewSession : AdjustmentPreviewSession {
        data class Gate(val release: CountDownLatch)

        val started = Channel<Map<String, ParamValue>>(Channel.UNLIMITED)
        val finished = Channel<Pair<Map<String, ParamValue>, CommandResult>>(Channel.UNLIMITED)
        val previewCalls = java.util.concurrent.CopyOnWriteArrayList<Map<String, ParamValue>>()
        val events = java.util.concurrent.CopyOnWriteArrayList<String>()
        val rejectedValues = java.util.concurrent.CopyOnWriteArrayList<Map<String, ParamValue>>()
        val regionRequests = java.util.concurrent.CopyOnWriteArrayList<DocRect>()
        val canceled = CompletableDeferred<Unit>()

        @Volatile var commitCalls = 0

        @Volatile var cancelCalls = 0

        @Volatile var rejectNextCommit = false

        @Volatile var regionImage: DocumentRegionPreview? = null

        @Volatile private var gateNext: Gate? = null

        @Volatile private var lastResult: CommandResult = CommandResult.Accepted(0L)

        fun blockNextPreview(): Gate = Gate(CountDownLatch(1)).also { gateNext = it }

        override fun preview(values: Map<String, ParamValue>): CommandResult {
            previewCalls += values
            events += "preview:${(values.values.single() as ParamValue.Num).v}"
            started.trySend(values)
            val gate = gateNext.also { gateNext = null }
            if (gate != null) {
                // Let the test know the call entered the engine before it releases the worker.
                assertTrue(gate.release.await(5, TimeUnit.SECONDS), "preview test gate timed out")
            }
            val result = if (values in rejectedValues) {
                CommandResult.Rejected(RejectionCode.INVALID_ARGUMENT, "Rejected test preview")
            } else {
                CommandResult.Accepted(previewCalls.size.toLong())
            }
            lastResult = result
            finished.trySend(values to result)
            return result
        }

        override fun commit(): CommandResult {
            commitCalls++
            events += "commit"
            if (rejectNextCommit) {
                rejectNextCommit = false
                return CommandResult.Rejected(RejectionCode.QUEUE_FULL, "History is busy")
            }
            return lastResult
        }

        override fun previewRegion(region: DocRect): DocumentRegionPreview? {
            regionRequests += region
            return regionImage
        }

        override fun cancel() {
            cancelCalls++
            events += "cancel"
            canceled.complete(Unit)
        }
    }
}
