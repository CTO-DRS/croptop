package app.croptop.feature.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.Density
import app.croptop.core.designsystem.theme.AeDensity
import app.croptop.core.designsystem.theme.CropTopTheme
import app.croptop.core.engine.api.EngineCapabilities
import app.croptop.core.model.UiPreferences
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
@Config(sdk = [35], qualifiers = "w1280dp-h1100dp-mdpi")
class SettingsScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun settingsLaptop() {
        compose.setContent {
            CropTopTheme(density = AeDensity.Pointer) {
                SettingsContent(
                    UiPreferences(),
                    onUpdate = {},
                    onBack = {},
                    onOpenCatalog = null,
                    about = AboutInfo(
                        "0.1.0",
                        EngineCapabilities(
                            engineVersion = "native-1",
                            abi = "x86_64",
                            gpuName = "Intel Arc Graphics",
                            supportedDepthBits = setOf(8, 16, 32),
                        ),
                    ),
                )
            }
        }
        compose.onRoot().captureRoboImage("src/test/snapshots/settings_1280.png")
    }

    @Test
    fun settingsAt200PercentFontScale() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                CropTopTheme(density = AeDensity.Pointer) {
                    SettingsContent(
                        UiPreferences(),
                        onUpdate = {},
                        onBack = {},
                        onOpenCatalog = null,
                        about = AboutInfo("0.1.0", EngineCapabilities("native-1", "x86_64")),
                    )
                }
            }
        }
        val titleHeight = compose.onNodeWithText("Settings").fetchSemanticsNode().size.height
        assertTrue(titleHeight >= 34, "Expected the Settings title to reflect 200% font scale, height was $titleHeight px")
        compose.onRoot().captureRoboImage("src/test/snapshots/settings_1280_200pct.png")
    }

    @OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
    @Test
    fun settingsTabTraversalLeavesPressureGraphAndReachesLaterSections() {
        compose.setContent {
            CropTopTheme(density = AeDensity.Pointer) {
                val inputModes = LocalInputModeManager.current
                LaunchedEffect(Unit) { inputModes.requestInputMode(InputMode.Keyboard) }
                SettingsContent(
                    UiPreferences(),
                    onUpdate = {},
                    onBack = {},
                    onOpenCatalog = null,
                    about = AboutInfo("0.1.0", EngineCapabilities("native-1", "x86_64")),
                )
            }
        }
        val focusedLabels = linkedSetOf<String>()
        repeat(120) {
            compose.onRoot().performKeyInput { pressKey(Key.Tab) }
            compose.onAllNodes(isFocused(), useUnmergedTree = false).fetchSemanticsNodes().forEach { node ->
                node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()?.let(focusedLabels::add)
                node.config.getOrNull(SemanticsProperties.Text)?.joinToString()?.let(focusedLabels::add)
            }
        }
        assertTrue("Reset pressure curve" in focusedLabels, "Tab traversal skipped the pressure reset: $focusedLabels")
        assertTrue("Customize…" in focusedLabels, "Tab traversal did not reach Keyboard Shortcuts: $focusedLabels")
        assertTrue("Memory for images" in focusedLabels, "Tab traversal did not reach Performance: $focusedLabels")
    }
}
