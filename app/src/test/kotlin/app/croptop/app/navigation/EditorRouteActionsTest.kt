package app.croptop.app.navigation

import androidx.navigation3.runtime.NavKey
import app.croptop.core.input.ActionDispatcher
import app.croptop.core.input.ActionId
import app.croptop.core.input.ShortcutManager
import app.croptop.core.input.ShortcutScope
import app.croptop.core.model.DocSize
import app.croptop.core.model.NewDocumentSpec
import app.croptop.feature.editor.EditorRoute
import app.croptop.feature.home.HomeRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EditorRouteActionsTest {
    @Test
    fun editorTopKeepsNewAndOpenEnabledThroughGlobalFallbackHandlers() {
        var newRequests = 0
        var openRequests = 0
        val handlers = EditorFileActionHandlers(
            onNewDocument = { newRequests++ },
            onOpenDocument = { openRequests++ },
        )
        val dispatcher = ActionDispatcher(ShortcutManager())
        dispatcher.register(ShortcutScope.GLOBAL, handlers.handlers)
        dispatcher.register(ShortcutScope.EDITOR, emptyMap())

        val new = ActionId("app.new")
        val open = ActionId("app.open")
        assertTrue(dispatcher.canPerform(new))
        assertTrue(dispatcher.canPerform(open))
        assertTrue(dispatcher.perform(new))
        assertTrue(dispatcher.perform(open))
        assertEquals(1, newRequests)
        assertEquals(1, openRequests)
    }

    @Test
    fun newAndOpenedFilesBecomeSiblingEditorRoutes() {
        val stack = mutableListOf<NavKey>(HomeRoute)
        val routes = EditorRouteLauncher(stack)
        val spec = NewDocumentSpec(name = "Poster", size = DocSize(1200, 800))

        routes.create(spec)
        val created = stack.last() as EditorRoute
        routes.openUri("content://photos/second.png")
        val opened = stack.last() as EditorRoute

        assertEquals(spec, created.newSpec)
        assertEquals("content://photos/second.png", opened.sourceUri)
        assertEquals(listOf(HomeRoute, created, opened), stack)
    }
}
