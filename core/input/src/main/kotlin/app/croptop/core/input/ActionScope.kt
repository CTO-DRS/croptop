package app.croptop.core.input

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import app.croptop.core.model.ToolGroup

val LocalActionDispatcher = staticCompositionLocalOf<ActionDispatcher> { error("ActionDispatcher not provided") }

class ActionHandlersBuilder internal constructor() {
    internal val handlers = mutableMapOf<ActionId, () -> Unit>()
    internal var cycle: ((ToolGroup) -> Unit)? = null

    fun on(id: String, handler: () -> Unit) {
        handlers[ActionId(id)] = handler
    }

    fun on(id: ActionId, handler: () -> Unit) {
        handlers[id] = handler
    }

    fun onCycleTool(handler: (ToolGroup) -> Unit) {
        cycle = handler
    }
}

/**
 * Registers action handlers while this composable is in the composition.
 * Handlers are refreshed every recomposition so they always see current state.
 */
@Composable
fun ActionScope(scope: ShortcutScope, build: ActionHandlersBuilder.() -> Unit) {
    val dispatcher = LocalActionDispatcher.current
    val builder = ActionHandlersBuilder().apply(build)
    val registration = remember(dispatcher, scope) { dispatcher.register(scope, emptyMap()) }
    SideEffect {
        registration.handlers = builder.handlers.toMap()
        registration.onCycleTool = builder.cycle
    }
    DisposableEffect(registration) { onDispose { dispatcher.unregister(registration) } }
}

/** Tooltip/menu label for an action, e.g. "Ctrl+Shift+Z", or null when unbound. */
@Composable
fun shortcutLabel(id: String): String? = LocalActionDispatcher.current.shortcuts.label(ActionId(id))
