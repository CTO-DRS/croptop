package app.croptop.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.croptop.core.data.PreferencesRepository
import app.croptop.core.input.ActionId
import app.croptop.core.input.KeyChord
import app.croptop.core.input.Keymap
import app.croptop.core.input.ShortcutManager
import app.croptop.core.input.ShortcutPreset
import app.croptop.core.input.withAffinityPhoto2Shortcuts
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Keyboard shortcut editor state. Changes persist and apply to every window at once. */
@HiltViewModel
class ShortcutsViewModel @Inject constructor(
    private val shortcuts: ShortcutManager,
    private val prefs: PreferencesRepository,
) : ViewModel() {
    val keymap: StateFlow<Keymap> = shortcuts.keymap
    val defaults: Keymap = Keymap.default()
    val affinityPhoto2: Keymap = defaults.withAffinityPhoto2Shortcuts()

    fun presetFor(keymap: Keymap): ShortcutPreset = when {
        keymap.bindings == defaults.bindings -> ShortcutPreset.PHOTOSHOP
        keymap.bindings == affinityPhoto2.bindings -> ShortcutPreset.AFFINITY_PHOTO
        else -> ShortcutPreset.CUSTOM
    }

    fun applyPreset(preset: ShortcutPreset) {
        when (preset) {
            ShortcutPreset.PHOTOSHOP -> persist(defaults)
            ShortcutPreset.AFFINITY_PHOTO -> persist(affinityPhoto2)
            ShortcutPreset.CUSTOM -> Unit
        }
    }

    private fun persist(next: Keymap) {
        shortcuts.setKeymap(next)
        viewModelScope.launch { prefs.setKeymapJson(if (next.differencesFrom(defaults).isEmpty()) null else next.toJson()) }
    }

    /** Assigns [chord] to [id], removing it from any conflicting actions (the user confirmed). */
    fun assign(id: ActionId, chord: KeyChord) {
        var km = keymap.value
        km.actionsUsing(chord, id).forEach { other -> km = km.rebind(other, km.chordsFor(other) - chord) }
        persist(km.rebind(id, listOf(chord) + km.chordsFor(id).filter { it != chord }.drop(1)))
    }

    fun clear(id: ActionId) = persist(keymap.value.rebind(id, emptyList()))

    fun reset(id: ActionId) = persist(keymap.value.rebind(id, defaults.chordsFor(id)))

    fun resetAll() = persist(defaults)

    /** Returns null on success or a user-readable problem. */
    fun import(json: String): String? = runCatching { Keymap.fromJson(json) }.fold(
        onSuccess = { imported ->
            persist(imported)
            if (imported.problems.isEmpty()) null else "Imported with ${imported.problems.size} skipped entries."
        },
        onFailure = { "That file isn't a CropTop keymap." },
    )

    fun exportJson(): String = keymap.value.toJson()
}
