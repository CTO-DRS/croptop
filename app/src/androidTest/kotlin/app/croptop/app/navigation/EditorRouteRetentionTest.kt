package app.croptop.app.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.croptop.core.designsystem.theme.AeDensity
import app.croptop.core.designsystem.theme.CropTopTheme
import app.croptop.feature.editor.EditorRoute
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the same NavDisplay decorators and EditorRouteTabs used by CropTopApp. */
@RunWith(AndroidJUnit4::class)
class EditorRouteRetentionTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun switchingBetweenEditorSiblingRoutesRetainsEachRouteViewModel() {
        val first = EditorRoute(sourceUri = "content://test/first")
        val second = EditorRoute(sourceUri = "content://test/second")
        compose.setContent {
            val backStack = remember { mutableStateListOf<NavKey>(first, second) }
            val order = remember { mutableListOf(first, second) }
            val tabs = remember(backStack) { EditorRouteTabs(backStack, order) }
            CropTopTheme(density = AeDensity.Touch) {
                NavDisplay(
                    backStack = backStack,
                    onBack = { if (backStack.isNotEmpty()) backStack.removeAt(backStack.lastIndex) },
                    entryDecorators = listOf(
                        rememberSaveableStateHolderNavEntryDecorator(),
                        rememberViewModelStoreNavEntryDecorator(),
                    ),
                    entryProvider = entryProvider {
                        entry<EditorRoute> { route ->
                            val counter = viewModel<RouteCounterViewModel>()
                            val other = if (route == first) second else first
                            Column(Modifier.fillMaxSize()) {
                                Text("Count ${route.sourceUri}: ${counter.value}")
                                Button(onClick = { counter.increment() }) { Text("Increment ${route.sourceUri}") }
                                Button(onClick = { tabs.activate(other) }) { Text("Activate ${other.sourceUri}") }
                            }
                        }
                    },
                )
            }
        }

        compose.onNodeWithText("Increment content://test/second").performClick()
        compose.onNodeWithText("Activate content://test/first").performClick()
        compose.onNodeWithText("Increment content://test/first").performClick()
        compose.onNodeWithText("Activate content://test/second").performClick()
        compose.onNodeWithText("Count content://test/second: 1").assertExists()
        compose.onNodeWithText("Increment content://test/second").performClick()
        compose.onNodeWithText("Activate content://test/first").performClick()
        compose.onNodeWithText("Count content://test/first: 1").assertExists()
    }
}

class RouteCounterViewModel : ViewModel() {
    var value by mutableIntStateOf(0)
        private set

    fun increment() {
        value++
    }
}
