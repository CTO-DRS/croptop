package app.croptop.core.designsystem.component

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.swipeUp
import app.croptop.core.designsystem.theme.CropTopTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AeMenuAccessibilityTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun longMenuCanScrollToFinalAction() {
        showLongMenu()

        val menu = compose.onNodeWithTag("ae-menu-scroll-container")
        repeat(6) {
            menu.performTouchInput { swipeUp() }
        }

        compose.onNodeWithText("Final Smart Object action").assertIsDisplayed()
    }

    @Test
    fun keyboardHighlightScrollsFinalActionIntoView() {
        showLongMenu()

        val menu = compose.onNodeWithTag("ae-menu-scroll-container")
        menu.performSemanticsAction(SemanticsActions.RequestFocus) { requestFocus -> check(requestFocus()) }
        menu.performKeyInput {
            repeat(36) { pressKey(Key.DirectionDown) }
        }
        compose.waitForIdle()

        compose.onNodeWithText("Final Smart Object action").assertIsDisplayed()
    }

    private fun showLongMenu() {
        val entries = buildList {
            repeat(35) { index -> add(AeMenuEntry.Item("Layer action $index", {})) }
            add(AeMenuEntry.Item("Final Smart Object action", {}))
        }
        compose.setContent {
            CropTopTheme {
                AeMenu(expanded = true, onDismiss = {}, entries = entries)
            }
        }
        compose.waitForIdle()
    }
}
