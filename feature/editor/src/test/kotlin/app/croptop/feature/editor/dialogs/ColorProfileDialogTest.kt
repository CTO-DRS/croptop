package app.croptop.feature.editor.dialogs

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.croptop.core.designsystem.theme.CropTopTheme
import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.EngineJob
import app.croptop.core.model.ColorProfileRef
import app.croptop.core.model.WorkingSpace
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-mdpi")
class ColorProfileDialogTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun assignProfileKeepsPixelsAndAppliesSelectedBuiltInProfile() {
        var applied: Pair<ColorProfileRef, Boolean>? = null
        var dismissed = false
        compose.setContent {
            CropTopTheme {
                ColorProfileDialog(
                    initial = ColorProfileRef.DisplayP3,
                    convertPixels = false,
                    onApply = { profile, convert ->
                        applied = profile to convert
                        EngineJob.completed(CommandResult.Accepted(1))
                    },
                    onDismiss = { dismissed = true },
                )
            }
        }

        compose.onNodeWithText("Assign the selected profile without changing RGB pixel values.").assertExists()
        compose.onNodeWithText("Display P3").performClick()
        compose.onNodeWithText("Adobe RGB (1998)").performClick()
        compose.onNodeWithText("Linear RGB").performClick()
        compose.onNodeWithText("Assign").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { dismissed }

        assertEquals(ColorProfileRef.BuiltIn(WorkingSpace.ADOBE_RGB, linear = true) to false, applied)
    }

    @Test
    fun convertProfileExplainsPixelConversionAndPassesConvertFlag() {
        var convertPixels: Boolean? = null
        compose.setContent {
            CropTopTheme {
                ColorProfileDialog(
                    initial = ColorProfileRef.Srgb,
                    convertPixels = true,
                    onApply = { _, convert ->
                        convertPixels = convert
                        EngineJob.completed(CommandResult.Accepted(2))
                    },
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText("Convert RGB pixel data and assign the selected profile as one undoable operation.").assertExists()
        compose.onNodeWithText("Convert").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { convertPixels != null }

        assertEquals(true, convertPixels)
    }
}
