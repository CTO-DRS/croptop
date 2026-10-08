package app.croptop.core.input

import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import app.croptop.core.model.ToolGroup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Result of resolving a key press. */
sealed interface ShortcutHit {
    data class Action(val id: ActionId) : ShortcutHit

    /** Shift + a tool key: cycle tools within the group. */
    data class CycleTool(val group: ToolGroup) : ShortcutHit
}

/**
 * Resolves key chords to actions for the active [ShortcutScope], manages user remaps, and exports
 * groups for the system keyboard-shortcut helper (Meta+/).
 */
class ShortcutManager(initial: Keymap = Keymap.default()) {
    private val _keymap = MutableStateFlow(initial)
    val keymap: StateFlow<Keymap> = _keymap.asStateFlow()

    private var index: Map<KeyChord, List<ActionId>> = buildIndex(initial)

    fun setKeymap(keymap: Keymap) {
        _keymap.value = keymap
        index = buildIndex(keymap)
    }

    fun rebind(id: ActionId, chords: List<KeyChord>) = setKeymap(_keymap.value.rebind(id, chords))

    /**
     * [textInputActive]: single-key (unmodified or Shift-only) shortcuts are suppressed while a text
     * field has focus, so typing "B" in the layer name doesn't switch to the brush.
     */
    fun resolve(chord: KeyChord, scope: ShortcutScope, textInputActive: Boolean = false): ShortcutHit? {
        if (textInputActive && !chord.ctrl && !chord.alt && !chord.meta) return null
        index[chord]?.firstOrNull { inScope(it, scope) }?.let { return ShortcutHit.Action(it) }
        if (scope == ShortcutScope.EDITOR && chord.shift && !chord.ctrl && !chord.alt && !chord.meta) {
            val plain = chord.copy(shift = false)
            index[plain]?.firstOrNull { it.id.startsWith("tool.") && inScope(it, scope) }?.let { id ->
                ToolGroup.entries.firstOrNull { toolAction(it) == id }?.let { return ShortcutHit.CycleTool(it) }
            }
        }
        return null
    }

    fun resolve(event: KeyEvent, scope: ShortcutScope, textInputActive: Boolean = false): ShortcutHit? {
        if (event.action != KeyEvent.ACTION_DOWN) return null
        return resolve(event.toChord(), scope, textInputActive)
    }

    /** Label for tooltips/menus, e.g. "Ctrl+Shift+Z". */
    fun label(id: ActionId): String? = _keymap.value.primaryChord(id)?.format()

    /** Groups for `Activity.onProvideKeyboardShortcuts`, one per category. */
    fun keyboardShortcutGroups(scopes: Set<ShortcutScope>): List<KeyboardShortcutGroup> {
        val km = _keymap.value
        return ActionCategory.entries.filter { it.scope in scopes }.mapNotNull { cat ->
            val infos = Actions.all.filter { it.category == cat }.flatMap { def ->
                km.chordsFor(def.id).take(1).map { KeyboardShortcutInfo(def.label, it.keyCode, it.metaState) }
            }
            if (infos.isEmpty()) null else KeyboardShortcutGroup(cat.label, infos)
        }
    }

    private fun inScope(id: ActionId, scope: ShortcutScope): Boolean {
        val s = Actions[id]?.category?.scope ?: return false
        return s == ShortcutScope.GLOBAL || s == scope
    }

    private fun buildIndex(km: Keymap): Map<KeyChord, List<ActionId>> =
        km.bindings.flatMap { (id, chords) -> chords.map { it to id } }.groupBy({ it.first }, { it.second })
}

fun KeyEvent.toChord(): KeyChord = KeyChord(keyCode, isCtrlPressed, isShiftPressed, isAltPressed, isMetaPressed)
