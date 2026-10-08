package app.croptop.feature.editor.files

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.croptop.core.designsystem.theme.AeDensity
import app.croptop.core.designsystem.theme.CropTopTheme
import app.croptop.core.designsystem.theme.ThemePreference
import app.croptop.core.engine.api.ExportFormat
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h900dp-mdpi")
class ExportCompatibilityDialogTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun displaysEngineWarningsAndDismisses() {
        var dismissed = false
        compose.setContent {
            CropTopTheme(theme = ThemePreference.Dark, density = AeDensity.Pointer) {
                ExportCompatibilityDialog(
                    format = ExportFormat.PSD,
                    warnings = listOf("Unsupported layer effects were rasterized", "Embedded profile was preserved"),
                    onDismiss = { dismissed = true },
                )
            }
        }

        compose.onNodeWithText("The PSD export completed with these format or metadata changes:").assertExists()
        compose.onNodeWithText("• Unsupported layer effects were rasterized").assertExists()
        compose.onNodeWithText("• Embedded profile was preserved").assertExists()
        compose.onNodeWithText("Done").performClick()

        assertTrue(dismissed)
    }
}
