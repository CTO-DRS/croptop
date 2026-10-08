package app.croptop.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.croptop.core.data.DocumentPresetsRepository
import app.croptop.core.data.PreferencesRepository
import app.croptop.core.data.RecentDocumentsRepository
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.RecentDocument
import app.croptop.core.model.WorkingSpace
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val recents: RecentDocumentsRepository,
    private val presetsRepo: DocumentPresetsRepository,
    prefs: PreferencesRepository,
) : ViewModel() {
    /** Default color space for new canvases (Settings ▸ Color). */
    val defaultSpace: StateFlow<WorkingSpace> = prefs.ui.map { it.defaultWorkingSpace }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WorkingSpace.DISPLAY_P3)

    val presets: StateFlow<List<NewDocumentSpec>> = presetsRepo.presets.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun savePreset(spec: NewDocumentSpec) = viewModelScope.launch { presetsRepo.save(spec) }

    fun deletePreset(name: String) = viewModelScope.launch { presetsRepo.delete(name) }

    val recent: StateFlow<List<RecentDocument>?> = recents.observe(limit = 12)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _actionFeedback = MutableStateFlow<HomeActionFeedback?>(null)
    val actionFeedback: StateFlow<HomeActionFeedback?> = _actionFeedback.asStateFlow()

    fun remove(uri: String) = runRecentAction("Remove from Recent", "Removed from Recent") {
        recents.remove(uri)
    }

    fun togglePin(doc: RecentDocument) = runRecentAction(
        if (doc.pinned) "Unpin ${doc.name}" else "Pin ${doc.name} to Top",
        if (doc.pinned) "${doc.name} unpinned" else "${doc.name} pinned to Top",
    ) {
        recents.setPinned(doc.uri, !doc.pinned)
    }

    private fun runRecentAction(action: String, completion: String, operation: suspend () -> Unit) = viewModelScope.launch {
        _actionFeedback.value = HomeActionFeedback(action, HomeActionPhase.ACKNOWLEDGED, "$action…")
        yield()
        _actionFeedback.value = HomeActionFeedback(action, HomeActionPhase.RUNNING, "$action…")
        try {
            operation()
            _actionFeedback.value = HomeActionFeedback(action, HomeActionPhase.COMPLETED, completion)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val reason = failure.message?.takeIf(String::isNotBlank) ?: failure::class.simpleName ?: "unknown error"
            _actionFeedback.value = HomeActionFeedback(action, HomeActionPhase.FAILED, "$action failed: $reason")
        }
    }
}
