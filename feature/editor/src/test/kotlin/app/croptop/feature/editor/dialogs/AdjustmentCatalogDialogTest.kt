package app.croptop.feature.editor.dialogs

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.croptop.core.designsystem.theme.AeDensity
import app.croptop.core.designsystem.theme.CropTopTheme
import app.croptop.core.designsystem.theme.ThemePreference
import app.croptop.core.model.AdjustmentCatalog
import app.croptop.core.model.FilterCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h900dp-mdpi")
class AdjustmentCatalogDialogTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun catalogKeepsUnsupportedAdjustmentsVisibleAndExplainsWhy() {
        var picked: String? = null
        compose.setContent {
            CropTopTheme(theme = ThemePreference.Dark, density = AeDensity.Pointer) {
                AdjustmentCatalogDialog(
                    specs = AdjustmentCatalog.All,
                    availability = { spec ->
                        if (spec.id == "invert") {
                            AdjustmentAvailability.Supported
                        } else {
                            AdjustmentAvailability.Disabled(AdjustmentDisabledReason.ENGINE_UNSUPPORTED)
                        }
                    },
                    onPick = { picked = it.id },
                    onDismiss = {},
                )
            }
        }

        assertNull(picked)
        compose.onNodeWithText("Posterize").assertExists()
        compose.onAllNodesWithText(AdjustmentDisabledReason.ENGINE_UNSUPPORTED.message).assertCountEquals(AdjustmentCatalog.All.size - 1)

        compose.onNodeWithText("Invert").performClick()
        assertEquals("invert", picked)
    }

    @Test
    fun filterCatalogRendersExistingGroupsAndKeepsProEntriesSelectableForPreview() {
        var picked: String? = null
        compose.setContent {
            CropTopTheme(theme = ThemePreference.Dark, density = AeDensity.Pointer) {
                AdjustmentCatalogDialog(
                    specs = FilterCatalog.All,
                    availability = { AdjustmentAvailability.Supported },
                    onPick = { picked = it.id },
                    onDismiss = {},
                    title = "Filters",
                    groups = FilterCatalog.groups,
                )
            }
        }

        compose.onNodeWithText("Blur (6)").assertExists()
        compose.onNodeWithText("Sharpen (3)").assertExists()
        compose.onNodeWithText("Distort (7)").assertExists()
        compose.onNodeWithText("Lens Correction (2)").assertExists()
        compose.onNodeWithText("Defringe").assertExists()
        compose.onAllNodesWithText("PRO").assertCountEquals(0)
        compose.onNodeWithText("Radial Blur").performClick()

        assertEquals("filter.radialBlur", picked)
    }
}
