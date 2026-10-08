package app.croptop.app.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.croptop.core.data.PreferencesRepository
import app.croptop.core.input.ActionCategory
import app.croptop.core.input.Actions
import app.croptop.core.input.Keymap
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Bump when the welcome content changes enough to show it again ("What's new"). */
const val WELCOME_VERSION = 1

@HiltViewModel
class OnboardingViewModel @Inject constructor(private val prefs: PreferencesRepository) : ViewModel() {
    /** null while loading, so the dialog never flashes for returning users. */
    val showWelcome: StateFlow<Boolean?> = prefs.onboardingSeenVersion.map { it < WELCOME_VERSION }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun finish(choice: KeymapChoice) = viewModelScope.launch {
        if (choice == KeymapChoice.SIMPLE) prefs.setKeymapJson(simpleKeymap().toJson())
        prefs.setOnboardingSeen(WELCOME_VERSION)
    }

    fun showAgain() = viewModelScope.launch { prefs.setOnboardingSeen(0) }

    companion object {
        /** Default keymap without unmodified single-key editor shortcuts (tools, D/X, 0–9, [ ]). */
        fun simpleKeymap(): Keymap {
            var km = Keymap.default()
            Actions.all.filter { it.category in setOf(ActionCategory.TOOLS, ActionCategory.BRUSH, ActionCategory.COLOR) }.forEach { def ->
                km = km.rebind(def.id, km.chordsFor(def.id).filter { it.ctrl || it.alt || it.meta })
            }
            return km
        }
    }
}
