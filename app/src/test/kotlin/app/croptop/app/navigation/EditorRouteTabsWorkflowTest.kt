package app.croptop.app.navigation

import androidx.navigation3.runtime.NavKey
import app.croptop.feature.editor.EditorRoute
import app.croptop.feature.home.HomeRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EditorRouteTabsWorkflowTest {
    @Test
    fun smartObjectChildIsAddedAsSiblingAndParentAndChildRemainSwitchable() {
        val parent = EditorRoute(sourceUri = "content://docs/parent")
        val child = EditorRoute(
            openDocumentId = 73L,
            smartObjectParentDocumentId = 19L,
            smartObjectParentLayerId = 5L,
        )
        val stack = mutableListOf<NavKey>(HomeRoute, parent)
        val order = mutableListOf(parent)
        val tabs = EditorRouteTabs(stack, order)

        // CropTopApp opens Edit Contents by adding the linked child to the same route collection.
        stack.add(child)
        tabs.sync()
        assertEquals(listOf(parent, child), order)

        tabs.activate(parent)
        assertEquals(parent, stack.last())
        assertEquals(listOf(parent, child), order)
        assertTrue(child in stack)

        tabs.activate(child)
        assertEquals(child, stack.last())
        assertEquals(listOf(parent, child), order)
        assertTrue(parent in stack)
    }

    @Test
    fun closingOneTabPreservesItsSiblingRouteAndReorderDoesNotChangeTheActiveDocument() {
        val first = EditorRoute(sourceUri = "content://docs/first")
        val second = EditorRoute(sourceUri = "content://docs/second")
        val stack = mutableListOf<NavKey>(HomeRoute, first, second)
        val order = mutableListOf(first, second)
        val tabs = EditorRouteTabs(stack, order)

        tabs.move(second, first)
        assertEquals(listOf(second, first), order)
        assertEquals(second, stack.last())

        tabs.remove(first)

        assertEquals(listOf(HomeRoute, second), stack)
        assertEquals(listOf(second), order)
    }
}
