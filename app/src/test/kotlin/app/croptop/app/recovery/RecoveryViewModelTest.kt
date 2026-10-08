package app.croptop.app.recovery

import app.croptop.core.engine.api.DocumentHandle
import app.croptop.core.engine.api.DocumentSource
import app.croptop.core.engine.api.Engine
import app.croptop.core.engine.api.EngineCapabilities
import app.croptop.core.engine.api.OpenOptions
import app.croptop.core.engine.api.RecoverableDocument
import app.croptop.core.model.DocSize
import app.croptop.core.model.NewDocumentSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class RecoveryViewModelTest {
    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setMainDispatcher() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun failedDiscardKeepsRecoveryEntryAndReportsFailure() = runBlocking {
        val document = recoverable("failed", "Still available")
        val viewModel = RecoveryViewModel(RecoveryTestEngine(listOf(document), discard = { false }))

        withTimeout(5_000) { viewModel.documents.first { it == listOf(document) } }
        viewModel.discard(document.id)
        val message = withTimeout(5_000) { viewModel.error.filterNotNull().first() }

        assertEquals(listOf(document), viewModel.documents.value)
        assertEquals("Could not discard recovery data. The document is still available.", message)
    }

    @Test
    fun discardAllKeepsOnlyEntriesWhoseJournalsCouldNotBeRemoved() = runBlocking {
        val removed = recoverable("removed", "Removed draft")
        val retained = recoverable("retained", "Retained draft")
        val viewModel = RecoveryViewModel(
            RecoveryTestEngine(listOf(removed, retained), discard = { it == "removed" }),
        )

        withTimeout(5_000) { viewModel.documents.first { it.size == 2 } }
        viewModel.discardAll()
        val message = withTimeout(5_000) { viewModel.error.filterNotNull().first() }

        assertEquals(listOf(retained), viewModel.documents.value)
        assertEquals("Could not discard 1 recovery document. They remain available.", message)
    }

    @Test
    fun failedRecoveryScanCanBeRetried() = runBlocking {
        val document = recoverable("after-retry", "Recovered after retry")
        var scanFails = true
        val viewModel = RecoveryViewModel(
            RecoveryTestEngine(emptyList(), { false }) {
                if (scanFails) error("journal unavailable") else listOf(document)
            },
        )

        val firstError = withTimeout(5_000) { viewModel.error.filterNotNull().first() }
        assertEquals("Could not check recovery data. Try again.", firstError)

        scanFails = false
        viewModel.refresh()
        withTimeout(5_000) { viewModel.documents.first { it == listOf(document) } }

        assertEquals(null, viewModel.error.value)
    }

    private fun recoverable(id: String, name: String) = RecoverableDocument(
        id = id,
        name = name,
        size = DocSize(64, 64),
        lastEditEpochMs = 0L,
    )

    private class RecoveryTestEngine(
        private val documents: List<RecoverableDocument>,
        private val discard: (String) -> Boolean,
        private val readDocuments: () -> List<RecoverableDocument> = { documents },
    ) : Engine {
        override val capabilities = EngineCapabilities("test", "test")
        override val memoryBudgetBytes = 0L

        override fun setMemoryBudget(bytes: Long) = Unit

        override suspend fun open(source: DocumentSource, options: OpenOptions): DocumentHandle = error("unused")

        override fun newDocument(spec: NewDocumentSpec): DocumentHandle = error("unused")

        override fun recoverableDocuments(): List<RecoverableDocument> = readDocuments()

        override fun discardRecovery(id: String): Boolean = discard(id)

        override fun close() = Unit
    }
}
