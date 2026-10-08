package app.croptop.core.designsystem.component

import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
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
@Config(sdk = [35], qualifiers = "w800dp-h1200dp-mdpi")
class AeSliderTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `semantic progress change publishes one finished callback`() {
        val value = mutableFloatStateOf(20f)
        var finished = 0
        compose.setContent {
            CropTopTheme {
                AeSlider(
                    label = "Brush size",
                    value = value.floatValue,
                    onValueChange = { value.floatValue = it },
                    range = 1f..100f,
                    onValueChangeFinished = { finished++ },
                )
            }
        }

        compose.onNodeWithContentDescription("Brush size")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(64f) }

        compose.runOnIdle {
            assertEquals(64f, value.floatValue)
            assertEquals(1, finished)
        }
    }
}
