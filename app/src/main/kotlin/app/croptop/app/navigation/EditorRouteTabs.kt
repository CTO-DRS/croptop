package app.croptop.app.navigation

import androidx.navigation3.runtime.NavKey
import app.croptop.feature.editor.EditorRoute

/** Keeps editor tab order separate from navigation history while reusing each route's saved state. */
internal class EditorRouteTabs(
    private val backStack: MutableList<NavKey>,
    private val tabOrder: MutableList<EditorRoute>,
) {
    fun sync() {
        val routes = backStack.filterIsInstance<EditorRoute>()
        tabOrder.removeAll { it !in routes }
        routes.forEach { if (it !in tabOrder) tabOrder.add(it) }
    }

    fun activate(route: EditorRoute) {
        if (route !in backStack || backStack.lastOrNull() == route) return
        val activeOrder = tabOrder.filter { it != route } + route
        backStack.indices.filter { backStack[it] is EditorRoute }
            .zip(activeOrder)
            .forEach { (index, tabRoute) -> backStack[index] = tabRoute }
    }

    fun move(from: EditorRoute, to: EditorRoute) {
        val fromIndex = tabOrder.indexOf(from)
        val toIndex = tabOrder.indexOf(to)
        if (fromIndex < 0 || toIndex < 0 || fromIndex == toIndex) return
        tabOrder.removeAt(fromIndex)
        tabOrder.add(toIndex, from)
    }

    fun remove(route: EditorRoute) {
        val index = backStack.indexOf(route)
        if (index >= 0) backStack.removeAt(index)
        tabOrder.remove(route)
    }
}
