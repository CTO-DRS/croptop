package app.croptop.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.theme.Ae
import app.croptop.core.designsystem.theme.AeDensity
import app.croptop.core.designsystem.theme.CropTopTheme
import app.croptop.core.model.ColorGradient
import app.croptop.core.model.GradientStop
import app.croptop.core.model.RgbaColor
import com.github.takahirom.roborazzi.captureRoboImage
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
class AeGradientEditorTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `add stop then keyboard edit its location and delete it`() {
        val gradient = mutableStateOf(ColorGradient.BlackToWhite)
        var completions = 0
        compose.setContent {
            CropTopTheme {
                AeGradientEditor(
                    gradient.value,
                    { gradient.value = it },
                    Modifier.width(360.dp),
                    onValueChangeFinished = { completions++ },
                )
            }
        }

        compose.onNodeWithContentDescription("Color stops. Click to add a stop.").performTouchInput {
            down(Offset(width / 2f, height / 2f))
            up()
        }
        compose.runOnIdle {
            assertEquals(3, gradient.value.stops.size)
            assertEquals(0.5f, gradient.value.stops.last().position)
            assertEquals(1, completions)
        }

        compose.onNodeWithContentDescription("Stop location").performClick().performKeyInput {
            pressKey(Key.DirectionUp)
        }
        compose.runOnIdle {
            assertEquals(0.51f, gradient.value.stops.last().position)
            assertEquals(2, completions)
        }

        compose.onNodeWithContentDescription("Delete stop").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle {
            assertEquals(2, gradient.value.stops.size)
            assertEquals(3, completions)
        }
        compose.onNodeWithText("Location").assertExists()
    }

    @Test
    fun `gradient stop strip exposes the selected location`() {
        val gradient = mutableStateOf(
            ColorGradient(
                listOf(
                    GradientStop(0f, RgbaColor.Black),
                    GradientStop(0.35f, RgbaColor(1f, 0f, 0f)),
                    GradientStop(1f, RgbaColor.White),
                ),
            ),
        )
        compose.setContent {
            CropTopTheme {
                AeGradientEditor(gradient.value, { gradient.value = it }, Modifier.width(360.dp))
            }
        }

        compose.onNodeWithContentDescription("Color stops. Click to add a stop.").performTouchInput {
            down(Offset(width * 0.35f, height / 2f))
            up()
        }
        compose.onNodeWithContentDescription("Stop location").assertExists()
        compose.runOnIdle { assertEquals(3, gradient.value.stops.size) }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `keyboard selects and edits a different stop`() {
        val gradient = mutableStateOf(ColorGradient.BlackToWhite)
        compose.setContent {
            CropTopTheme {
                val inputModes = LocalInputModeManager.current
                LaunchedEffect(Unit) { inputModes.requestInputMode(InputMode.Keyboard) }
                AeGradientEditor(gradient.value, { gradient.value = it }, Modifier.width(360.dp))
            }
        }
        val second = compose.onNodeWithContentDescription("Gradient stop 2, location 100 percent")
        second.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        second.performKeyInput { pressKey(Key.Enter) }
        second.assertIsSelected()
        compose.onNodeWithContentDescription("Stop location").performClick().performKeyInput {
            pressKey(Key.DirectionDown)
        }
        compose.runOnIdle {
            assertEquals(0f, gradient.value.stops.first().position)
            assertEquals(0.99f, gradient.value.stops.last().position)
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `tab traverses stop actions and color location delete controls`() {
        val gradient = ColorGradient(
            ColorGradient.BlackToWhite.stops + GradientStop(0.5f, RgbaColor.White),
        )
        compose.setContent {
            CropTopTheme(density = AeDensity.Touch) {
                val inputModes = LocalInputModeManager.current
                LaunchedEffect(Unit) { inputModes.requestInputMode(InputMode.Keyboard) }
                AeGradientEditor(gradient, {}, Modifier.width(360.dp))
            }
        }
        val first = compose.onNodeWithContentDescription("Gradient stop 1, location 0 percent")
        first.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        listOf(
            compose.onNodeWithContentDescription("Gradient stop 2, location 100 percent"),
            compose.onNodeWithContentDescription("Gradient stop 3, location 50 percent"),
            compose.onNodeWithText("Add stop"),
            compose.onNodeWithContentDescription("Stop color #${RgbaColor.Black.hex}"),
            compose.onNodeWithContentDescription("Stop location"),
            compose.onNodeWithContentDescription("Delete stop"),
        ).forEach { next ->
            compose.onRoot().performKeyInput { pressKey(Key.Tab) }
            next.assertIsFocused()
        }
    }

    @Test
    fun `accessible add stop splits the widest interval and selects it`() {
        val gradient = mutableStateOf(
            ColorGradient(listOf(GradientStop(1f, RgbaColor.White), GradientStop(0.25f, RgbaColor.Black))),
        )
        compose.setContent {
            CropTopTheme {
                AeGradientEditor(gradient.value, { gradient.value = it }, Modifier.width(360.dp))
            }
        }
        compose.onNodeWithText("Add stop").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle {
            assertEquals(3, gradient.value.stops.size)
            assertEquals(0.625f, gradient.value.stops.last().position)
            assertEquals(RgbaColor(0.5f, 0.5f, 0.5f), gradient.value.stops.last().color)
        }
        compose.onNodeWithContentDescription("Gradient stop 3, location 63 percent").assertIsSelected()
        compose.onNodeWithContentDescription("Delete stop").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { assertEquals(2, gradient.value.stops.size) }
        compose.onNodeWithContentDescription("Delete stop").assertIsNotEnabled()
    }

    @Test
    fun `replacing a gradient keeps a valid stop accessible for editing`() {
        val gradient = mutableStateOf(ColorGradient.BlackToWhite)
        compose.setContent {
            CropTopTheme {
                AeGradientEditor(gradient.value, { gradient.value = it }, Modifier.width(360.dp))
            }
        }
        compose.onNodeWithText("Add stop").performClick()
        compose.onNodeWithContentDescription("Gradient stop 3, location 50 percent").assertIsSelected()
        compose.runOnIdle { gradient.value = ColorGradient.BlackToWhite }
        compose.onNodeWithContentDescription("Gradient stop 2, location 100 percent").assertIsSelected()
        compose.onNodeWithContentDescription("Stop location").performClick().performKeyInput {
            pressKey(Key.DirectionDown)
        }
        compose.runOnIdle {
            assertEquals(0f, gradient.value.stops.first().position)
            assertEquals(0.99f, gradient.value.stops.last().position)
        }
    }

    @Test
    fun `stop controls remain inside narrow editor with doubled font size`() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                CropTopTheme(density = AeDensity.Touch) {
                    AeGradientEditor(ColorGradient.BlackToWhite, {}, Modifier.width(360.dp).background(Ae.colors.surface1).testTag("gradient"))
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/snapshots/gradient_editor_touch_360_font200.png")
        val bounds = compose.onNodeWithTag("gradient").fetchSemanticsNode().boundsInRoot
        listOf("Stop color #${RgbaColor.Black.hex}", "Stop location", "Delete stop").forEach { description ->
            val control = compose.onNodeWithContentDescription(description).fetchSemanticsNode().boundsInRoot
            assertTrue(control.left >= bounds.left && control.right <= bounds.right, "Clipped $description: $control")
        }
    }

    @Test
    fun `stop actions meet touch target dimensions`() {
        compose.setContent {
            CropTopTheme(density = AeDensity.Touch) {
                AeGradientEditor(ColorGradient.BlackToWhite, {}, Modifier.width(360.dp).background(Ae.colors.surface1))
            }
        }
        compose.onRoot().captureRoboImage("src/test/snapshots/gradient_editor_touch_360.png")
        val actions = listOf(
            compose.onNodeWithContentDescription("Gradient stop 1, location 0 percent"),
            compose.onNodeWithContentDescription("Gradient stop 2, location 100 percent"),
            compose.onNodeWithText("Add stop"),
            compose.onNodeWithContentDescription("Stop color #${RgbaColor.Black.hex}"),
            compose.onNodeWithContentDescription("Stop location"),
            compose.onNodeWithContentDescription("Delete stop"),
        )
        actions.forEach { action ->
            val node = action.fetchSemanticsNode()
            val minimum = with(node.layoutInfo.density) { 40.dp.toPx() }
            assertTrue(node.size.width >= minimum && node.size.height >= minimum, "Undersized stop action: ${node.config}")
        }
        listOf(
            compose.onNodeWithContentDescription("Stop color #${RgbaColor.Black.hex}"),
            compose.onNodeWithContentDescription("Stop location"),
            compose.onNodeWithContentDescription("Delete stop"),
        ).forEach { action ->
            val node = action.fetchSemanticsNode()
            val minimum = with(node.layoutInfo.density) { 48.dp.toPx() }
            assertTrue(node.size.height >= minimum, "Undersized property action: ${node.config}")
        }
    }
}
