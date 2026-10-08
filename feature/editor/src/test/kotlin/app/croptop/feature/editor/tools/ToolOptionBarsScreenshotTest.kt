package app.croptop.feature.editor.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.theme.Ae
import app.croptop.core.designsystem.theme.AeDensity
import app.croptop.core.designsystem.theme.CropTopTheme
import app.croptop.core.designsystem.theme.ThemePreference
import app.croptop.core.model.ToolId
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The options bars with value bars instead of number boxes: one row per tool, as they sit under the menu bar. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h400dp-mdpi")
class ToolOptionBarsScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun optionBars() {
        val remove = AiRemoveTool { _, _ -> true }
        val clone = RetouchTool(ToolId.CLONE_STAMP, { _, _, _ -> null }, {}, {})
        val dodge = RetouchTool(ToolId.DODGE, { _, _, _ -> null }, {}, {})
        val lasso = MagneticLassoTool({ _, _ -> null }, {})
        compose.setContent {
            CropTopTheme(theme = ThemePreference.Dark, density = AeDensity.Pointer) {
                Column(Modifier.background(Ae.colors.surface1).padding(12.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                    listOf<ToolController>(remove, clone, dodge, lasso).forEach { tool ->
                        Row(Modifier.width(1240.dp)) { tool.Options() }
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/snapshots/tool_option_bars.png")
    }
}
