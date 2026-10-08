package app.croptop.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.component.LevelsPickTarget
import app.croptop.core.designsystem.theme.Ae
import app.croptop.core.designsystem.theme.AeDensity
import app.croptop.core.designsystem.theme.CropTopTheme
import app.croptop.core.designsystem.theme.ThemePreference
import app.croptop.core.model.AdjustmentCatalog
import app.croptop.core.model.AdjustmentSpec
import app.croptop.core.model.ColorParam
import app.croptop.core.model.Curve
import app.croptop.core.model.CurveChannels
import app.croptop.core.model.CurvePoint
import app.croptop.core.model.HueChannel
import app.croptop.core.model.Levels
import app.croptop.core.model.ParamValue
import app.croptop.core.model.RgbaColor
import app.croptop.feature.editor.properties.AdjustmentProperties
import com.github.takahirom.roborazzi.captureRoboImage
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** the Properties panel generated from AdjustmentSpec. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h900dp-mdpi")
class AdjustmentPropertiesTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun generatedPanels() {
        val shown = listOf(
            "brightnessContrast" to mapOf("contrast" to ParamValue.Num(30f)),
            "blackWhite" to mapOf("tint" to ParamValue.Bool(true)),
            "photoFilter" to mapOf("filter" to ParamValue.Choice("custom")),
            "channelMixer" to emptyMap(),
        )
        compose.setContent {
            CropTopTheme(theme = ThemePreference.Dark, density = AeDensity.Pointer) {
                Row(Modifier.background(Ae.colors.app), verticalAlignment = Alignment.Top) {
                    shown.forEach { (id, v) ->
                        var values by remember { mutableStateOf(v) }
                        AdjustmentProperties(
                            AdjustmentCatalog[id]!!,
                            values,
                            { updated, _ -> values = updated },
                            Modifier.width(300.dp).background(Ae.colors.surface1),
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("Increase Contrast").assertExists() // preset recognised from the values
        compose.onNodeWithText("Tint color").assertExists() // showIf
        compose.onRoot().captureRoboImage("src/test/snapshots/properties_adjustments.png")
    }

    @Test
    fun everyCatalogSpecRenders() {
        var index by mutableStateOf(0)
        compose.setContent {
            CropTopTheme(theme = ThemePreference.Dark, density = AeDensity.Pointer) {
                AdjustmentProperties(AdjustmentCatalog.All[index], emptyMap(), { _, _ -> }, Modifier.width(300.dp))
            }
        }
        AdjustmentCatalog.All.indices.forEach { i ->
            index = i
            compose.waitForIdle()
            compose.onAllNodesWithText(AdjustmentCatalog.All[i].name).onFirst().assertExists()
        }
    }

    @Test
    fun canvasTargetingActionIsAvailableForCurves() {
        var armed = false
        compose.setContent {
            CropTopTheme(theme = ThemePreference.Dark, density = AeDensity.Pointer) {
                AdjustmentProperties(
                    AdjustmentCatalog["curves"]!!,
                    emptyMap(),
                    { _, _ -> },
                    Modifier.width(300.dp),
                    onCanvasTarget = { armed = true },
                )
            }
        }

        compose.onNodeWithText("Target tone on canvas").performClick()

        assertTrue(armed)
    }

    @Test
    fun hueTargetingSelectsTheSampledEditorChannel() {
        compose.setContent {
            CropTopTheme(theme = ThemePreference.Dark, density = AeDensity.Pointer) {
                AdjustmentProperties(
                    AdjustmentCatalog["hueSaturation"]!!,
                    emptyMap(),
                    { _, _ -> },
                    Modifier.width(300.dp),
                    targetedHueChannel = HueChannel.BLUES,
                )
            }
        }

        compose.waitForIdle()
        compose.onNodeWithText("Blues").assertExists()
    }

    @Test
    fun curvePropertiesRoundTripIndependentChannels() {
        val spec = AdjustmentCatalog["curves"]!!
        val initial = ParamValue.CurveChannelsValue(
            CurveChannels(
                rgb = Curve.Identity.withPoint(CurvePoint(0.35f, 0.6f)).first,
                red = Curve.Identity.withPoint(CurvePoint(0.5f, 0.75f)).first,
                green = Curve.Identity.withPoint(CurvePoint(0.5f, 0.25f)).first,
                blue = Curve.Identity.withPoint(CurvePoint(0.65f, 0.7f)).first,
            ),
        )
        var values by mutableStateOf(mapOf("curve" to initial as ParamValue))
        compose.setContent {
            CropTopTheme(theme = ThemePreference.Dark, density = AeDensity.Pointer) {
                AdjustmentProperties(spec, values, { updated, _ -> values = updated }, Modifier.width(300.dp))
            }
        }

        compose.onNodeWithText("Red").performClick()
        val graph = compose.onNodeWithContentDescription("Curve editor graph with 3 points")
        graph.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        graph.performKeyInput {
            pressKey(Key.Tab)
            pressKey(Key.Tab)
            pressKey(Key.DirectionUp)
        }

        val edited = values.getValue("curve") as ParamValue.CurveChannelsValue
        assertEquals(initial.v.rgb, edited.v.rgb)
        assertEquals(initial.v.green, edited.v.green)
        assertEquals(initial.v.blue, edited.v.blue)
        assertTrue(edited.v.red.points[1].y > initial.v.red.points[1].y)

        val serializer = MapSerializer(String.serializer(), ParamValue.serializer())
        val json = Json { encodeDefaults = true }
        val reopened = json.decodeFromString(serializer, json.encodeToString(serializer, values))
        assertEquals(values, reopened)
    }

    @Test
    fun levelsPropertiesRequestTheSelectedPickTarget() {
        var requested: LevelsPickTarget? = null
        compose.setContent {
            CropTopTheme(theme = ThemePreference.Dark, density = AeDensity.Pointer) {
                AdjustmentProperties(
                    AdjustmentCatalog["levels"]!!,
                    mapOf("levels" to ParamValue.LevelsValue(Levels())),
                    { _, _ -> },
                    Modifier.width(300.dp),
                    onLevelsPick = { requested = it },
                )
            }
        }

        compose.onNodeWithText("Gray").performClick()
        assertEquals(LevelsPickTarget.GRAY, requested)
    }

    @Test
    fun colorParamOpensPickerAndCommitsWithContextualAction() {
        val spec = AdjustmentSpec(
            id = "color-test",
            name = "Color Test",
            params = listOf(ColorParam("tint", "Tint", RgbaColor.Black)),
        )
        var values = spec.defaults()
        var action: String? = null
        var uiAction: String? = null
        compose.setContent {
            CropTopTheme(theme = ThemePreference.Dark, density = AeDensity.Pointer) {
                AdjustmentProperties(
                    spec,
                    values,
                    { updated, label ->
                        values = updated
                        action = label
                    },
                    Modifier.width(300.dp),
                    onActionCompleted = { uiAction = it },
                )
            }
        }

        compose.onNodeWithContentDescription("Tint", substring = true, useUnmergedTree = true).performClick()
        assertEquals("Open tint color picker", uiAction)
        compose.onNodeWithContentDescription("Saturation and brightness").performTouchInput { click(center) }

        assertEquals("Set tint color", action)
        assertTrue((values.getValue("tint") as ParamValue.Color).v != RgbaColor.Black)
    }
}
