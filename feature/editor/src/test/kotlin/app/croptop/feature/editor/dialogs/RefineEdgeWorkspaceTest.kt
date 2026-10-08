package app.croptop.feature.editor.dialogs

import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.RefineEdgeOutput
import app.croptop.core.engine.api.RefineEdgeSession
import app.croptop.core.engine.api.RefineEdgeSettings
import app.croptop.core.engine.api.RefineEdgeViewMode
import app.croptop.core.engine.api.RejectionCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

class RefineEdgeWorkspaceTest {
    @Test
    fun `rapid changes only compute the latest settings and cannot commit early`() = runTest {
        val session = RecordingSession()
        val workspace = RefineEdgeWorkspace(session, StandardTestDispatcher(testScheduler))
        val job = launch { workspace.run({ _, _ -> }, {}) }
        workspace.update(RefineEdgeSettings(feather = 1f))
        workspace.update(RefineEdgeSettings(feather = 7f))
        workspace.commit(RefineEdgeOutput.LAYER_MASK)
        advanceUntilIdle()
        assertEquals(listOf(RefineEdgeSettings(feather = 7f)), session.updates)
        assertEquals(null, session.committed)
        assertTrue(workspace.status.value.canCommit)
        assertFalse(workspace.canCommit(RefineEdgeSettings(feather = 8f)))
        workspace.commit(RefineEdgeOutput.LAYER_MASK)
        advanceUntilIdle()
        assertEquals(RefineEdgeOutput.LAYER_MASK, session.committed)
        assertEquals(0, session.cancels)
        job.join()
    }

    @Test
    fun `failed preview remains visible and retry must succeed before commit`() = runTest {
        val session = RecordingSession().apply { fail = true }
        val workspace = RefineEdgeWorkspace(session, StandardTestDispatcher(testScheduler))
        val job = launch { workspace.run({ _, _ -> }, {}) }
        advanceUntilIdle()
        assertEquals("Preview failed", workspace.status.value.error)
        assertFalse(workspace.status.value.canCommit)
        workspace.commit(RefineEdgeOutput.SELECTION)
        session.fail = false
        workspace.update(RefineEdgeSettings())
        advanceUntilIdle()
        assertEquals(null, session.committed)
        assertEquals(null, workspace.status.value.error)
        assertTrue(workspace.status.value.canCommit)
        workspace.cancel()
        advanceUntilIdle()
        job.join()
        assertEquals(1, session.cancels)
    }

    @Test
    fun `disposing workspace cancels the session exactly once`() = runTest {
        val session = RecordingSession()
        val workspace = RefineEdgeWorkspace(session, StandardTestDispatcher(testScheduler))
        val job = launch { workspace.run({ _, _ -> error("Disposal should not call onDone") }, {}) }
        advanceUntilIdle()
        job.cancel()
        advanceUntilIdle()
        job.join()
        assertEquals(1, session.cancels)
    }

    @Test
    fun `view rejection blocks commit and rejected commit restores session`() = runTest {
        val session = RecordingSession()
        val workspace = RefineEdgeWorkspace(session, StandardTestDispatcher(testScheduler))
        var completed: CommandResult? = null
        val job = launch { workspace.run({ result, _ -> completed = result }, {}) }
        advanceUntilIdle()
        session.fail = true
        workspace.setViewMode(RefineEdgeViewMode.ON_BLACK)
        advanceUntilIdle()
        assertFalse(workspace.status.value.canCommit)
        assertEquals(RefineEdgeViewMode.OVERLAY, session.viewMode.value)
        session.fail = false
        workspace.update(RefineEdgeSettings())
        advanceUntilIdle()
        session.fail = true
        workspace.commit(RefineEdgeOutput.SELECTION)
        advanceUntilIdle()
        job.join()
        assertTrue(completed is CommandResult.Rejected)
        assertEquals(1, session.cancels)
    }

    private class RecordingSession : RefineEdgeSession {
        override val viewMode = MutableStateFlow(RefineEdgeViewMode.OVERLAY)
        val updates = mutableListOf<RefineEdgeSettings>()
        var fail = false
        var cancels = 0
        var committed: RefineEdgeOutput? = null
        private fun result() = if (fail) CommandResult.Rejected(RejectionCode.UNSUPPORTED, "Preview failed") else CommandResult.Accepted(1)
        override fun update(settings: RefineEdgeSettings): CommandResult {
            updates += settings
            return result()
        }
        override fun setViewMode(mode: RefineEdgeViewMode): CommandResult {
            if (!fail) viewMode.value = mode
            return result()
        }
        override fun commit(output: RefineEdgeOutput): CommandResult {
            committed = output
            return result()
        }
        override fun cancel() { cancels++ }
    }
}
