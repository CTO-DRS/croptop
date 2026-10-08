package app.croptop.core.designsystem.component

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.ExperimentalComposeUiApi
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
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.theme.AeDensity
import app.croptop.core.designsystem.theme.CropTopTheme
import app.croptop.core.model.RgbaColor
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AeColorWellAccessibilityTest {
    @get:Rule
    val compose = createComposeRule()

    private fun assertSize(expected: Int) {
        val node = compose.onNodeWithContentDescription("Color #${RgbaColor.Black.hex}").fetchSemanticsNode()
        val pixels = with(node.layoutInfo.density) { expected.dp.roundToPx() }
        assertEquals(pixels, node.size.width)
        assertEquals(pixels, node.size.height)
    }

    @Test
    fun smallTouchActionUsesControlMinimumAndButtonRole() {
        compose.setContent {
            CropTopTheme(density = AeDensity.Touch) { AeColorWell(RgbaColor.Black, "Color", size = 20, onClick = {}) }
        }
        assertSize(40)
        compose.onNodeWithContentDescription("Color #${RgbaColor.Black.hex}")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
    }

    @Test
    fun pointerActionUsesCompactControlMinimum() {
        compose.setContent {
            CropTopTheme(density = AeDensity.Pointer) { AeColorWell(RgbaColor.Black, "Color", size = 20, onClick = {}) }
        }
        assertSize(28)
    }

    @Test
    fun readOnlySwatchKeepsRequestedSizeAndHasNoAction() {
        compose.setContent {
            CropTopTheme(density = AeDensity.Touch) { AeColorWell(RgbaColor.Black, "Color", size = 20) }
        }
        assertSize(20)
        compose.onNodeWithContentDescription("Color #${RgbaColor.Black.hex}").assertHasNoClickAction()
    }

    @Test
    fun largerRequestedActionRemainsLarger() {
        compose.setContent {
            CropTopTheme(density = AeDensity.Touch) { AeColorWell(RgbaColor.Black, "Color", size = 64, onClick = {}) }
        }
        assertSize(64)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun keyboardActivatesTheActionOnce() {
        var clicks = 0
        compose.setContent {
            CropTopTheme(density = AeDensity.Touch) {
                val input = LocalInputModeManager.current
                LaunchedEffect(Unit) { input.requestInputMode(InputMode.Keyboard) }
                AeColorWell(RgbaColor.Black, "Color", size = 20, onClick = { clicks++ })
            }
        }
        val well = compose.onNodeWithContentDescription("Color #${RgbaColor.Black.hex}")
        well.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        well.performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle { assertEquals(1, clicks) }
    }
}
