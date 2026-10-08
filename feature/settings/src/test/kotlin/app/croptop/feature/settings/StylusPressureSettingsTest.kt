package app.croptop.feature.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.croptop.core.designsystem.theme.AeDensity
import app.croptop.core.designsystem.theme.CropTopTheme
import app.croptop.core.engine.api.EngineCapabilities
import app.croptop.core.model.Curve
import app.croptop.core.model.CurvePoint
import app.croptop.core.model.UiPreferences
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h1100dp-mdpi")
class StylusPressureSettingsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun resetPressureCurvePersistsIdentityCalibration() {
        var preferences by mutableStateOf(
            UiPreferences(
                stylusPressureCurve = Curve(listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.2f), CurvePoint(1f, 1f))),
            ),
        )
        compose.setContent {
            CropTopTheme(density = AeDensity.Pointer) {
                SettingsContent(
                    prefs = preferences,
                    onUpdate = { update -> preferences = update(preferences) },
                    onBack = {},
                    onOpenCatalog = null,
                    about = AboutInfo(
                        "test",
                        EngineCapabilities(engineVersion = "test", abi = "x86_64", gpuName = "test", supportedDepthBits = setOf(8)),
                    ),
                )
            }
        }

        compose.onNodeWithText("Reset pressure curve").performScrollTo().performClick()

        assertEquals(Curve.Identity, preferences.stylusPressureCurve)
    }
}
