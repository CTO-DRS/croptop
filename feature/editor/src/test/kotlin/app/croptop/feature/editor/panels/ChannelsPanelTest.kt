package app.croptop.feature.editor.panels

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.croptop.core.designsystem.theme.AeDensity
import app.croptop.core.designsystem.theme.CropTopTheme
import app.croptop.core.engine.api.SelectionChannel
import app.croptop.core.model.DocRect
import app.croptop.core.model.RgbaColor
import app.croptop.core.model.SelectionOp
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
class ChannelsPanelTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun saveRequiresAnActiveSelectionAndEmptyStateIsExplained() {
        var saves = 0
        compose.setContent {
            CropTopTheme(density = AeDensity.Pointer) {
                ChannelsPanelContent(emptyList(), hasSelection = false, onSave = {
                    saves++
                    true
                }, onLoad = { _, _ -> true }, onDelete = { true })
            }
        }

        compose.onNodeWithText("Save Selection").assertIsNotEnabled().performClick()
        compose.onNodeWithText("No saved selections").assertExists()
        compose.runOnIdle { assertEquals(0, saves) }
    }

    @Test
    fun channelCanLoadWithChosenOperationAndDelete() {
        val channel = SelectionChannel("Alpha 1", DocRect(2, 3, 12, 18))
        var loaded: Pair<String?, SelectionOp>? = null
        var deleted: String? = null
        compose.setContent {
            CropTopTheme(density = AeDensity.Pointer) {
                ChannelsPanelContent(
                    listOf(channel),
                    hasSelection = true,
                    onSave = { true },
                    onLoad = { name, op ->
                        loaded = name to op
                        true
                    },
                    onDelete = {
                        deleted = it
                        true
                    },
                )
            }
        }

        compose.onNodeWithText("10 × 15 px · (2, 3)").assertExists()
        compose.onNodeWithContentDescription("Selection load operation: Replace").performClick()
        compose.onNodeWithText("Add").performClick()
        compose.onNodeWithText("Load").performClick()
        compose.onNodeWithContentDescription("Delete Alpha 1").performClick()

        compose.runOnIdle {
            assertEquals("Alpha 1" to SelectionOp.ADD, loaded)
            assertEquals("Alpha 1", deleted)
        }
    }

    @Test
    fun emptyChannelsCannotBeLoaded() {
        compose.setContent {
            CropTopTheme(density = AeDensity.Pointer) {
                ChannelsPanelContent(listOf(SelectionChannel("Alpha 1", null)), true, { true }, { _, _ -> true }, { true })
            }
        }

        compose.onNodeWithText("Empty channel").assertExists()
        compose.onNodeWithText("Load").assertIsNotEnabled()
    }

    @Test
    fun quickMaskColorWellOpensPickerWithColorAndOpacityControls() {
        val color = RgbaColor(0.2f, 0.4f, 0.8f, 0.35f)
        compose.setContent {
            CropTopTheme(density = AeDensity.Pointer) {
                ChannelsPanelContent(
                    emptyList(),
                    hasSelection = false,
                    onSave = { true },
                    onLoad = { _, _ -> true },
                    onDelete = { true },
                    quickMaskOverlayColor = color,
                )
            }
        }

        compose.onNodeWithContentDescription("Quick Mask overlay color #${color.hex}").performClick()
        compose.onNodeWithText("Quick Mask Overlay Color").assertExists()
        compose.onNodeWithContentDescription("Saturation and brightness").assertExists()
        compose.onNodeWithText("Opacity").assertExists()
        compose.onNodeWithText("Done").performClick()
    }
}
