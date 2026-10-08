package app.croptop.feature.settings

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.croptop.core.designsystem.theme.AeDensity
import app.croptop.core.designsystem.theme.CropTopTheme
import app.croptop.core.engine.api.EngineCapabilities
import app.croptop.core.model.UiPreferences
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Checks the actual Android keyboard focus path through the long Settings page. */
@RunWith(AndroidJUnit4::class)
class SettingsAccessibilityDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)
    @Test
    fun tabTraversalLeavesPressureGraphAndReachesLaterSettings() {
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

        assertTrue("Tab traversal skipped pressure reset: $focusedLabels", "Reset pressure curve" in focusedLabels)
        assertTrue("Tab traversal skipped Keyboard Shortcuts: $focusedLabels", "Customize…" in focusedLabels)
        assertTrue("Tab traversal skipped Performance: $focusedLabels", "Memory for images" in focusedLabels)
    }
}
