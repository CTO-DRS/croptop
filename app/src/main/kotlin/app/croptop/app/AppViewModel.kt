package app.croptop.app

import android.app.ActivityManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.croptop.core.data.PreferencesRepository
import app.croptop.core.data.RecentDocumentsRepository
import app.croptop.core.engine.api.Engine
import app.croptop.core.input.Keymap
import app.croptop.core.input.ShortcutManager
import app.croptop.core.model.RecentDocument
import app.croptop.core.model.UiPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class AppViewModel @Inject constructor(
    prefs: PreferencesRepository,
    recents: RecentDocumentsRepository,
    shortcuts: ShortcutManager,
    engine: Engine,
    @ApplicationContext context: Context,
    /** Natural-language edits in the palette; null when no on-device model is bound. */
    askService: app.croptop.core.agent.AskService,
) : ViewModel() {
    val ask: app.croptop.core.agent.AskService? = askService.takeIf { it.available }

    init {
        // Apply the user's keymap app-wide (all windows share the ShortcutManager singleton).
        viewModelScope.launch {
            prefs.keymapJson.collect { json ->
                shortcuts.setKeymap(json?.let { runCatching { Keymap.fromJson(it) }.getOrNull() } ?: Keymap.default())
            }
        }
        viewModelScope.launch {
            prefs.ui.map { it.memoryBudgetPercent }.distinctUntilChanged().collect { percent ->
                val activityManager = context.getSystemService(ActivityManager::class.java) ?: return@collect
                val memory = ActivityManager.MemoryInfo()
                activityManager.getMemoryInfo(memory)
                engine.setMemoryBudget(memory.totalMem * percent.coerceIn(20, 70) / 100L)
            }
        }
    }

    val ui: StateFlow<UiPreferences> = prefs.ui.stateIn(viewModelScope, SharingStarted.Eagerly, UiPreferences())
    val recent: StateFlow<List<RecentDocument>> = recents.observe(
        limit = 12,
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
