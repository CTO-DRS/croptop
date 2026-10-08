package app.croptop.feature.editor.panels

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.croptop.core.designsystem.theme.AeDensity
import app.croptop.core.designsystem.theme.CropTopTheme
import app.croptop.core.designsystem.theme.ThemePreference
import app.croptop.core.engine.api.Histogram
import app.croptop.core.engine.api.HistogramScope
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h800dp-mdpi")
class HistogramPanelTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun channelVisibilityExpansionScopeAndLiveRefreshAreExposed() {
        val histogram = MutableStateFlow(sampleHistogram(redBin = 40, weight = 4.0))
        var selectedScope = HistogramScope.MERGED
        val scopes = listOf(HistogramScope.MERGED, HistogramScope.ACTIVE_LAYER, HistogramScope.SELECTION)

        compose.setContent {
            val current by histogram.collectAsState()
            CropTopTheme(theme = ThemePreference.Dark, density = AeDensity.Touch) {
                HistogramPanelContent(current, selectedScope, scopes) { selectedScope = it }
            }
        }

        compose.onNodeWithContentDescription("Red histogram hidden").performClick()
        compose.onNodeWithContentDescription("Red histogram visible").assertExists()
        compose.onNodeWithText("4.0 px · μ 40.0 · σ 0.0 · med 40", substring = true).assertExists()

        compose.onNodeWithText("Merged image").performClick()
        compose.onNodeWithText("Active layer").performClick()
        assertEquals(HistogramScope.ACTIVE_LAYER, selectedScope)

        compose.onNodeWithText("Expand histogram").performClick()
        compose.onNodeWithText("Compact histogram").assertExists()

        histogram.value = sampleHistogram(redBin = 90, weight = 6.0)
        compose.waitForIdle()
        compose.onNodeWithText("6.0 px · μ 90.0 · σ 0.0 · med 90", substring = true).assertExists()
    }

    private fun sampleHistogram(redBin: Int, weight: Double): Histogram {
        fun bins(at: Int, value: Double) = List(256) { index -> if (index == at) value else 0.0 }
        return Histogram.fromChannels(
            bins(redBin, weight),
            bins(120, weight),
            bins(200, weight),
            bins(130, weight),
        )
    }
}
