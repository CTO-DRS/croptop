package app.croptop.feature.editor.tools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.croptop.core.data.PreferencesRepository
import app.croptop.core.model.UiPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** Shared stylus input preferences consumed by the editor canvas and Settings. */
@HiltViewModel
class StylusInputViewModel @Inject constructor(
    private val preferences: PreferencesRepository,
) : ViewModel() {
    val settings: StateFlow<UiPreferences> = preferences.ui.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        UiPreferences(),
    )
}
