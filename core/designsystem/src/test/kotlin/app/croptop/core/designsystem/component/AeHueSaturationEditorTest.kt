package app.croptop.core.designsystem.component

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import app.croptop.core.designsystem.theme.CropTopTheme
import app.croptop.core.model.HueChannel
import app.croptop.core.model.HueSaturation
import app.croptop.core.model.HueShift
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w800dp-h1200dp-mdpi")
class AeHueSaturationEditorTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `hue edits stay with selected channel and colorize hides range picker`() {
        val initial = HueSaturation(
            master = HueShift(hue = 12f),
            channels = mapOf(
                HueChannel.REDS to HueShift(hue = 25f),
                HueChannel.GREENS to HueShift(hue = -10f),
            ),
        )
        val value = mutableStateOf(initial)
        compose.setContent {
            CropTopTheme {
                AeHueSaturationEditor(value.value, { value.value = it }, Modifier)
            }
        }

        compose.onNodeWithText("Master").performClick()
        compose.onNodeWithText("Reds").performClick()
        compose.onNodeWithText("Hue").performSemanticsAction(SemanticsActions.SetProgress) { it(28f) }
        compose.runOnIdle {
            assertEquals(12f, value.value.master.hue)
            assertEquals(28f, value.value.shift(HueChannel.REDS).hue)
        }

        compose.onNodeWithText("Reds").performClick()
        compose.onNodeWithText("Greens").performClick()
        compose.onNodeWithText("Hue").performSemanticsAction(SemanticsActions.SetProgress) { it(-6f) }
        compose.runOnIdle {
            assertEquals(28f, value.value.shift(HueChannel.REDS).hue)
            assertEquals(-6f, value.value.shift(HueChannel.GREENS).hue)
        }

        compose.onNodeWithText("Colorize").performClick()
        compose.onNodeWithContentDescription("Hue range").assertDoesNotExist()
        compose.runOnIdle { assertEquals(true, value.value.colorize) }
    }
}
