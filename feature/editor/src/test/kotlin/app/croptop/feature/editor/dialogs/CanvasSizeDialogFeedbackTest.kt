package app.croptop.feature.editor.dialogs

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import app.croptop.core.designsystem.theme.AeDensity
import app.croptop.core.designsystem.theme.CropTopTheme
import app.croptop.core.model.DocSize
import app.croptop.core.model.SelectionModify
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h900dp-mdpi")
class CanvasSizeDialogFeedbackTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun selectingCanvasAnchorReportsItsValue() {
        var action: String? = null
        compose.setContent {
            CropTopTheme(density = AeDensity.Pointer) {
                CanvasSizeDialog(
                    size = DocSize(100, 100),
                    ppi = 72f,
                    onApply = { _, _ -> },
                    onDismiss = {},
                    onActionCompleted = { action = it },
                )
            }
        }

        compose.onNodeWithContentDescription("Anchor top left").performClick()

        assertEquals("Set canvas anchor to top left", action)
    }

    @Test
    fun selectingImageSizeUnitReportsItsValue() {
        var action: String? = null
        compose.setContent {
            CropTopTheme(density = AeDensity.Pointer) {
                ImageSizeDialog(
                    size = DocSize(100, 100),
                    ppi = 72f,
                    bytesPerChannel = 4,
                    onApply = { _, _ -> },
                    onDismiss = {},
                    onActionCompleted = { action = it },
                )
            }
        }

        compose.onNodeWithText("Pixels").performClick()
        compose.onNodeWithText("Inches").performClick()

        assertEquals("Set image size unit to Inches", action)
    }

    @Test
    fun committingSelectionModifyAmountReportsItsOperation() {
        var action: String? = null
        compose.setContent {
            CropTopTheme(density = AeDensity.Pointer) {
                ModifySelectionDialog(
                    kind = SelectionModify.FEATHER,
                    onApply = {},
                    onDismiss = {},
                    onActionCompleted = { action = it },
                )
            }
        }

        val amount = compose.onNodeWithContentDescription("Feather Radius")
        amount.performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNode(hasSetTextAction()).performTextReplacement("12")
        compose.onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }

        assertEquals("Set feather amount", action)
    }
}
