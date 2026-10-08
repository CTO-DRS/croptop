package app.croptop.feature.editor.dialogs

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.croptop.core.designsystem.theme.CropTopTheme
import app.croptop.core.model.SelectionModify
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h900dp-mdpi")
class ModifySelectionPreviewTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun opensWithALivePreviewAndCancelRestores() {
        val previews = mutableListOf<Float>()
        var cancelledPreview = 0
        var dismissed = 0
        compose.setContent {
            CropTopTheme {
                ModifySelectionDialog(
                    SelectionModify.EXPAND,
                    onApply = {},
                    onDismiss = { dismissed++ },
                    onPreview = { previews += it },
                    onCancelPreview = { cancelledPreview++ },
                )
            }
        }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        assertEquals(listOf(5f), previews, "the default amount previews once the dialog settles")
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(1, cancelledPreview)
        assertEquals(1, dismissed)
        assertTrue(previews.size == 1)
    }

    @Test
    fun okAppliesTheCurrentAmountWithoutCancellingThePreview() {
        var applied: Float? = null
        var cancelledPreview = 0
        compose.setContent {
            CropTopTheme {
                ModifySelectionDialog(SelectionModify.CONTRACT, onApply = { applied = it }, onDismiss = {}, onPreview = {}, onCancelPreview = { cancelledPreview++ })
            }
        }
        compose.onNodeWithText("OK").performClick()
        assertEquals(5f, applied)
        assertEquals(0, cancelledPreview)
    }
}
