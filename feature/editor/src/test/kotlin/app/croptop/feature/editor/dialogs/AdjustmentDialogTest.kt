package app.croptop.feature.editor.dialogs

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.croptop.core.designsystem.theme.AeDensity
import app.croptop.core.designsystem.theme.CropTopTheme
import app.croptop.core.designsystem.theme.ThemePreference
import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.RejectionCode
import app.croptop.core.model.AdjustmentCatalog
import app.croptop.core.model.FilterCatalog
import app.croptop.core.model.ParamValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h900dp-mdpi")
class AdjustmentDialogTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun filterPreviewUsesFilterSpecificCopy() {
        compose.setContent {
            CropTopTheme(theme = ThemePreference.Dark, density = AeDensity.Pointer) {
                AdjustmentDialog(
                    spec = FilterCatalog.All.first { it.id == "filter.gaussianBlur" },
                    onApply = { CommandResult.Accepted(1L) },
                    onDismiss = {},
                    isFilter = true,
                )
            }
        }

        compose.onNodeWithText(
            "Preview this filter on the active layer. Smart objects keep it as an editable smart filter.",
        ).assertExists()
        compose.onNodeWithText("New Adjustment Layer").assertDoesNotExist()
    }

    @Test
    fun everyFilterAppliesWithOkAndNoUnlockStep() {
        var committed = false
        compose.setContent {
            CropTopTheme(theme = ThemePreference.Dark, density = AeDensity.Pointer) {
                AdjustmentDialog(
                    spec = FilterCatalog.All.first { it.id == "filter.ripple" },
                    onApply = {
                        committed = true
                        CommandResult.Accepted(1L)
                    },
                    onDismiss = {},
                    isFilter = true,
                )
            }
        }

        compose.onNodeWithText("Unlock Pro").assertDoesNotExist()
        compose.onNodeWithText("OK").performClick()
        compose.waitForIdle()
        assertTrue(committed)
    }

    @Test
    fun presetEditsPreviewImmediatelyAndCommitTheSameValues() {
        val previews = mutableListOf<Map<String, ParamValue>>()
        var committed: Map<String, ParamValue>? = null
        var dismissed = false
        compose.setContent {
            CropTopTheme(theme = ThemePreference.Dark, density = AeDensity.Pointer) {
                AdjustmentDialog(
                    spec = AdjustmentCatalog["brightnessContrast"]!!,
                    onApply = {
                        committed = it
                        CommandResult.Accepted(1L)
                    },
                    onDismiss = { dismissed = true },
                    onPreview = { previews += it },
                )
            }
        }

        compose.onNodeWithText("Default").performClick()
        compose.onNodeWithText("Increase Contrast").performClick()

        assertFalse(dismissed)
        assertEquals(30f, (previews.last().getValue("contrast") as ParamValue.Num).v)
        compose.onNodeWithText("OK").performClick()

        assertTrue(dismissed)
        assertEquals(previews.last(), committed)
    }

    @Test
    fun cancelRestoresPreviewWithoutCommitting() {
        var previewed = false
        var canceled = false
        var committed = false
        compose.setContent {
            CropTopTheme(theme = ThemePreference.Dark, density = AeDensity.Pointer) {
                AdjustmentDialog(
                    spec = AdjustmentCatalog["brightnessContrast"]!!,
                    onApply = {
                        committed = true
                        CommandResult.Accepted(1L)
                    },
                    onDismiss = {},
                    onPreview = {
                        previewed = true
                    },
                    onCancelPreview = { canceled = true },
                )
            }
        }

        compose.onNodeWithText("Default").performClick()
        compose.onNodeWithText("Increase Contrast").performClick()
        compose.onNodeWithText("Cancel").performClick()

        assertTrue(previewed)
        assertTrue(canceled)
        assertFalse(committed)
    }

    @Test
    fun rejectedApplyKeepsThePreviewDialogOpenForCorrection() {
        var canApply = false
        var dismissed = false
        var applies = 0
        compose.setContent {
            CropTopTheme(theme = ThemePreference.Dark, density = AeDensity.Pointer) {
                AdjustmentDialog(
                    spec = AdjustmentCatalog["brightnessContrast"]!!,
                    onApply = {
                        applies += 1
                        if (canApply) {
                            CommandResult.Accepted(applies.toLong())
                        } else {
                            CommandResult.Rejected(RejectionCode.INVALID_ARGUMENT, "Not ready")
                        }
                    },
                    onDismiss = { dismissed = true },
                )
            }
        }

        compose.onNodeWithText("OK").performClick()
        assertFalse(dismissed)
        assertEquals(1, applies)

        canApply = true
        compose.onNodeWithText("OK").performClick()

        assertTrue(dismissed)
        assertEquals(2, applies)
    }
}
