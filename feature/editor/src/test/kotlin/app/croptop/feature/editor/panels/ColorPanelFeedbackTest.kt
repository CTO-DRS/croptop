package app.croptop.feature.editor.panels

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.croptop.core.designsystem.theme.AeDensity
import app.croptop.core.designsystem.theme.CropTopTheme
import app.croptop.core.input.ActionDispatcher
import app.croptop.core.input.LocalActionDispatcher
import app.croptop.core.input.ShortcutManager
import app.croptop.core.model.RgbaColor
import app.croptop.feature.editor.session.ColorState
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
class ColorPanelFeedbackTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun swapAndDefaultButtonsPublishOneContextualCompletionEach() {
        val completions = mutableListOf<String>()
        var swaps = 0
        var resets = 0
        compose.setContent {
            CompositionLocalProvider(LocalActionDispatcher provides ActionDispatcher(ShortcutManager())) {
                CropTopTheme(density = AeDensity.Pointer) {
                    ColorPanelContent(
                        colors = ColorState(foreground = RgbaColor.Black, background = RgbaColor.White),
                        onForeground = {},
                        onBackground = {},
                        onCommit = {},
                        onSwap = { swaps++ },
                        onDefaults = { resets++ },
                        swatches = SwatchesUi(DefaultSwatchList),
                        onActionComplete = completions::add,
                    )
                }
            }
        }

        compose.onNodeWithContentDescription("Swap colors").performClick()
        compose.onNodeWithContentDescription("Default colors").performClick()

        compose.runOnIdle {
            assertEquals(1, swaps)
            assertEquals(1, resets)
            assertEquals(listOf("Swap foreground and background colors", "Restore default colors"), completions)
        }
    }

    @Test
    fun harmonySelectionReportsItsSetting() {
        val completions = mutableListOf<String>()
        compose.setContent {
            CompositionLocalProvider(LocalActionDispatcher provides ActionDispatcher(ShortcutManager())) {
                CropTopTheme(density = AeDensity.Pointer) {
                    ColorPanelContent(
                        colors = ColorState(foreground = RgbaColor.Black, background = RgbaColor.White),
                        onForeground = {},
                        onBackground = {},
                        onCommit = {},
                        onSwap = {},
                        onDefaults = {},
                        swatches = SwatchesUi(DefaultSwatchList),
                        onActionComplete = completions::add,
                    )
                }
            }
        }

        compose.onNodeWithText("Complementary").performClick()
        compose.onNodeWithText("Analogous").performClick()

        compose.runOnIdle {
            assertEquals(listOf("Set color harmony to analogous"), completions)
        }
    }
}
