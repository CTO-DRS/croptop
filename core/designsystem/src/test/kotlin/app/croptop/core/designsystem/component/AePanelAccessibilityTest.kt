package app.croptop.core.designsystem.component

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.theme.AeDensity
import app.croptop.core.designsystem.theme.CropTopTheme
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AePanelAccessibilityTest {
    @get:Rule
    val compose = createComposeRule()

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `keyboard toggle announces panel state and action`() {
        val expanded = mutableStateOf(true)
        var toggles = 0
        compose.setContent {
            CropTopTheme(density = AeDensity.Touch) {
                val inputModes = LocalInputModeManager.current
                LaunchedEffect(Unit) { inputModes.requestInputMode(InputMode.Keyboard) }
                AePanel(
                    "Histogram",
                    Modifier.width(300.dp),
                    expanded = expanded.value,
                    onExpandedChange = {
                        expanded.value = it
                        toggles++
                    },
                ) { AeText("Histogram contents") }
            }
        }
        val header = compose.onNodeWithText("HISTOGRAM")
        header.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        header.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
        assertEquals("Collapse Histogram", header.fetchSemanticsNode().config[SemanticsActions.OnClick].label)
        val node = header.fetchSemanticsNode()
        val minimum = with(node.layoutInfo.density) { 40.dp.toPx() }
        assertTrue(node.size.width >= minimum && node.size.height >= minimum)

        header.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        header.performKeyInput { pressKey(Key.Enter) }
        header.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
        assertEquals("Expand Histogram", header.fetchSemanticsNode().config[SemanticsActions.OnClick].label)
        compose.onNodeWithText("Histogram contents").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, toggles) }

        header.performKeyInput { pressKey(Key.Enter) }
        header.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
        compose.onNodeWithText("Histogram contents").assertExists()
        compose.runOnIdle { assertEquals(2, toggles) }
    }

    @Test
    fun `fixed panel header does not announce a toggle action`() {
        compose.setContent {
            CropTopTheme { AePanel("Info") { AeText("Info contents") } }
        }
        compose.onNodeWithText("INFO").assertHasNoClickAction()
        compose.onNodeWithText("Info contents").assertExists()
    }
}
