package app.croptop.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.theme.AeDensity
import app.croptop.core.designsystem.theme.CropTopTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AeContextMenuAccessibilityTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun semanticLongClickOpensContextMenu() {
        setContextMenu()

        compose.onNodeWithContentDescription("Canvas context target")
            .performSemanticsAction(SemanticsActions.OnLongClick) { action -> check(action()) }

        compose.onNodeWithText("Rename").fetchSemanticsNode()
    }

    @Test
    fun touchLongPressOpensContextMenu() {
        setContextMenu()

        compose.onNodeWithContentDescription("Canvas context target").performTouchInput { longClick() }

        compose.onNodeWithText("Rename").fetchSemanticsNode()
    }

    private fun setContextMenu() {
        compose.setContent {
            CropTopTheme(density = AeDensity.Touch) {
                AeContextMenuArea(entries = { listOf(AeMenuEntry.Item("Rename", {})) }) {
                    Box(
                        Modifier
                            .size(160.dp)
                            .semantics { contentDescription = "Canvas context target" },
                    )
                }
            }
        }
    }
}
