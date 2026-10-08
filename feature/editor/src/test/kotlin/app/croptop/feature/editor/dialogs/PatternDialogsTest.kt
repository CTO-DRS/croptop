package app.croptop.feature.editor.dialogs

import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.croptop.core.designsystem.theme.CropTopTheme
import app.croptop.core.engine.api.LayerThumb
import app.croptop.core.engine.api.PatternInfo
import app.croptop.core.model.FillKind
import app.croptop.core.model.FillLayerInfo
import app.croptop.core.model.LayerId
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-mdpi")
class PatternDialogsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun definePatternValidatesAndSendsTrimmedName() {
        var definedName: String? = null
        compose.setContent {
            CropTopTheme {
                DefinePatternDialog({ name ->
                    definedName = name
                    null
                }, {})
            }
        }

        compose.onNodeWithText("Define").performClick()
        compose.onNodeWithText("Enter a name for this pattern").assertExists()
        compose.onNode(hasSetTextAction()).performTextInput("  Paper Grain  ")
        compose.onNodeWithText("Define").performClick()

        assertEquals("Paper Grain", definedName)
    }

    @Test
    fun patternFillRequiresAndAppliesSelectedLibraryPattern() {
        val pattern = PatternInfo("paper", "Paper Grain", 64, 64)
        var applied: FillLayerInfo? = null
        compose.setContent {
            CropTopTheme {
                FillLayerDialog(
                    layerId = null,
                    initial = FillLayerInfo(kind = FillKind.SOLID),
                    patterns = listOf(pattern),
                    patternThumbnail = { _, _ -> null },
                    onApply = { _, fill ->
                        applied = fill
                        true
                    },
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText("Solid Color").performClick()
        compose.onNodeWithText("Pattern").performClick()
        compose.onNodeWithText("New Pattern Fill Layer").assertExists()
        compose.onNodeWithText("Apply").assertIsNotEnabled()
        compose.onNodeWithText("Paper Grain").performClick()
        compose.onNodeWithText("Apply").performClick()

        assertEquals("paper", applied?.patternId)
        assertEquals(FillKind.PATTERN, applied?.kind)
    }

    @Test
    fun patternLibraryRequiresConfirmationBeforeDeletingSharedPattern() {
        val pattern = PatternInfo("paper", "Paper Grain", 64, 64)
        var deleted: String? = null
        compose.setContent {
            CropTopTheme {
                PatternLibraryDialog(
                    patterns = listOf(pattern),
                    patternThumbnail = { _, _ -> null },
                    onDelete = { deleted = it },
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText("Delete Paper Grain").performClick()
        compose.onNodeWithText("Delete Paper Grain?").assertExists()
        assertEquals(null, deleted)

        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Delete Paper Grain?").assertDoesNotExist()
        assertEquals(null, deleted)

        compose.onNodeWithText("Delete Paper Grain").performClick()
        compose.onNodeWithText("Delete Pattern").performClick()
        assertEquals("paper", deleted)
    }

    @Test
    fun patternLibraryLabelsThumbnailAsRenderedOverTransparencyGrid() {
        val pattern = PatternInfo("transparent", "Transparent Detail", 1, 1)
        compose.setContent {
            CropTopTheme {
                PatternPicker(
                    patterns = listOf(pattern),
                    selectedId = null,
                    onSelect = {},
                    patternThumbnail = { _, _ ->
                        LayerThumb(LayerId(1), 1, 1, byteArrayOf(0, 0, 0, 0))
                    },
                )
            }
        }

        compose.waitForIdle()
        val preview = compose.onNodeWithContentDescription("Transparent Detail preview on transparency grid")
        preview.assertExists()
        val pixels = preview.captureToImage().toPixelMap()
        assertNotEquals(pixels[2, 2], pixels[10, 2], "Adjacent columns should alternate through a transparent pattern")
        assertNotEquals(pixels[2, 2], pixels[2, 10], "Adjacent rows should alternate through a transparent pattern")
        assertEquals(pixels[2, 2], pixels[10, 10], "Diagonal cells should repeat the same checker color")
    }

    @Test
    fun editingFillCanChangeTypeAndPreservesLayerIdentity() {
        val layerId = LayerId(42)
        var appliedLayer: LayerId? = null
        var appliedFill: FillLayerInfo? = null
        compose.setContent {
            CropTopTheme {
                FillLayerDialog(
                    layerId = layerId,
                    initial = FillLayerInfo(kind = FillKind.GRADIENT),
                    onApply = { id, fill ->
                        appliedLayer = id
                        appliedFill = fill
                        true
                    },
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText("Gradient").performClick()
        compose.onNodeWithText("Solid Color").performClick()
        compose.onNodeWithText("Edit Solid Color Fill").assertExists()
        compose.onNodeWithText("Apply").performClick()

        assertEquals(layerId, appliedLayer)
        assertEquals(FillKind.SOLID, appliedFill?.kind)
    }
}
