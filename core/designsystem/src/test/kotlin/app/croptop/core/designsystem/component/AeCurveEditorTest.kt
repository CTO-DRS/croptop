package app.croptop.core.designsystem.component

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import app.croptop.core.designsystem.theme.CropTopTheme
import app.croptop.core.model.Curve
import app.croptop.core.model.CurvePoint
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
class AeCurveEditorTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `keyboard selects and edits curve points with accessible values`() {
        val curveState = mutableStateOf(Curve.Identity.withPoint(CurvePoint(0.5f, 0.5f)).first)
        compose.setContent {
            CropTopTheme {
                AeCurveEditor(curveState.value, { curveState.value = it }, Modifier)
            }
        }

        val graph = compose.onNodeWithContentDescription("Curve editor graph with 3 points", substring = true)
        graph.performSemanticsAction(SemanticsActions.RequestFocus) { requestFocus -> requestFocus() }
        graph.performKeyInput {
            pressKey(Key.Tab)
            pressKey(Key.Tab)
        }
        compose.onNodeWithContentDescription("Curve editor graph with 3 points; selected point 2, input 128, output 128").assertExists()

        graph.performKeyInput { pressKey(Key.DirectionUp) }
        compose.runOnIdle { assertTrue(curveState.value.points[1].y > 0.5f) }
        graph.performKeyInput { pressKey(Key.DirectionRight) }
        compose.runOnIdle {
            val point = curveState.value.points[1]
            assertTrue(point.x > 0.5f)
        }

        graph.performKeyInput { pressKey(Key.Delete) }
        compose.runOnIdle { assertEquals(2, curveState.value.points.size) }
        compose.onNodeWithContentDescription("Curve editor graph with 2 points").assertExists()
    }

    @Test
    fun `freehand mode converts a gesture into an ordered curve`() {
        val curveState = mutableStateOf(Curve.Identity)
        compose.setContent {
            CropTopTheme {
                AeCurveEditor(curveState.value, { curveState.value = it }, Modifier)
            }
        }

        compose.onNodeWithText("Freehand").performClick()
        compose.onNodeWithContentDescription("freehand mode", substring = true).performTouchInput {
            down(Offset(width * 0.25f, height * 0.2f))
            moveTo(Offset(width * 0.75f, height * 0.3f))
            up()
        }

        compose.runOnIdle {
            assertEquals(65, curveState.value.points.size)
            assertTrue(curveState.value.points.zipWithNext().all { (left, right) -> right.x > left.x })
            assertTrue(curveState.value.evaluate(0.5f) > 0.6f)
        }
    }

    @Test
    fun `channel tabs retain independent curves`() {
        val redCurve = Curve.Identity.withPoint(CurvePoint(0.5f, 0.8f)).first
        val blueCurve = Curve.Identity.withPoint(CurvePoint(0.5f, 0.5f)).first
        val channels = mutableStateOf(ChannelCurves(red = redCurve, blue = blueCurve))
        compose.setContent {
            CropTopTheme {
                AeCurveEditor(channels.value, { channels.value = it }, Modifier)
            }
        }

        compose.onNodeWithText("Red").performClick()
        compose.onNodeWithContentDescription("Curve editor graph with 3 points").assertExists()
        compose.onNodeWithText("Blue").performClick()
        val graph = compose.onNodeWithContentDescription("Curve editor graph with 3 points")
        graph.performSemanticsAction(SemanticsActions.RequestFocus) { requestFocus -> requestFocus() }
        graph.performKeyInput {
            pressKey(Key.Tab)
            pressKey(Key.Tab)
        }
        compose.onNodeWithContentDescription("Curve editor graph with 3 points", substring = true).performKeyInput {
            pressKey(Key.DirectionUp)
        }

        compose.runOnIdle {
            assertEquals(redCurve, channels.value[CurveChannel.RED])
            assertTrue(channels.value[CurveChannel.BLUE].points[1].y > 0.5f)
        }
    }
}
