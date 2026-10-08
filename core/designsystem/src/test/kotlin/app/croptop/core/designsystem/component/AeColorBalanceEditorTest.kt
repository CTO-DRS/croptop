package app.croptop.core.designsystem.component

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import app.croptop.core.designsystem.theme.CropTopTheme
import app.croptop.core.model.ColorBalance
import app.croptop.core.model.ColorBalanceShift
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
class AeColorBalanceEditorTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `tone selection edits only that tone and preserves luminosity toggle`() {
        val initial = ColorBalance(
            shadows = ColorBalanceShift(cyanRed = -10f),
            midtones = ColorBalanceShift(cyanRed = 20f),
            highlights = ColorBalanceShift(cyanRed = 30f),
        )
        val value = mutableStateOf(initial)
        compose.setContent {
            CropTopTheme {
                AeColorBalanceEditor(value.value, { value.value = it }, Modifier)
            }
        }

        compose.onNodeWithText("Highlights").performTouchInput {
            down(Offset(width / 2f, height / 2f))
            up()
        }
        compose.onNodeWithText("Cyan – Red").performSemanticsAction(SemanticsActions.SetProgress) { it(34f) }
        compose.runOnIdle {
            assertEquals(-10f, value.value.shadows.cyanRed)
            assertEquals(20f, value.value.midtones.cyanRed)
            assertEquals(34f, value.value.highlights.cyanRed)
            assertEquals(true, value.value.preserveLuminosity)
        }

        compose.onNodeWithText("Preserve Luminosity").performTouchInput {
            down(Offset(width / 2f, height / 2f))
            up()
        }
        compose.runOnIdle { assertEquals(false, value.value.preserveLuminosity) }
    }
}
