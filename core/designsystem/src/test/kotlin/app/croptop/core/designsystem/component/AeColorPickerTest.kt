package app.croptop.core.designsystem.component

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import app.croptop.core.designsystem.theme.CropTopTheme
import app.croptop.core.model.RgbaColor
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
@Config(sdk = [35], qualifiers = "w800dp-h1200dp-mdpi")
class AeColorPickerTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `pasting shorthand hex updates the selected color`() {
        val color = mutableStateOf(RgbaColor.Black)
        var editCompletions = 0
        compose.setContent {
            CropTopTheme {
                AeColorPicker(color.value, { color.value = it }, Modifier, onEditComplete = { editCompletions++ })
            }
        }

        compose.onNodeWithContentDescription("Hex color").performTextReplacement("#3A7")

        compose.runOnIdle {
            assertEquals(0xFF33AA77.toInt(), color.value.toArgb())
            assertEquals(1, editCompletions)
        }
    }

    @Test
    fun `wheel mode selects hue and saturation`() {
        val color = mutableStateOf(RgbaColor.White)
        var editCompletions = 0
        compose.setContent {
            CropTopTheme {
                AeColorPicker(color.value, { color.value = it }, Modifier, onEditComplete = { editCompletions++ })
            }
        }

        compose.onNodeWithText("Wheel").performClick()
        compose.onNodeWithContentDescription("Hue and saturation wheel").performTouchInput {
            down(Offset(width / 2f + height / 2f - 8f, height / 2f))
            up()
        }

        compose.runOnIdle {
            assertTrue(color.value.r > 0.95f)
            assertTrue(color.value.g < 0.1f)
            assertTrue(color.value.b < 0.1f)
            assertEquals(1, editCompletions)
        }
        compose.onNodeWithContentDescription("Brightness").assertExists()
    }

    @Test
    fun `Lab mode exposes lightness and opponent color fields`() {
        compose.setContent {
            CropTopTheme {
                AeColorPicker(RgbaColor(0.2f, 0.4f, 0.6f), {}, Modifier)
            }
        }

        compose.onNodeWithText("LAB").performClick()

        compose.onNodeWithContentDescription("L").assertExists()
        compose.onNodeWithContentDescription("a").assertExists()
        compose.onNodeWithContentDescription("b").assertExists()
    }

    @Test
    fun `numeric channel commit reports one completed color edit`() {
        val color = mutableStateOf(RgbaColor(1f, 0f, 0f))
        var editCompletions = 0
        compose.setContent {
            CropTopTheme {
                AeColorPicker(color.value, { color.value = it }, Modifier, onEditComplete = { editCompletions++ })
            }
        }

        compose.onNodeWithText("HSB").performClick()
        compose.onNodeWithContentDescription("H").performTextReplacement("120")
        compose.onNodeWithContentDescription("H").performImeAction()

        compose.runOnIdle {
            assertTrue(color.value.g > 0.95f)
            assertTrue(color.value.r < 0.1f)
            assertEquals(1, editCompletions)
        }
    }
}
