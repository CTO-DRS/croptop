package app.croptop.core.designsystem.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.theme.AeDensity
import app.croptop.core.designsystem.theme.CropTopTheme
import app.croptop.core.designsystem.theme.ThemePreference
import com.github.takahirom.roborazzi.captureRoboImage
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w600dp-h400dp-mdpi")
class AeBarSliderTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(value: Float, range: ClosedFloatingPointRange<Float> = 0f..100f, default: Float = 50f, onFinished: () -> Unit = {}) = mutableFloatStateOf(value).also { state ->
        compose.setContent {
            CropTopTheme(theme = ThemePreference.Dark, density = AeDensity.Pointer) {
                AeBarSlider("Size", state.floatValue, { state.floatValue = it }, Modifier.width(200.dp), range = range, defaultValue = default, unit = "px", onValueChangeFinished = onFinished, contentDescription = "Brush size")
            }
        }
    }

    @Test
    fun `accessible reset applies the default and finishes the change`() {
        var finished = 0
        val value = show(80f, default = 35f, onFinished = { finished++ })
        val node = compose.onNodeWithContentDescription("Brush size").fetchSemanticsNode()
        val action = node.config[SemanticsActions.CustomActions].single()
        assertEquals("Reset to 35 px", action.label)
        compose.runOnIdle { assertTrue(action.action()) }
        assertEquals(35f, value.floatValue)
        assertEquals(1, finished)
    }

    @Test
    fun `dragging along the bar sweeps the range and publishes one final value`() {
        var finished = 0
        val value = show(10f, onFinished = { finished++ })
        compose.onNodeWithContentDescription("Brush size").performTouchInput { swipeRight(startX = left + 2f, endX = left + width / 2f) }
        compose.runOnIdle {
            assertTrue(value.floatValue in 40f..60f, "half a bar is about half the range: ${value.floatValue}")
            assertEquals(1, finished)
        }
    }

    @Test
    fun `a curved bar gives the small end more room`() {
        val value = mutableFloatStateOf(1f)
        compose.setContent {
            CropTopTheme(theme = ThemePreference.Dark, density = AeDensity.Pointer) {
                AeBarSlider("Size", value.floatValue, { value.floatValue = it }, Modifier.width(200.dp), range = 1f..5000f, curve = 3f, unit = "px", contentDescription = "Size")
            }
        }
        compose.onNodeWithContentDescription("Size").performTouchInput { swipeRight(startX = left + 2f, endX = left + width / 2f) }
        // Half the bar is about (1/2)^3 of the range with a cubic curve; a linear bar would give about 2500.
        compose.runOnIdle { assertTrue(value.floatValue in 100f..900f, "half the bar: ${value.floatValue}") }
    }

    @Test
    fun `a curved bipolar bar keeps its middle fine and still reaches both ends`() {
        val value = mutableFloatStateOf(0f)
        compose.setContent {
            CropTopTheme(theme = ThemePreference.Dark, density = AeDensity.Pointer) {
                AeBarSlider("Indent", value.floatValue, { value.floatValue = it }, Modifier.width(200.dp), range = -1296f..1296f, curve = 3f, unit = "pt", contentDescription = "Indent")
            }
        }
        // A short drag right of centre moves a few points, not hundreds.
        compose.onNodeWithContentDescription("Indent").performTouchInput { swipeRight(startX = left + width / 2f, endX = left + width / 2f + 30f) }
        compose.runOnIdle { assertTrue(value.floatValue in 0.5f..40f, "30 px right of centre: ${value.floatValue}") }
        compose.onNodeWithContentDescription("Indent").performSemanticsAction(SemanticsActions.SetProgress) { it(-1296f) }
        compose.runOnIdle { assertEquals(-1296f, value.floatValue) }
    }

    @Test
    fun `a tap opens the editor and a typed number or sum commits`() {
        val value = show(10f)
        compose.onNodeWithContentDescription("Brush size").performClick()
        compose.mainClock.advanceTimeBy(400) // a tap waits out the double-tap window
        compose.waitForIdle()
        compose.onNodeWithText("10").performTextClearance()
        compose.onNodeWithText("").performTextInput("12*3")
        compose.onNodeWithText("12*3").performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle { assertEquals(36f, value.floatValue) }
        compose.onNodeWithContentDescription("Brush size").assertTextContains("36 px", substring = true)
    }

    @Test
    fun `typing past the range is clamped and escape keeps the old value`() {
        val value = show(40f)
        compose.onNodeWithContentDescription("Brush size").performClick()
        compose.mainClock.advanceTimeBy(400) // a tap waits out the double-tap window
        compose.waitForIdle()
        compose.onNodeWithText("40").performTextClearance()
        compose.onNodeWithText("").performTextInput("900")
        compose.onNodeWithText("900").performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle { assertEquals(100f, value.floatValue) }
        compose.onNodeWithContentDescription("Brush size").performClick()
        compose.mainClock.advanceTimeBy(400) // a tap waits out the double-tap window
        compose.waitForIdle()
        compose.onNodeWithText("100").performTextClearance()
        compose.onNodeWithText("").performTextInput("7")
        compose.onNodeWithText("7").performKeyInput { pressKey(Key.Escape) }
        compose.runOnIdle { assertEquals(100f, value.floatValue) }
    }

    @Test
    fun `double click resets, arrow keys step and screen readers set a value`() {
        val value = show(80f, default = 25f)
        compose.onNodeWithContentDescription("Brush size").performMouseInput { doubleClick() }
        compose.runOnIdle { assertEquals(25f, value.floatValue) }
        compose.onNodeWithContentDescription("Brush size").requestFocus()
        compose.onNodeWithContentDescription("Brush size").performKeyInput { pressKey(Key.DirectionRight) }
        compose.runOnIdle { assertEquals(26f, value.floatValue) }
        compose.onNodeWithContentDescription("Brush size").performSemanticsAction(SemanticsActions.SetProgress) { it(61f) }
        compose.runOnIdle { assertEquals(61f, value.floatValue) }
    }

    @Test
    fun `looks like a bar with the label and the value on it`() {
        val a = mutableFloatStateOf(40f)
        val b = mutableFloatStateOf(-30f)
        compose.setContent {
            CropTopTheme(theme = ThemePreference.Dark, density = AeDensity.Pointer) {
                Column(Modifier.padding(16.dp)) {
                    AeBarSlider("Size", a.floatValue, { a.floatValue = it }, Modifier.width(260.dp), unit = "px")
                    AeBarSlider("Exposure", b.floatValue, { b.floatValue = it }, Modifier.width(260.dp).padding(top = 8.dp), range = -100f..100f, defaultValue = 0f)
                    AeBarSlider("Locked", 70f, {}, Modifier.width(260.dp).padding(top = 8.dp), enabled = false, unit = "%")
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/snapshots/ae_bar_slider.png")
    }
}
