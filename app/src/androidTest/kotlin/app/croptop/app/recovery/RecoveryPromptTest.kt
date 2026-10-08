package app.croptop.app.recovery

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.croptop.core.designsystem.theme.AeDensity
import app.croptop.core.designsystem.theme.CropTopTheme
import app.croptop.core.engine.api.DocumentHandle
import app.croptop.core.engine.api.DocumentSource
import app.croptop.core.engine.api.Engine
import app.croptop.core.engine.api.EngineCapabilities
import app.croptop.core.engine.api.OpenOptions
import app.croptop.core.engine.api.RecoverableDocument
import app.croptop.core.model.DocSize
import app.croptop.core.model.NewDocumentSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecoveryPromptTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(
        documents: List<RecoverableDocument>,
        onRecover: (RecoverableDocument) -> Unit = {},
        onDiscard: (String) -> Unit = {},
        onDiscardAll: () -> Unit = {},
        onLater: () -> Unit = {},
        onRetry: () -> Unit = {},
        error: String? = null,
    ) {
        compose.setContent {
            CropTopTheme(density = AeDensity.Touch) {
                RecoveryPromptContent(documents, onRecover, onDiscard, onDiscardAll, onLater, error, onRetry)
            }
        }
    }

    @Test
    fun noRecoverableDocumentsShowsNoPrompt() {
        show(emptyList())

        assertTrue(compose.onAllNodesWithText("Recover 0 documents").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("Discard All").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun singleDocumentShowsNameAndRoutesRecover() {
        val document = recoverable("poster", "Poster draft", 1200, 800)
        var recovered: RecoverableDocument? = null
        show(listOf(document), onRecover = { recovered = it })

        compose.onNodeWithText("Recover 1 document").assertExists()
        compose.onNodeWithText("Poster draft").assertExists()
        compose.onNodeWithText("1200 × 800 px", substring = true).assertExists()
        compose.onNodeWithText("Recover").performClick()

        compose.runOnIdle { assertEquals(document, recovered) }
    }

    @Test
    fun multipleDocumentsRouteRecoverAndIndividualDiscardToTheRightIds() {
        val first = recoverable("first", "First draft", 300, 200)
        val second = recoverable("second", "Second draft", 640, 480)
        var recovered: RecoverableDocument? = null
        val discarded = mutableListOf<String>()
        show(listOf(first, second), onRecover = { recovered = it }, onDiscard = discarded::add)

        compose.onNodeWithText("Recover 2 documents").assertExists()
        compose.onAllNodesWithText("Recover")[1].performClick()
        compose.onAllNodesWithText("Discard")[0].performClick()

        compose.runOnIdle {
            assertEquals(second, recovered)
            assertEquals(listOf("first"), discarded)
        }
    }

    @Test
    fun discardAllAndLaterUseTheirDedicatedCallbacks() {
        val document = recoverable("draft", "Recovered draft", 64, 64)
        var discardAll = 0
        var later = 0
        show(listOf(document), onDiscardAll = { discardAll++ }, onLater = { later++ })
        compose.onNodeWithText("Discard All").performClick()
        compose.runOnIdle { assertEquals(1, discardAll) }

        compose.onNodeWithText("Later").performClick()
        compose.runOnIdle { assertEquals(1, later) }
    }

    @Test
    fun discardFailureExplainsThatTheRecoveryDocumentIsStillAvailable() {
        val document = recoverable("failed", "Still available", 64, 64)
        val message = "Could not discard recovery data. The document is still available."
        show(listOf(document), error = message)

        compose.onNodeWithText("Still available").assertExists()
        compose.onNodeWithText(message).assertExists()
    }

    @Test
    fun failedRecoveryScanOffersRetryAndLater() {
        val message = "Could not check recovery data. Try again."
        var retries = 0
        show(emptyList(), onRetry = { retries++ }, error = message)

        compose.onNodeWithText("Recovery unavailable").assertExists()
        compose.onNodeWithText(message).assertExists()
        compose.onNodeWithText("Try Again").performClick()
        compose.onNodeWithText("Later").assertExists()

        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun failedDiscardKeepsRecoveryEntryAndReportsFailure() {
        val document = recoverable("failed", "Still available", 64, 64)
        val viewModel = RecoveryViewModel(RecoveryTestEngine(listOf(document)) { false })

        compose.waitUntil(timeoutMillis = 5_000) { viewModel.documents.value == listOf(document) }
        viewModel.discard(document.id)
        compose.waitUntil(timeoutMillis = 5_000) { viewModel.error.value != null }

        assertEquals(listOf(document), viewModel.documents.value)
        assertEquals("Could not discard recovery data. The document is still available.", viewModel.error.value)
    }

    @Test
    fun discardAllRemovesOnlySuccessfullyDiscardedEntries() {
        val discarded = recoverable("removed", "Removed draft", 64, 64)
        val retained = recoverable("retained", "Retained draft", 128, 128)
        val viewModel = RecoveryViewModel(
            RecoveryTestEngine(listOf(discarded, retained)) { it == "removed" },
        )

        compose.waitUntil(timeoutMillis = 5_000) { viewModel.documents.value.size == 2 }
        viewModel.discardAll()
        compose.waitUntil(timeoutMillis = 5_000) { viewModel.error.value != null }

        assertEquals(listOf(retained), viewModel.documents.value)
        assertEquals("Could not discard 1 recovery document. They remain available.", viewModel.error.value)
    }

    private fun recoverable(id: String, name: String, width: Int, height: Int) = RecoverableDocument(
        id = id,
        name = name,
        size = DocSize(width, height),
        lastEditEpochMs = System.currentTimeMillis(),
    )

    private class RecoveryTestEngine(
        private val documents: List<RecoverableDocument>,
        private val discard: (String) -> Boolean,
    ) : Engine {
        override val capabilities = EngineCapabilities("test", "test")
        override val memoryBudgetBytes = 0L

        override fun setMemoryBudget(bytes: Long) = Unit

        override suspend fun open(source: DocumentSource, options: OpenOptions): DocumentHandle = error("unused")

        override fun newDocument(spec: NewDocumentSpec): DocumentHandle = error("unused")

        override fun recoverableDocuments(): List<RecoverableDocument> = documents

        override fun discardRecovery(id: String): Boolean = discard(id)

        override fun close() = Unit
    }
}
