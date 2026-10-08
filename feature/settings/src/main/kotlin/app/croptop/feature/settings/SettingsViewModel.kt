package app.croptop.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.croptop.core.data.PreferencesRepository
import app.croptop.core.engine.api.Engine
import app.croptop.core.engine.api.EngineCapabilities
import app.croptop.core.model.UiPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: PreferencesRepository,
    engine: Engine,
) : ViewModel() {
    val capabilities: EngineCapabilities = engine.capabilities

    val ui: StateFlow<UiPreferences> = prefs.ui.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiPreferences())

    fun update(transform: (UiPreferences) -> UiPreferences) {
        viewModelScope.launch { prefs.updateUi(transform) }
    }
}
