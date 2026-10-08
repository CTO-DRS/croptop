package app.croptop.core.designsystem

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.croptop.core.designsystem.component.AeButton
import app.croptop.core.designsystem.component.AeButtonStyle
import app.croptop.core.designsystem.component.AeDialog
import app.croptop.core.designsystem.component.AeGradientEditor
import app.croptop.core.designsystem.component.AePanel
import app.croptop.core.designsystem.component.AeText
import app.croptop.core.designsystem.theme.Ae
import app.croptop.core.designsystem.theme.AeDensity
import app.croptop.core.designsystem.theme.CropTopTheme
import app.croptop.core.model.ColorGradient
import app.croptop.core.model.RgbaColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android keyboard and semantics evidence; does not replace a manual TalkBack/Scanner pass. */
@RunWith(AndroidJUnit4::class)
class ControlAccessibilityDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun systemZeroAnimationScaleProvidesReducedMotionTokens() {
        var baseMotionMillis = -1
        compose.setContent {
            CropTopTheme { baseMotionMillis = Ae.motion.baseMillis }
        }
        compose.runOnIdle { assertEquals(0, baseMotionMillis) }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun keyboardAddsAndEditsGradientStop() {
        val gradient = mutableStateOf(ColorGradient.BlackToWhite)
        var completed = 0
        compose.setContent {
            CropTopTheme(density = AeDensity.Touch) {
                val input = LocalInputModeManager.current
                LaunchedEffect(Unit) { input.requestInputMode(InputMode.Keyboard) }
                AeGradientEditor(gradient.value, { gradient.value = it }, Modifier.width(360.dp)) { completed++ }
            }
        }
        val add = compose.onNodeWithText("Add stop")
        add.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        add.performKeyInput { pressKey(Key.Enter) }
        compose.onNodeWithContentDescription("Gradient stop 3, location 50 percent").assertIsSelected()
        compose.runOnIdle {
            assertEquals(3, gradient.value.stops.size)
            assertEquals(1, completed)
        }
        val location = compose.onNodeWithContentDescription("Stop location")
        location.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        location.performKeyInput { pressKey(Key.DirectionUp) }
        compose.runOnIdle {
            assertEquals(0.51f, gradient.value.stops.last().position, 0.0001f)
            assertEquals(2, completed)
        }
        val bounds = add.fetchSemanticsNode()
        val minimum = with(bounds.layoutInfo.density) { 40.dp.toPx() }
        assertTrue(bounds.size.width >= minimum && bounds.size.height >= minimum)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun keyboardTraversesGradientActionsAndPropertiesInBothDirections() {
        val gradient = mutableStateOf(
            ColorGradient.BlackToWhite.copy(
                stops = ColorGradient.BlackToWhite.stops + ColorGradient.BlackToWhite.stops.first().copy(position = 0.5f),
            ),
        )
        compose.setContent {
            CropTopTheme(density = AeDensity.Touch) {
                val input = LocalInputModeManager.current
                LaunchedEffect(Unit) { input.requestInputMode(InputMode.Keyboard) }
                AeGradientEditor(gradient.value, { gradient.value = it }, Modifier.width(360.dp))
            }
        }

        val first = compose.onNodeWithContentDescription("Gradient stop 1, location 0 percent")
        val second = compose.onNodeWithContentDescription("Gradient stop 2, location 100 percent")
        val third = compose.onNodeWithContentDescription("Gradient stop 3, location 50 percent")
        val add = compose.onNodeWithText("Add stop")
        val color = compose.onNodeWithContentDescription("Stop color #${RgbaColor.Black.hex}")
        val location = compose.onNodeWithContentDescription("Stop location")
        val delete = compose.onNodeWithContentDescription("Delete stop")

        first.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        var focused = first
        listOf(second, third, add, color, location, delete).forEach { next ->
            focused.performKeyInput { pressKey(Key.Tab) }
            next.assertIsFocused()
            focused = next
        }
        delete.performKeyInput {
            keyDown(Key.ShiftLeft)
            pressKey(Key.Tab)
            keyUp(Key.ShiftLeft)
        }
        location.assertIsFocused()
        location.performKeyInput {
            keyDown(Key.ShiftLeft)
            pressKey(Key.Tab)
            keyUp(Key.ShiftLeft)
        }
        color.assertIsFocused()
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun keyboardCollapsesAndExpandsPanelWithStateFeedback() {
        val expanded = mutableStateOf(true)
        compose.setContent {
            CropTopTheme(density = AeDensity.Touch) {
                val input = LocalInputModeManager.current
                LaunchedEffect(Unit) { input.requestInputMode(InputMode.Keyboard) }
                AePanel("Histogram", Modifier.width(360.dp), expanded.value, { expanded.value = it }) {
                    AeText("Panel content")
                }
            }
        }
        val header = compose.onNodeWithText("HISTOGRAM")
        header.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        header.performKeyInput { pressKey(Key.Enter) }
        header.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
        compose.onNodeWithText("Panel content").assertDoesNotExist()
        header.performKeyInput { pressKey(Key.Enter) }
        header.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
        compose.onNodeWithText("Panel content").assertExists()
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun keyboardTraversesPanelHeaderAndActions() {
        compose.setContent {
            CropTopTheme(density = AeDensity.Touch) {
                val input = LocalInputModeManager.current
                LaunchedEffect(Unit) { input.requestInputMode(InputMode.Keyboard) }
                AePanel("Histogram", Modifier.width(360.dp), expanded = true, onExpandedChange = {}) {
                    AeButton("Reset histogram", {})
                    AeButton("Refresh histogram", {})
                }
            }
        }

        val header = compose.onNodeWithText("HISTOGRAM")
        val reset = compose.onNodeWithText("Reset histogram")
        val refresh = compose.onNodeWithText("Refresh histogram")
        header.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        header.performKeyInput { pressKey(Key.Tab) }
        reset.assertIsFocused()
        reset.performKeyInput { pressKey(Key.Tab) }
        refresh.assertIsFocused()
        refresh.performKeyInput {
            keyDown(Key.ShiftLeft)
            pressKey(Key.Tab)
            keyUp(Key.ShiftLeft)
        }
        reset.assertIsFocused()
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun dialogActionsFollowKeyboardOrderAndHandleEnterEscape() {
        val shown = mutableStateOf(true)
        var confirms = 0
        var dismissals = 0
        compose.setContent {
            CropTopTheme(density = AeDensity.Touch) {
                val input = LocalInputModeManager.current
                LaunchedEffect(Unit) { input.requestInputMode(InputMode.Keyboard) }
                if (shown.value) {
                    AeDialog(
                        title = "Export image",
                        onDismiss = {
                            dismissals++
                            shown.value = false
                        },
                        onConfirm = { confirms++ },
                        actions = {
                            AeButton("Cancel", {
                                dismissals++
                                shown.value = false
                            })
                            AeButton("Export", { confirms++ }, style = AeButtonStyle.Primary)
                        },
                    )
                }
            }
        }

        val cancel = compose.onNodeWithText("Cancel")
        cancel.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        cancel.performKeyInput { pressKey(Key.Tab) }
        val export = compose.onNodeWithText("Export")
        export.assertIsFocused()
        export.performKeyInput {
            keyDown(Key.ShiftLeft)
            pressKey(Key.Tab)
            keyUp(Key.ShiftLeft)
        }
        cancel.assertIsFocused()
        export.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        export.performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle { assertEquals(1, confirms) }
        cancel.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        cancel.performKeyInput { pressKey(Key.Escape) }
        compose.runOnIdle {
            assertEquals(1, dismissals)
            assertEquals(false, shown.value)
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun gradientControlsPassAndroidAccessibilityFrameworkChecks() {
        val gradient = mutableStateOf(ColorGradient.BlackToWhite)
        compose.setContent {
            CropTopTheme(density = AeDensity.Touch) {
                AeGradientEditor(gradient.value, { gradient.value = it }, Modifier.width(360.dp))
            }
        }
        compose.enableAccessibilityChecks()
        compose.onNodeWithText("Add stop").performClick()
        compose.onNodeWithContentDescription("Gradient stop 3, location 50 percent").assertIsSelected()
        compose.runOnIdle { assertEquals(3, gradient.value.stops.size) }
    }
}
