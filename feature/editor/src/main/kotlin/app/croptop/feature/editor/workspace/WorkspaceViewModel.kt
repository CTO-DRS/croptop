package app.croptop.feature.editor.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.croptop.core.data.PreferencesRepository
import app.croptop.core.model.WorkspaceLayout
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** Persists the editor workspace across windows and restarts. */
@HiltViewModel
class WorkspaceViewModel @Inject constructor(private val prefs: PreferencesRepository) : ViewModel() {
    val layout: StateFlow<WorkspaceLayout> = prefs.workspace.stateIn(viewModelScope, SharingStarted.Eagerly, WorkspaceLayout.Essentials)
    val custom: StateFlow<List<WorkspaceLayout>> = prefs.customWorkspaces.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun set(layout: WorkspaceLayout): Deferred<Unit> = viewModelScope.async { prefs.setWorkspace(layout) }

    fun saveCustom(name: String): Deferred<Unit> = viewModelScope.async {
        val named = layout.value.copy(name = name)
        prefs.saveCustomWorkspace(named)
        prefs.setWorkspace(named)
    }
}
