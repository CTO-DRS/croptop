package app.croptop.feature.editor

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.croptop.core.agent.OpenDocuments
import app.croptop.core.designsystem.theme.AeDensity
import app.croptop.core.designsystem.theme.CropTopTheme
import app.croptop.core.designsystem.theme.ThemePreference
import app.croptop.core.engine.fake.FakeEngine
import app.croptop.core.input.ActionDispatcher
import app.croptop.core.input.LocalActionDispatcher
import app.croptop.core.input.ShortcutManager
import app.croptop.core.model.DocSize
import app.croptop.core.model.NewDocumentSpec
import app.croptop.feature.editor.session.EditorViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-mdpi")
class EditorDirtyTabCloseTest {
    @get:Rule
    val compose = createComposeRule()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun dirtyTabCloseRequestPromptsAndCancelKeepsTheDocumentOpen() {
        var closeCount = 0
        val (vm, requestClose) = showDirtyEditor { closeCount++ }

        requestClose.value = true
        compose.waitForIdle()

        compose.onNodeWithText("Save changes to “Tab document”?", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()

        assertEquals(0, closeCount)
        compose.onNodeWithText("Tab document").assertExists()
        assertEquals(true, vm.state.value.dirty)
    }

    @Test
    fun dirtyTabCloseRequestClosesOnlyAfterDiscardIsConfirmed() {
        var closeCount = 0
        val (vm, requestClose) = showDirtyEditor { closeCount++ }

        requestClose.value = true
        compose.waitForIdle()
        compose.onNodeWithText("Don't Save").performClick()
        compose.waitForIdle()

        assertEquals(true, vm.state.value.dirty)
        assertEquals(1, closeCount)
    }

    private fun showDirtyEditor(onClose: () -> Unit): Pair<EditorViewModel, androidx.compose.runtime.MutableState<Boolean>> {
        val route = EditorRoute(newSpec = NewDocumentSpec(name = "Tab document", size = DocSize(400, 300)))
        val engine = FakeEngine()
        val vm = EditorViewModel(
            engine,
            NoRecents,
            TempShareCache,
            OpenDocuments(engine),
            { null },
            route,
        )
        val requestClose = mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalActionDispatcher provides ActionDispatcher(ShortcutManager())) {
                CropTopTheme(theme = ThemePreference.Dark, density = AeDensity.Pointer) {
                    EditorContent(
                        vm = vm,
                        route = route,
                        onClose = onClose,
                        documentTabs = listOf(EditorDocumentTab(route, "Tab document", dirty = true)),
                        closeTabOnActivation = requestClose.value,
                        onCloseTabRequestConsumed = { requestClose.value = false },
                    )
                }
            }
        }
        compose.waitForIdle()
        vm.newLayer()
        compose.waitForIdle()
        return vm to requestClose
    }
}
