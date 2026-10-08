package app.croptop.core.input

import android.view.KeyEvent
import app.croptop.core.model.ToolGroup

/**
 * Routes resolved shortcuts, menu clicks and palette picks to whichever screen currently handles
 * them. Screens register handler scopes (see [ActionScope]); the most recently registered scope
 * wins, falling back to older ones (e.g. Editor → app-level GLOBAL handlers).
 */
class ActionDispatcher(val shortcuts: ShortcutManager) {

    class Registration internal constructor(
        internal val scope: ShortcutScope,
        internal var handlers: Map<ActionId, () -> Unit>,
        internal var onCycleTool: ((ToolGroup) -> Unit)?,
    )

    private val stack = ArrayDeque<Registration>()

    /** Set while a text field has focus; single-key shortcuts are then ignored. */
    @Volatile
    var textInputActive: Boolean = false

    val activeScope: ShortcutScope
        get() = stack.lastOrNull { it.scope != ShortcutScope.GLOBAL }?.scope ?: ShortcutScope.GLOBAL

    fun register(scope: ShortcutScope, handlers: Map<ActionId, () -> Unit>, onCycleTool: ((ToolGroup) -> Unit)? = null): Registration =
        Registration(scope, handlers, onCycleTool).also { stack.addLast(it) }

    fun unregister(registration: Registration) {
        stack.remove(registration)
    }

    /** Invoke [id] if any registered scope handles it. Returns true when handled. */
    fun perform(id: ActionId): Boolean {
        for (r in stack.asReversed()) {
            r.handlers[id]?.let {
                it()
                return true
            }
        }
        return false
    }

    fun canPerform(id: ActionId): Boolean = stack.any { id in it.handlers }

    /** Returns true when the key event was consumed. Call from Activity.dispatchKeyEvent. */
    fun dispatch(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        return when (val hit = shortcuts.resolve(event, activeScope, textInputActive)) {
            is ShortcutHit.Action -> perform(hit.id)

            is ShortcutHit.CycleTool -> stack.asReversed().firstNotNullOfOrNull { it.onCycleTool }?.let {
                it(hit.group)
                true
            } ?: false

            null -> false
        }
    }
}
