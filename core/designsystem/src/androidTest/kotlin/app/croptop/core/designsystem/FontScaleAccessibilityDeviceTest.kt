package app.croptop.core.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.croptop.core.designsystem.component.AeButton
import app.croptop.core.designsystem.component.AeDialog
import app.croptop.core.designsystem.component.AeGradientEditor
import app.croptop.core.designsystem.component.AePanel
import app.croptop.core.designsystem.component.AeText
import app.croptop.core.designsystem.theme.AeDensity
import app.croptop.core.designsystem.theme.CropTopTheme
import app.croptop.core.model.ColorGradient
import app.croptop.core.model.RgbaColor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Launched only by tools/run-accessibility-font-scale-test.sh, which sets/restores system font scale. */
@RunWith(AndroidJUnit4::class)
class FontScaleAccessibilityDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun gradientPropertiesFitAtSystemFontScaleTwoHundredPercent() {
        var observedFontScale = 0f
        compose.setContent {
            CropTopTheme(density = AeDensity.Touch) {
                val fontScale = LocalDensity.current.fontScale
                SideEffect { observedFontScale = fontScale }
                AeGradientEditor(ColorGradient.BlackToWhite, {}, Modifier.width(360.dp))
            }
        }

        compose.runOnIdle { assertTrue("Device must be at 200% font scale", observedFontScale >= 1.99f) }
        val nodes = listOf(
            compose.onNodeWithText("Add stop").fetchSemanticsNode(),
            compose.onNodeWithContentDescription("Stop color #${RgbaColor.Black.hex}").fetchSemanticsNode(),
            compose.onNodeWithContentDescription("Stop location").fetchSemanticsNode(),
            compose.onNodeWithContentDescription("Delete stop").fetchSemanticsNode(),
        )
        val maximumRight = with(nodes.first().layoutInfo.density) { 360.dp.toPx() }
        val controls = nodes.map { it.boundsInRoot }
        assertTrue(controls.all { it.left >= 0f && it.right <= maximumRight })
        assertTrue(
            controls.indices.all { first ->
                (first + 1 until controls.size).all { second -> !controls[first].overlaps(controls[second]) }
            },
        )
    }

    @Test
    fun panelAndDialogActionsStayOnScreenAtSystemFontScaleTwoHundredPercent() {
        val showDialog = mutableStateOf(false)
        var screenWidthDp = 0
        compose.setContent {
            CropTopTheme(density = AeDensity.Touch) {
                val fontScale = LocalDensity.current.fontScale
                val screenWidth = with(LocalDensity.current) { androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.width.toDp().value.toInt() }
                SideEffect {
                    screenWidthDp = screenWidth
                    assertTrue("Device must be at 200% font scale", fontScale >= 1.99f)
                }
                if (showDialog.value) {
                    AeDialog(
                        title = "Export settings",
                        body = "Choose how to save this image and preserve the original document.",
                        width = 340.dp,
                        onDismiss = {},
                        actions = {
                            AeButton("Keep original", {})
                            AeButton("Export image", {})
                        },
                        onConfirm = {},
                    )
                } else {
                    Column(Modifier.width(360.dp)) {
                        AePanel("Layer properties", expanded = true, onExpandedChange = {}) {
                            AeText("Adjust the selected layer")
                            AeButton("Reset layer properties", {})
                            AeButton("Restore default settings", {})
                        }
                    }
                }
            }
        }

        val panelLabels = listOf("LAYER PROPERTIES", "Reset layer properties", "Restore default settings")
        val panelNodes = panelLabels.map { compose.onNodeWithText(it).fetchSemanticsNode() }
        val panelRight = with(panelNodes.first().layoutInfo.density) { 360.dp.toPx() }
        assertTrue(panelNodes.all { it.boundsInRoot.left >= 0f && it.boundsInRoot.right <= panelRight })
        assertFalse(panelNodes[1].boundsInRoot.overlaps(panelNodes[2].boundsInRoot))

        compose.runOnIdle { showDialog.value = true }
        val dialogNodes = listOf("Export settings", "Keep original", "Export image")
            .map { compose.onNodeWithText(it).fetchSemanticsNode() }
        val dialogRight = with(dialogNodes.first().layoutInfo.density) { screenWidthDp.dp.toPx() }
        assertTrue(dialogNodes.all { it.boundsInRoot.left >= 0f && it.boundsInRoot.right <= dialogRight })
        assertFalse(dialogNodes[1].boundsInRoot.overlaps(dialogNodes[2].boundsInRoot))
    }
}
