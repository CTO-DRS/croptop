package app.croptop.feature.editor.dialogs

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import app.croptop.core.designsystem.theme.AeDensity
import app.croptop.core.designsystem.theme.CropTopTheme
import app.croptop.core.model.LayerId
import app.croptop.core.model.LayerStyles
import app.croptop.core.model.StyleColorOverlay
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h600dp-mdpi")
class LayerStyleDialogTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun presetPreviewsAndApplyCommitsTheSelectedStyle() {
        val id = LayerId(7)
        var preview: LayerStyles? = null
        var applied: LayerStyles? = null
        var copied: LayerStyles? = null
        var dismissed = false
        compose.setContent {
            CropTopTheme(density = AeDensity.Touch) {
                LayerStyleDialog(
                    layerId = id,
                    layerName = "Artwork",
                    initial = null,
                    onPreview = { _, styles ->
                        preview = styles
                        true
                    },
                    onApply = { _, styles ->
                        applied = styles
                        true
                    },
                    onCopy = { copied = it },
                    onPaste = { copied },
                    onDismiss = { dismissed = true },
                )
            }
        }

        compose.onNodeWithText("Soft Shadow").performClick()
        compose.onNodeWithText("Warm Glow").performClick()
        compose.onNodeWithText("Load Preset").performClick()
        assertTrue(preview?.outerGlow?.enabled == true)
        compose.onNodeWithText("Copy Style").performClick()

        compose.onNodeWithText("Warm Glow").performClick()
        compose.onNodeWithText("Soft Shadow").performClick()
        compose.onNodeWithText("Load Preset").performClick()
        assertTrue(preview?.dropShadow?.enabled == true)
        compose.onNodeWithText("Paste Style").performClick()
        assertTrue(preview?.outerGlow?.enabled == true)
        assertTrue(preview?.dropShadow?.enabled == false)

        compose.onNode(hasText("Apply", substring = false) and hasClickAction())
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        assertEquals(preview, applied)
        assertTrue(dismissed)
    }

    @Test
    fun cancelRestoresTheOriginalLayerStyleAfterPreview() {
        val id = LayerId(8)
        val original = LayerStyles(colorOverlay = StyleColorOverlay(enabled = true))
        var preview: LayerStyles? = null
        var dismissed = false
        compose.setContent {
            CropTopTheme(density = AeDensity.Touch) {
                LayerStyleDialog(
                    layerId = id,
                    layerName = "Artwork",
                    initial = original,
                    onPreview = { _, styles ->
                        preview = styles
                        true
                    },
                    onApply = { _, _ -> error("Cancel must not apply") },
                    onCopy = {},
                    onPaste = { null },
                    onDismiss = { dismissed = true },
                )
            }
        }

        compose.onNodeWithText("Soft Shadow").performClick()
        compose.onNodeWithText("Warm Glow").performClick()
        compose.onNodeWithText("Load Preset").performClick()
        compose.onNode(hasText("Cancel", substring = false) and hasClickAction())
            .performSemanticsAction(SemanticsActions.OnClick) { it() }

        assertEquals(original, preview)
        assertTrue(dismissed)
    }

    @Test
    fun rejectedApplyKeepsDialogOpenAndShowsFailure() {
        var dismissed = false
        compose.setContent {
            CropTopTheme(density = AeDensity.Touch) {
                LayerStyleDialog(
                    layerId = LayerId(10),
                    layerName = "Artwork",
                    initial = null,
                    onPreview = { _, _ -> true },
                    onApply = { _, _ -> false },
                    onCopy = {},
                    onPaste = { null },
                    onDismiss = { dismissed = true },
                )
            }
        }

        compose.onNode(hasText("Apply", substring = false) and hasClickAction())
            .performSemanticsAction(SemanticsActions.OnClick) { it() }

        compose.onNodeWithText("Style could not be applied").assertExists()
        assertTrue(!dismissed)
    }

    @Test
    fun enterAppliesStyleAndDismissesDialog() {
        var applied: LayerStyles? = null
        var dismissed = false
        compose.setContent {
            CropTopTheme(density = AeDensity.Touch) {
                LayerStyleDialog(
                    layerId = LayerId(11),
                    layerName = "Artwork",
                    initial = null,
                    onPreview = { _, _ -> true },
                    onApply = { _, styles ->
                        applied = styles
                        true
                    },
                    onCopy = {},
                    onPaste = { null },
                    onDismiss = { dismissed = true },
                )
            }
        }

        compose.onNodeWithText("Cancel").performKeyInput { pressKey(Key.Enter) }

        assertEquals(LayerStyles(), applied)
        assertTrue(dismissed)
    }

    @Test
    fun gradientAndBevelSectionsExposeTheirModelControls() {
        compose.setContent {
            CropTopTheme(density = AeDensity.Touch) {
                LayerStyleDialog(
                    layerId = LayerId(9),
                    layerName = "Artwork",
                    initial = null,
                    onPreview = { _, _ -> true },
                    onApply = { _, _ -> true },
                    onCopy = {},
                    onPaste = { null },
                    onDismiss = {},
                )
            }
        }

        compose.onAllNodes(hasText("Drop Shadow", substring = false) and hasClickAction()).onFirst().performClick()
        compose.onAllNodes(hasText("Gradient Overlay", substring = false) and hasClickAction()).get(1).performClick()
        compose.onNodeWithText("Add stop").assertExists()
        compose.onNodeWithText("Reverse").assertExists()

        compose.onAllNodes(hasText("Gradient Overlay", substring = false) and hasClickAction()).get(0).performClick()
        compose.onAllNodes(hasText("Bevel & Emboss", substring = false) and hasClickAction()).get(1).performClick()
        compose.onNodeWithText("Chisel Hard").assertExists()
        compose.onNodeWithText("Soften").assertExists()
        compose.onNodeWithText("Altitude").assertExists()
        compose.onNodeWithText("Highlight Opacity").assertExists()
        compose.onNodeWithText("Shadow Opacity").assertExists()
    }
}
