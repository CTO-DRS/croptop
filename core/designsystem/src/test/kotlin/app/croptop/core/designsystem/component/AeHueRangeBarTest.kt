package app.croptop.core.designsystem.component

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.theme.CropTopTheme
import app.croptop.core.model.HueRange
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
class AeHueRangeBarTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `keyboard and accessibility actions rotate a wrapping range`() {
        val original = HueRange(315f, 345f, 15f, 45f)
        val range = mutableStateOf(original)
        compose.setContent {
            CropTopTheme {
                AeHueRangeBar(range.value, hueShift = 0f, onRangeChange = { range.value = it }, modifier = Modifier.width(360.dp))
            }
        }

        val node = compose.onNodeWithContentDescription("Hue range")
        assertEquals(
            "Selected 345° to 15°; soft edges 315° and 45°",
            node.fetchSemanticsNode().config[SemanticsProperties.StateDescription],
        )
        node.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        node.performKeyInput { pressKey(Key.DirectionRight) }
        compose.runOnIdle { assertEquals(original.rotate(1f), range.value) }
    }

    @Test
    fun `touch drag moves the inner handle across zero without snapping to the outer edge`() {
        val original = HueRange(315f, 345f, 15f, 45f)
        val range = mutableStateOf(original)
        compose.setContent {
            CropTopTheme {
                AeHueRangeBar(range.value, hueShift = 0f, onRangeChange = { range.value = it }, modifier = Modifier.width(360.dp))
            }
        }

        compose.onNodeWithContentDescription("Hue range").performTouchInput {
            down(Offset(345f, height / 2f))
            moveTo(Offset(5f, height / 2f))
            up()
        }

        compose.runOnIdle {
            assertEquals(5f, range.value.innerStart)
            assertEquals(original.innerEnd, range.value.innerEnd)
            assertEquals(original.outerStart, range.value.outerStart)
            assertEquals(original.outerEnd, range.value.outerEnd)
        }
    }
}
