package app.croptop.feature.editor.brush

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import app.croptop.core.designsystem.theme.AeDensity
import app.croptop.core.designsystem.theme.CropTopTheme
import app.croptop.core.model.BrushPreset
import app.croptop.core.model.DynamicsInput
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class BrushPanelFeedbackTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun savingNewBrushReportsDialogOpenAndCancel() {
        val actions = mutableListOf<String>()
        compose.setContent {
            CropTopTheme(density = AeDensity.Pointer) {
                BrushSettingsPanelContent(BrushUi(onActionCompleted = actions::add))
            }
        }

        compose.onNodeWithText("Save as New…").performClick()
        compose.onNodeWithText("Cancel").performClick()

        compose.runOnIdle { assertEquals(listOf("Open Save Brush dialog", "Cancel Save Brush"), actions) }
    }

    @Test
    fun openingPresetPickerReportsCompletion() {
        val actions = mutableListOf<String>()
        compose.setContent {
            CropTopTheme(density = AeDensity.Pointer) {
                BrushOptions(BrushUi(onActionCompleted = actions::add))
            }
        }

        compose.onNodeWithContentDescription("Brush preset, ${BrushPreset.BuiltIn.first().name}").performClick()

        compose.runOnIdle { assertEquals(listOf("Open brush preset picker"), actions) }
    }

    @Test
    fun brushFilterAndSearchSubmitReportAtTheirActionBoundaries() {
        val actions = mutableListOf<String>()
        compose.setContent {
            CropTopTheme(density = AeDensity.Pointer) {
                BrushesPanelContent(BrushUi(onActionCompleted = actions::add))
            }
        }

        compose.onNodeWithText("Favorites").performClick()
        compose.onNodeWithContentDescription("Search brushes").performTextInput("soft")
        compose.onNodeWithContentDescription("Search brushes").performImeAction()

        compose.runOnIdle { assertEquals(listOf("Filter brushes: Favorites", "Search brush presets"), actions) }
    }

    @Test
    fun responseCurveDisclosureReportsOpenAndClose() {
        val actions = mutableListOf<String>()
        val defaults = BrushUi().settings
        val settings = defaults.copy(sizeDynamics = defaults.sizeDynamics.copy(input = DynamicsInput.PRESSURE))
        compose.setContent {
            CropTopTheme(density = AeDensity.Pointer) {
                BrushSettingsPanelContent(BrushUi(settings = settings, onActionCompleted = actions::add))
            }
        }

        compose.onNodeWithContentDescription("Edit Size response curve").performClick()

        compose.runOnIdle {
            assertEquals(listOf("Open Size response curve"), actions)
        }
    }
}
