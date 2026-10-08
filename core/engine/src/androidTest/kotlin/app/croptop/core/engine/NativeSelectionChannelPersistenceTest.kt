package app.croptop.core.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.DocumentSource
import app.croptop.core.engine.api.EngineEvent
import app.croptop.core.engine.api.SaveTarget
import app.croptop.core.model.BackgroundContents
import app.croptop.core.model.DocRect
import app.croptop.core.model.DocRectF
import app.croptop.core.model.DocSize
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.SelectionInfo
import app.croptop.core.model.SelectionOp
import app.croptop.core.model.SelectionShape
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeSelectionChannelPersistenceTest {
    @Test
    fun savedSelectionSurvivesProjectSaveOpenAndCrashRecovery() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = NativeEngine(context)
        val recoveryEngine = NativeEngine(context)
        val project = context.cacheDir.resolve("selection-channel-${System.nanoTime()}.croptop")
        val existingJournals = engine.recoveryRoot?.listFiles()?.map { it.name }?.toSet().orEmpty()
        val document = engine.newDocument(
            NewDocumentSpec("Selection channel persistence", DocSize(24, 18), background = BackgroundContents.TRANSPARENT),
        )
        var reopened: NativeDocument? = null
        var recovered: NativeDocument? = null
        var recoveryId: String? = null
        try {
            val bounds = DocRect(3, 4, 15, 13)
            assertTrue(document.select(SelectionShape.Rect(DocRectF(3f, 4f, 15f, 13f)), antiAlias = false) is CommandResult.Accepted)
            val saveResult = document.saveSelection("Portrait") as CommandResult.Accepted
            assertEquals(
                EngineEvent.Applied(saveResult.sequence, "Save Selection"),
                withTimeout(5_000) {
                    document.events.first { it is EngineEvent.Applied && it.sequence == saveResult.sequence }
                },
            )
            assertTrue("saving a channel dirties the document", document.info.value.dirty)
            document.save(SaveTarget.AppFile(project.absolutePath))
            assertEquals(false, document.info.value.dirty)

            reopened = engine.open(DocumentSource.AppFile(project.absolutePath)) as NativeDocument
            assertEquals(listOf("Portrait"), reopened.selectionChannels.value.map { it.name })
            // The active selection itself is transient across reopen; the named channel is durable.
            assertTrue(reopened.deselect() is CommandResult.Rejected)
            assertTrue(reopened.loadSelection("Portrait", SelectionOp.REPLACE, invert = false) is CommandResult.Accepted)
            assertEquals(SelectionInfo.Active(bounds), reopened.selection.value)

            assertTrue(reopened.saveSelection("Recovery") is CommandResult.Accepted)
            assertTrue(reopened.autosaveNow(flushTimeoutMs = 30_000L))
            val journal = checkNotNull(
                engine.recoveryRoot?.listFiles()?.singleOrNull { it.isDirectory && it.name !in existingJournals },
            )
            recoveryId = journal.name
            recovered = recoveryEngine.recover(journal.name) as NativeDocument
            assertEquals(listOf("Portrait", "Recovery"), recovered.selectionChannels.value.map { it.name })
            assertTrue(recovered.deselect() is CommandResult.Rejected)
            assertTrue(recovered.loadSelection("Portrait", SelectionOp.REPLACE, invert = false) is CommandResult.Accepted)
            assertEquals(SelectionInfo.Active(bounds), recovered.selection.value)

            val deleteResult = recovered.deleteSelectionChannel("Recovery") as CommandResult.Accepted
            assertEquals(
                EngineEvent.Applied(deleteResult.sequence, "Delete Saved Selection"),
                withTimeout(5_000) {
                    recovered.events.first { it is EngineEvent.Applied && it.sequence == deleteResult.sequence }
                },
            )
            assertTrue("deleting a channel dirties the document", recovered.info.value.dirty)
        } finally {
            recovered?.close()
            reopened?.close()
            document.close()
            recoveryId?.let(engine::discardRecovery)
            recoveryEngine.close()
            engine.close()
            project.delete()
        }
    }
}
