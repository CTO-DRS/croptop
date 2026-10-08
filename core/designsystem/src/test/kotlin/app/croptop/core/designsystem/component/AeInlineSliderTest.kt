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
class AeInlineSliderTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `semantic slider change publishes one final value`() {
        val value = mutableFloatStateOf(100f)
        var finishedValue: Float? = null
        compose.setContent {
            CropTopTheme {
                AeInlineSlider(
                    label = "Opacity",
                    value = value.floatValue,
                    onValueChange = { value.floatValue = it },
                    onValueChangeFinished = { finishedValue = it },
                    contentDescription = "Layer opacity slider",
                    showValueField = false,
                )
            }
        }

        compose.onNodeWithContentDescription("Layer opacity slider")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(46f) }

        compose.runOnIdle {
            assertEquals(46f, value.floatValue)
            assertEquals(46f, finishedValue)
        }
    }
}
