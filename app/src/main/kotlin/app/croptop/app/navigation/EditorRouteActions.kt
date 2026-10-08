package app.croptop.app.navigation

import androidx.navigation3.runtime.NavKey
import app.croptop.core.input.ActionId
import app.croptop.core.model.NewDocumentSpec
import app.croptop.feature.editor.EditorRoute

/** File-menu actions that remain available while an editor route is the active screen. */
internal class EditorFileActionHandlers(
    onNewDocument: () -> Unit,
    onOpenDocument: () -> Unit,
) {
    val handlers: Map<ActionId, () -> Unit> = mapOf(
        ActionId("app.new") to onNewDocument,
        ActionId("app.open") to onOpenDocument,
    )
}

/** Adds editor documents to the app's shared navigation stack so they appear as sibling tabs. */
internal class EditorRouteLauncher(private val backStack: MutableList<NavKey>) {
    fun openUri(uri: String) {
        backStack.add(EditorRoute(sourceUri = uri))
    }

    fun create(spec: NewDocumentSpec) {
        backStack.add(EditorRoute(newSpec = spec))
    }
}
