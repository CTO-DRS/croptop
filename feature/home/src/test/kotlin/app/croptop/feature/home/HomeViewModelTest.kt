package app.croptop.feature.home

import app.croptop.core.data.DocumentPresetsRepository
import app.croptop.core.data.PreferencesRepository
import app.croptop.core.data.RecentDocumentsRepository
import app.croptop.core.model.DocSize
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.RecentDocument
import app.croptop.core.model.Swatch
import app.croptop.core.model.UiPreferences
import app.croptop.core.model.WorkspaceLayout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

class HomeViewModelTest {
    private val document = RecentDocument("content://photos/1", "Photo.png", DocSize(100, 80), null, 10L, pinned = false)

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `recent pin and remove publish acknowledged and completed feedback`() = runTest {
        val recents = MemoryRecents(document).apply { pinGate = CompletableDeferred() }
        val viewModel = HomeViewModel(recents, MemoryPresets, MemoryPreferences)

        val pin = viewModel.togglePin(document)
        assertEquals(HomeActionPhase.RUNNING, viewModel.actionFeedback.value?.phase)
        assertEquals("Pin Photo.png to Top…", viewModel.actionFeedback.value?.detail)
        recents.pinGate?.complete(Unit)
        pin.join()
        assertTrue(recents.documents.value.single().pinned)
        assertEquals(HomeActionPhase.COMPLETED, viewModel.actionFeedback.value?.phase)
        assertEquals("Photo.png pinned to Top", viewModel.actionFeedback.value?.detail)

        recents.removeGate = CompletableDeferred()
        val remove = viewModel.remove(document.uri)
        assertEquals(HomeActionPhase.RUNNING, viewModel.actionFeedback.value?.phase)
        assertEquals("Remove from Recent…", viewModel.actionFeedback.value?.detail)
        recents.removeGate?.complete(Unit)
        remove.join()
        assertTrue(recents.documents.value.isEmpty())
        assertEquals(HomeActionPhase.COMPLETED, viewModel.actionFeedback.value?.phase)
        assertEquals("Removed from Recent", viewModel.actionFeedback.value?.detail)
    }

    @Test
    fun `recent action failure stays visible with its reason`() = runTest {
        val recents = MemoryRecents(document).apply { failure = IllegalStateException("database is unavailable") }
        val viewModel = HomeViewModel(recents, MemoryPresets, MemoryPreferences)

        viewModel.remove(document.uri).join()

        assertEquals(HomeActionPhase.FAILED, viewModel.actionFeedback.value?.phase)
        assertEquals("Remove from Recent failed: database is unavailable", viewModel.actionFeedback.value?.detail)
        assertFalse(recents.documents.value.isEmpty())
    }

    @Test
    fun `pin failure stays visible with its reason`() = runTest {
        val recents = MemoryRecents(document).apply { failure = IllegalStateException("pin storage is unavailable") }
        val viewModel = HomeViewModel(recents, MemoryPresets, MemoryPreferences)

        viewModel.togglePin(document).join()

        assertEquals(HomeActionPhase.FAILED, viewModel.actionFeedback.value?.phase)
        assertEquals("Pin Photo.png to Top failed: pin storage is unavailable", viewModel.actionFeedback.value?.detail)
        assertFalse(recents.documents.value.single().pinned)
    }

    private class MemoryRecents(initial: RecentDocument) : RecentDocumentsRepository {
        val documents = MutableStateFlow(listOf(initial))
        var failure: Exception? = null
        var pinGate: CompletableDeferred<Unit>? = null
        var removeGate: CompletableDeferred<Unit>? = null

        override fun observe(limit: Int): Flow<List<RecentDocument>> = documents

        override suspend fun recordOpened(uri: String, name: String, size: DocSize?, now: Long) = Unit

        override suspend fun remove(uri: String) {
            failure?.let { throw it }
            removeGate?.await()
            documents.value = documents.value.filterNot { it.uri == uri }
        }

        override suspend fun setPinned(uri: String, pinned: Boolean) {
            failure?.let { throw it }
            pinGate?.await()
            documents.value = documents.value.map { if (it.uri == uri) it.copy(pinned = pinned) else it }
        }
    }

    private object MemoryPresets : DocumentPresetsRepository {
        override val presets: Flow<List<NewDocumentSpec>> = flowOf(emptyList())

        override suspend fun save(spec: NewDocumentSpec, now: Long) = Unit

        override suspend fun delete(name: String) = Unit
    }

    private object MemoryPreferences : PreferencesRepository {
        override val ui: Flow<UiPreferences> = flowOf(UiPreferences())
        override val workspace: Flow<WorkspaceLayout> = flowOf(WorkspaceLayout.Essentials)
        override val customWorkspaces: Flow<List<WorkspaceLayout>> = flowOf(emptyList())
        override val keymapJson: Flow<String?> = flowOf(null)
        override val onboardingSeenVersion: Flow<Int> = flowOf(0)
        override val agentSettings: Flow<app.croptop.core.data.AgentSettings> = flowOf(app.croptop.core.data.AgentSettings())

        override suspend fun updateUi(transform: (UiPreferences) -> UiPreferences) = Unit

        override suspend fun setWorkspace(layout: WorkspaceLayout) = Unit

        override suspend fun saveCustomWorkspace(layout: WorkspaceLayout) = Unit

        override suspend fun deleteCustomWorkspace(name: String) = Unit

        override suspend fun setKeymapJson(json: String?) = Unit

        override suspend fun setOnboardingSeen(versionCode: Int) = Unit

        override suspend fun setAgentSettings(settings: app.croptop.core.data.AgentSettings) = Unit

        override val swatches: Flow<List<Swatch>?> = flowOf(null)
    }
}
