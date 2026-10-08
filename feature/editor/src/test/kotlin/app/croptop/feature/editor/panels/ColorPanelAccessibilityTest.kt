package app.croptop.feature.editor.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.theme.Ae
import app.croptop.core.designsystem.theme.AeDensity
import app.croptop.core.designsystem.theme.CropTopTheme
import app.croptop.core.input.ActionDispatcher
import app.croptop.core.input.LocalActionDispatcher
import app.croptop.core.input.ShortcutManager
import app.croptop.core.model.RgbaColor
import app.croptop.feature.editor.session.ColorState
import com.github.takahirom.roborazzi.captureRoboImage
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w800dp-h1400dp-mdpi")
class ColorPanelAccessibilityTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun touchColorActionsFitInsideNarrowPanelAndMeetMinimum() {
        val recent = RgbaColor(1f, 0f, 0f)
        compose.setContent {
            CompositionLocalProvider(LocalActionDispatcher provides ActionDispatcher(ShortcutManager())) {
                CropTopTheme(density = AeDensity.Touch) {
                    Box(Modifier.width(360.dp).background(Ae.colors.surface1).testTag("color-panel")) {
                        ColorPanelContent(
                            colors = ColorState(foreground = RgbaColor.Black, background = RgbaColor.White, recent = listOf(recent)),
                            onForeground = {},
                            onBackground = {},
                            onCommit = {},
                            onSwap = {},
                            onDefaults = {},
                        )
                    }
                }
            }
        }
        val panel = compose.onNodeWithTag("color-panel")
        panel.captureRoboImage("src/test/snapshots/color_panel_touch_360.png")
        val panelBounds = panel.fetchSemanticsNode().boundsInRoot
        listOf(
            "Foreground color #${RgbaColor.Black.hex}",
            "Background color #${RgbaColor.White.hex}",
            "Recent color #${recent.hex}",
        ).forEach { description ->
            val action = compose.onNodeWithContentDescription(description).fetchSemanticsNode()
            val minimum = with(action.layoutInfo.density) { 40.dp.toPx() }
            assertTrue(action.size.width >= minimum && action.size.height >= minimum, "Undersized $description")
            val bounds = action.boundsInRoot
            assertTrue(bounds.left >= panelBounds.left && bounds.right <= panelBounds.right, "Clipped $description")
        }
    }
}
