package app.croptop.feature.editor.properties

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.croptop.core.designsystem.theme.AeDensity
import app.croptop.core.designsystem.theme.CropTopTheme
import app.croptop.core.model.ChoiceParam
import app.croptop.core.model.FilterCatalog
import app.croptop.core.model.ParamValue
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w800dp-h1000dp-mdpi")
class DisplaceLayerChoiceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun displaceOffersTheDocumentsPixelLayersAsItsMap() {
        val spec = checkNotNull(FilterCatalog.get(FilterCatalog.PREFIX + "displace"))
        val values = mutableStateOf(spec.defaults())
        compose.setContent {
            CropTopTheme(density = AeDensity.Pointer) {
                AdjustmentProperties(
                    spec,
                    values.value,
                    { next, _ -> values.value = next },
                    layerChoices = listOf(ChoiceParam.Option("layer:7", "Wood grain"), ChoiceParam.Option("layer:9", "Clouds map")),
                )
            }
        }
        compose.onNodeWithText("Choose a layer…").performClick()
        compose.onNodeWithText("Wood grain").assertExistsAndClick()
        compose.runOnIdle { assertEquals(ParamValue.Choice("layer:7"), values.value["map"]) }
        // The chosen layer is shown by name afterwards.
        compose.onNodeWithText("Wood grain").assertExists()
    }

    @Test
    fun aLayerThatWasDeletedFallsBackToTheFirstOptionInsteadOfCrashing() {
        val spec = checkNotNull(FilterCatalog.get(FilterCatalog.PREFIX + "displace"))
        compose.setContent {
            CropTopTheme(density = AeDensity.Pointer) {
                AdjustmentProperties(spec, spec.defaults() + ("map" to ParamValue.Choice("layer:99")), { _, _ -> }, layerChoices = emptyList())
            }
        }
        compose.onNodeWithText("Choose a layer…").assertExists()
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertExistsAndClick() {
        assertExists()
        performClick()
    }
}
