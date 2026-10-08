package app.croptop.core.designsystem.component

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.theme.AeDensity
import app.croptop.core.designsystem.theme.CropTopTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class AeNumberFieldTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun arrowKeysCommitTheChangedValueOnce() {
        val value = mutableFloatStateOf(10f)
        val committed = mutableListOf<Float>()

        compose.setContent {
            CropTopTheme(density = AeDensity.Pointer) {
                AeNumberField(
                    value = value.floatValue,
                    onValueChange = { value.floatValue = it },
                    modifier = Modifier.width(120.dp),
                    range = 0f..100f,
                    contentDescription = "Opacity",
                    onCommit = { committed += value.floatValue },
                )
            }
        }

        compose.onNodeWithContentDescription("Opacity").performClick().performKeyInput {
            pressKey(Key.DirectionUp)
            pressKey(Key.DirectionDown)
        }

        compose.runOnIdle {
            assertEquals(10f, value.floatValue)
            assertEquals(listOf(11f, 10f), committed)
        }
    }

    @Test
    fun arrowKeyStepsFromTheUncommittedTypedValue() {
        val value = mutableFloatStateOf(10f)
        val committed = mutableListOf<Float>()

        compose.setContent {
            CropTopTheme(density = AeDensity.Pointer) {
                AeNumberField(
                    value = value.floatValue,
                    onValueChange = { value.floatValue = it },
                    modifier = Modifier.width(120.dp),
                    range = 0f..100f,
                    contentDescription = "Opacity",
                    onCommit = { committed += value.floatValue },
                )
            }
        }

        val field = compose.onNodeWithContentDescription("Opacity")
        field.performClick()
        field.performTextReplacement("20")
        field.performKeyInput {
            pressKey(Key.DirectionUp)
        }

        compose.runOnIdle {
            assertEquals(21f, value.floatValue)
            assertEquals(listOf(21f), committed)
        }
    }
}
