package app.croptop.core.designsystem.component

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import app.croptop.core.designsystem.theme.CropTopTheme
import app.croptop.core.model.Levels
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
class AeLevelsEditorTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `auto applies histogram range and eyedroppers report their target`() {
        val levels = mutableStateOf(Levels(gamma = 1.8f, outBlack = 0.1f, outWhite = 0.9f))
        val picked = mutableStateOf<LevelsPickTarget?>(null)
        val histogram = FloatArray(256).also {
            it[20] = 1_000f
            it[230] = 1_000f
        }
        compose.setContent {
            CropTopTheme {
                AeLevelsEditor(
                    levels = levels.value,
                    onLevelsChange = { levels.value = it },
                    modifier = Modifier,
                    histogram = histogram,
                    onPickSample = { picked.value = it },
                )
            }
        }

        compose.onNodeWithText("Auto").performClick()
        compose.runOnIdle {
            assertEquals(20f / 255f, levels.value.inBlack)
            assertEquals(230f / 255f, levels.value.inWhite)
            assertEquals(1f, levels.value.gamma)
            assertEquals(0.1f, levels.value.outBlack)
            assertEquals(0.9f, levels.value.outWhite)
        }

        compose.onNodeWithText("Black").performClick()
        compose.runOnIdle { assertEquals(LevelsPickTarget.BLACK, picked.value) }
        compose.onNodeWithText("Gray").performClick()
        compose.runOnIdle { assertEquals(LevelsPickTarget.GRAY, picked.value) }
        compose.onNodeWithText("White").performClick()
        compose.runOnIdle { assertEquals(LevelsPickTarget.WHITE, picked.value) }
        compose.onNodeWithContentDescription("Input black").assertExists()
        compose.onNodeWithContentDescription("Input white").assertExists()
        compose.onNodeWithContentDescription("Gamma").assertExists()
        compose.onNodeWithContentDescription("Output black").assertExists()
        compose.onNodeWithContentDescription("Output white").assertExists()
    }

    @Test
    fun `numeric input and output fields commit bounded values`() {
        val levels = mutableStateOf(Levels())
        compose.setContent {
            CropTopTheme {
                AeLevelsEditor(levels.value, { levels.value = it }, Modifier, FloatArray(256) { 1f })
            }
        }

        fun enter(description: String, value: String) {
            val field = compose.onNodeWithContentDescription(description)
            field.performClick()
            field.performTextReplacement(value)
            field.performImeAction()
        }

        enter("Input black", "50")
        enter("Input white", "200")
        enter("Gamma", "1.25")
        enter("Output black", "15")
        enter("Output white", "240")

        compose.runOnIdle {
            assertEquals(50f / 255f, levels.value.inBlack)
            assertEquals(200f / 255f, levels.value.inWhite)
            assertEquals(1.25f, levels.value.gamma)
            assertEquals(15f / 255f, levels.value.outBlack)
            assertEquals(240f / 255f, levels.value.outWhite)
        }
    }
}
