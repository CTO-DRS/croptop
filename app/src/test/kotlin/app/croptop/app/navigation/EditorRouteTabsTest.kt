package app.croptop.app.navigation

import androidx.navigation3.runtime.NavKey
import app.croptop.feature.editor.EditorRoute
import app.croptop.feature.home.HomeRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EditorRouteTabsTest {
    @Test
    fun activatingDocumentMovesItsExistingEntryToTheTopAndKeepsEveryTab() {
        val first = EditorRoute(sourceUri = "content://docs/first")
        val second = EditorRoute(sourceUri = "content://docs/second")
        val stack = mutableListOf<NavKey>(HomeRoute, first, second)
        val order = mutableListOf(first, second)
        val tabs = EditorRouteTabs(stack, order)

        tabs.activate(first)

        assertEquals(listOf(HomeRoute, second, first), stack)
        assertEquals(listOf(first, second), order)
    }

    @Test
    fun reorderChangesTabOrderWithoutChangingTheActiveRoute() {
        val first = EditorRoute(sourceUri = "content://docs/first")
        val second = EditorRoute(sourceUri = "content://docs/second")
        val stack = mutableListOf<NavKey>(HomeRoute, first, second)
        val order = mutableListOf(first, second)
        val tabs = EditorRouteTabs(stack, order)

        tabs.move(first, second)

        assertEquals(listOf(second, first), order)
        assertEquals(second, stack.last())
        tabs.activate(first)
        assertEquals(first, stack.last())
        assertEquals(listOf(second, first), order)
    }

    @Test
    fun syncAndRemoveKeepTheTabCollectionAlignedWithNavigationEntries() {
        val first = EditorRoute(sourceUri = "content://docs/first")
        val second = EditorRoute(sourceUri = "content://docs/second")
        val stale = EditorRoute(sourceUri = "content://docs/closed")
        val stack = mutableListOf<NavKey>(HomeRoute, first, second)
        val order = mutableListOf(stale, first)
        val tabs = EditorRouteTabs(stack, order)

        tabs.sync()

        assertEquals(listOf(first, second), order)
        assertTrue(first in stack)
        assertTrue(second in stack)
        assertFalse(stale in order)

        tabs.remove(second)

        assertEquals(listOf(HomeRoute, first), stack)
        assertEquals(listOf(first), order)
    }
}
