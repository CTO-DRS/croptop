package app.croptop.feature.editor.tools

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.theme.AeDensity
import app.croptop.core.designsystem.theme.CropTopTheme
import app.croptop.core.model.RgbaColor
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ToolOptionsFeedbackTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun editGradientButtonInvokesItsOpenAction() {
        var opened = false
        val completed = mutableListOf<String>()
        val tool = GradientTool(
            colors = { RgbaColor.Black to RgbaColor.White },
            apply = { _, _, _, _, _, _, _ -> true },
            onEditGradient = { opened = true },
            onActionCompleted = completed::add,
        )

        compose.setContent {
            CropTopTheme(density = AeDensity.Pointer) {
                Box(Modifier.width(320.dp).horizontalScroll(rememberScrollState())) {
                    Row(Modifier.width(1000.dp)) { tool.Options() }
                }
            }
        }

        compose.onNodeWithText("Edit gradient…").performScrollTo().performClick()

        compose.runOnIdle {
            assertEquals(true, opened)
            assertEquals(emptyList(), completed)
        }
    }

    @Test
    fun reverseSettingReportsItsSettledAction() {
        val completed = mutableListOf<String>()
        val tool = GradientTool(
            colors = { RgbaColor.Black to RgbaColor.White },
            apply = { _, _, _, _, _, _, _ -> true },
            onActionCompleted = completed::add,
        )

        compose.setContent {
            CropTopTheme(density = AeDensity.Pointer) {
                Box(Modifier.width(320.dp).horizontalScroll(rememberScrollState())) {
                    Row(Modifier.width(1000.dp)) { tool.Options() }
                }
            }
        }

        compose.onNodeWithText("Reverse").performScrollTo().performClick()

        compose.runOnIdle {
            assertEquals(true, tool.reverse)
            assertEquals(listOf("Reverse gradient"), completed)
        }
    }

    @Test
    fun magicWandContiguousSettingReportsItsAction() {
        val completed = mutableListOf<String>()
        val tool = MagicWandTool(
            onActionCompleted = completed::add,
            apply = { _, _, _, _, _ -> true },
        )

        compose.setContent {
            CropTopTheme(density = AeDensity.Pointer) {
                Box(Modifier.width(320.dp).horizontalScroll(rememberScrollState())) {
                    Row(Modifier.width(1000.dp)) { tool.Options() }
                }
            }
        }

        compose.onNodeWithText("Contiguous").performScrollTo().performClick()

        compose.runOnIdle {
            assertFalse(tool.contiguous)
            assertEquals(listOf("Disable contiguous selection"), completed)
        }
    }

    @Test
    fun paintBucketContiguousSettingReportsItsAction() {
        val completed = mutableListOf<String>()
        val tool = PaintBucketTool(
            foreground = { RgbaColor.Black },
            onActionCompleted = completed::add,
            apply = { _, _, _, _, _, _ -> true },
        )

        compose.setContent {
            CropTopTheme(density = AeDensity.Pointer) {
                Box(Modifier.width(320.dp).horizontalScroll(rememberScrollState())) {
                    Row(Modifier.width(1000.dp)) { tool.Options() }
                }
            }
        }

        compose.onNodeWithText("Contiguous").performScrollTo().performClick()

        compose.runOnIdle {
            assertFalse(tool.contiguous)
            assertEquals(listOf("Disable contiguous fill"), completed)
        }
    }

    @Test
    fun gradientOpacityArrowReportsTheCommittedSetting() {
        val completed = mutableListOf<String>()
        val tool = GradientTool(
            colors = { RgbaColor.Black to RgbaColor.White },
            apply = { _, _, _, _, _, _, _ -> true },
            onActionCompleted = completed::add,
        ).apply {
            opacity = 0.5f
        }

        compose.setContent {
            CropTopTheme(density = AeDensity.Pointer) {
                Box(Modifier.width(320.dp).horizontalScroll(rememberScrollState())) {
                    Row(Modifier.width(1000.dp)) { tool.Options() }
                }
            }
        }

        compose.onNodeWithContentDescription("Opacity").performScrollTo().requestFocus().performKeyInput {
            pressKey(Key.DirectionUp)
        }

        compose.runOnIdle {
            assertEquals(0.51f, tool.opacity)
            assertEquals(listOf("Set gradient opacity"), completed)
        }
    }

    @Test
    fun quickSelectionSampleAllToggleReportsItsAction() {
        val completed = mutableListOf<String>()
        val tool = QuickSelectionTool(
            begin = { null },
            onResult = { false },
            selectSubject = { false },
        ).apply { onActionCompleted = completed::add }

        compose.setContent {
            CropTopTheme(density = AeDensity.Pointer) {
                Box(Modifier.width(320.dp).horizontalScroll(rememberScrollState())) {
                    Row(Modifier.width(1000.dp)) { tool.Options() }
                }
            }
        }

        compose.onNodeWithText("Sample all layers").performScrollTo().performClick()

        compose.runOnIdle {
            assertTrue(tool.sampleAll)
            assertEquals(listOf("Sample all layers for quick selection"), completed)
        }
    }

    @Test
    fun objectSelectionModeAndSampleAllReportTheirActions() {
        val completed = mutableListOf<String>()
        val tool = ObjectSelectTool(
            hasSelection = { false },
            selectObject = { _, _, _ -> false },
            deselect = { false },
            selectSubject = { false },
        ).apply { onActionCompleted = completed::add }

        compose.setContent {
            CropTopTheme(density = AeDensity.Pointer) {
                Box(Modifier.width(320.dp).horizontalScroll(rememberScrollState())) {
                    Row(Modifier.width(1000.dp)) { tool.Options() }
                }
            }
        }

        compose.onNodeWithText("Lasso").performScrollTo().performClick()
        compose.onNodeWithText("Sample all layers").performScrollTo().performClick()

        compose.runOnIdle {
            assertEquals(ObjectSelectMode.LASSO, tool.mode)
            assertTrue(tool.sampleAll)
            assertEquals(
                listOf("Set object selection mode to lasso", "Sample all layers for object selection"),
                completed,
            )
        }
    }

    @Test
    fun marqueeFeatherArrowReportsItsCommittedSetting() {
        val completed = mutableListOf<String>()
        val tool = MarqueeTool(
            tool = app.croptop.core.model.ToolId.RECT_MARQUEE,
            ellipse = false,
            hasSelection = { false },
            apply = { _, _, _ -> false },
        ).apply { onActionCompleted = completed::add }

        compose.setContent {
            CropTopTheme(density = AeDensity.Pointer) {
                Box(Modifier.width(320.dp).horizontalScroll(rememberScrollState())) {
                    Row(Modifier.width(1000.dp)) { tool.Options() }
                }
            }
        }

        compose.onNodeWithContentDescription("Feather").performScrollTo().requestFocus().performKeyInput {
            pressKey(Key.DirectionUp)
        }

        compose.runOnIdle {
            assertEquals(1f, tool.feather)
            assertEquals(listOf("Set marquee feather"), completed)
        }
    }

    @Test
    fun marqueeStyleDropdownUpdatesStateAndReportsCompletion() {
        val completed = mutableListOf<String>()
        val tool = MarqueeTool(
            tool = app.croptop.core.model.ToolId.RECT_MARQUEE,
            ellipse = false,
            hasSelection = { false },
            apply = { _, _, _ -> false },
        ).apply { onActionCompleted = completed::add }

        compose.setContent {
            CropTopTheme(density = AeDensity.Pointer) {
                Box(Modifier.width(320.dp).horizontalScroll(rememberScrollState())) {
                    Row(Modifier.width(1000.dp)) { tool.Options() }
                }
            }
        }

        compose.onNodeWithText("Normal").performClick()
        compose.onNodeWithText("Fixed Size").performClick()
        val width = compose.onNodeWithContentDescription("Width")
        width.performTextReplacement("50")
        width.performKeyInput {
            pressKey(Key.Enter)
        }

        compose.runOnIdle {
            assertEquals(MarqueeStyle.FIXED_SIZE, tool.style)
            assertEquals(50f, tool.fixedWidth)
            assertEquals(listOf("Set marquee style to fixed size", "Set marquee width"), completed)
        }
    }

    @Test
    fun cropPixelPreservationToggleReportsItsAction() {
        val completed = mutableListOf<String>()
        val tool = CropTool(document = { null }, commit = { _, _ -> false }).apply {
            onActionCompleted = completed::add
        }

        compose.setContent {
            CropTopTheme(density = AeDensity.Pointer) {
                Box(Modifier.width(320.dp).horizontalScroll(rememberScrollState())) {
                    Row(Modifier.width(1000.dp)) { tool.Options() }
                }
            }
        }

        compose.onNodeWithText("Delete Cropped Pixels").performScrollTo().performClick()

        compose.runOnIdle {
            assertFalse(tool.deleteCropped)
            assertEquals(listOf("Preserve cropped pixels"), completed)
        }
    }

    @Test
    fun transformRotationButtonReportsItsAction() {
        val completed = mutableListOf<String>()
        val tool = TransformTool(
            session = app.croptop.core.engine.api.UnsupportedTransformSession("Transform"),
            start = app.croptop.core.model.DocRectF(0f, 0f, 100f, 50f),
            label = "Free Transform",
            onCommit = { false },
            onEnd = {},
        ).apply { onActionCompleted = completed::add }

        compose.setContent {
            CropTopTheme(density = AeDensity.Pointer) {
                Box(Modifier.width(320.dp).horizontalScroll(rememberScrollState())) {
                    Row(Modifier.width(1000.dp)) { tool.Options() }
                }
            }
        }

        compose.onNodeWithContentDescription("Rotate 90° clockwise").performScrollTo().performClick()

        compose.runOnIdle {
            assertEquals(listOf("Rotate transform clockwise"), completed)
        }
    }

    @Test
    fun retouchSampleAllToggleReportsItsAction() {
        val completed = mutableListOf<String>()
        val tool = RetouchTool(
            tool = app.croptop.core.model.ToolId.BLUR,
            begin = { _, _, _ -> null },
            onResult = {},
            notify = {},
        ).apply { onActionCompleted = completed::add }

        compose.setContent {
            CropTopTheme(density = AeDensity.Pointer) {
                Box(Modifier.width(320.dp).horizontalScroll(rememberScrollState())) {
                    Row(Modifier.width(1000.dp)) { tool.Options() }
                }
            }
        }

        compose.onNodeWithText("Sample all layers").performScrollTo().performClick()

        compose.runOnIdle {
            assertTrue(tool.sampleAll)
            assertEquals(listOf("Sample all layers for blur"), completed)
        }
    }

    @Test
    fun eyedropperSampleSizeReportsItsSetting() {
        val completed = mutableListOf<String>()
        val tool = EyedropperTool(
            sample = { _, _, _ -> null },
            onForeground = {},
            onBackground = {},
            onCommit = {},
            activeColor = { app.croptop.core.model.RgbaColor.Black },
        ).apply { onActionCompleted = completed::add }

        compose.setContent {
            CropTopTheme(density = AeDensity.Pointer) {
                Box(Modifier.width(320.dp).horizontalScroll(rememberScrollState())) {
                    Row(Modifier.width(1000.dp)) { tool.Options() }
                }
            }
        }

        compose.onNodeWithText("1 × 1 px").performScrollTo().performClick()
        compose.onNodeWithText("3 × 3 px").performClick()

        compose.runOnIdle {
            assertEquals(3, tool.sampleSize)
            assertEquals(listOf("Set eyedropper sample size to 3×3 px"), completed)
        }
        tool.close()
    }

    @Test
    fun moveAutoSelectToggleReportsItsAction() {
        val completed = mutableListOf<String>()
        val tool = MoveTool(
            layerAt = { null },
            selectLayer = {},
            selectedBounds = { null },
            commit = { _, _ -> false },
        ).apply { onActionCompleted = completed::add }

        compose.setContent {
            CropTopTheme(density = AeDensity.Pointer) {
                Box(Modifier.width(320.dp).horizontalScroll(rememberScrollState())) {
                    Row(Modifier.width(1000.dp)) { tool.Options() }
                }
            }
        }

        compose.onNodeWithText("Auto-Select Layer").performScrollTo().performClick()

        compose.runOnIdle {
            assertFalse(tool.autoSelect)
            assertEquals(listOf("Disable auto-select layer"), completed)
        }
    }

    @Test
    fun textFontSizeCommitReportsItsAction() {
        val completed = mutableListOf<String>()
        val updates = mutableListOf<app.croptop.core.model.CharacterStyle>()
        val ui = app.croptop.feature.editor.text.TypeUi(
            families = null,
            onCharacter = updates::add,
        )

        compose.setContent {
            CropTopTheme(density = AeDensity.Pointer) {
                Box(Modifier.width(320.dp).horizontalScroll(rememberScrollState())) {
                    Row(Modifier.width(1000.dp)) {
                        app.croptop.feature.editor.text.TextOptions(ui, completed::add)
                    }
                }
            }
        }

        compose.onNodeWithContentDescription("Font size").performScrollTo().requestFocus().performKeyInput {
            pressKey(Key.DirectionUp)
        }

        compose.runOnIdle {
            assertEquals(1, updates.size)
            assertEquals("Set text size", completed.single())
        }
    }

    @Test
    fun dismissingFontPickerReportsAfterItsOpenAction() {
        val completed = mutableListOf<String>()
        val ui = app.croptop.feature.editor.text.TypeUi(
            families = null,
            onCharacter = {},
        )

        compose.setContent {
            CropTopTheme(density = AeDensity.Pointer) {
                app.croptop.feature.editor.text.TextOptions(ui, completed::add)
            }
        }

        compose.onNodeWithContentDescription("Font family, Roboto").performClick()
        compose.onNodeWithContentDescription("Search fonts").performClick().performKeyInput { pressKey(Key.Escape) }

        compose.runOnIdle {
            assertEquals(listOf("Open font picker", "Dismiss font picker"), completed)
        }
    }

    @Test
    fun fontPickerFavoriteToggleReportsItsAction() {
        val completed = mutableListOf<String>()
        val favorites = mutableListOf<String>()
        val ui = app.croptop.feature.editor.text.TypeUi(
            families = listOf(
                app.croptop.core.model.FontFamily(
                    "Test Sans",
                    listOf(app.croptop.core.model.FontFace("Test Sans", "Regular", "/missing-font.ttf")),
                ),
            ),
            onCharacter = {},
            onToggleFavorite = favorites::add,
        )

        compose.setContent {
            CropTopTheme(density = AeDensity.Pointer) {
                app.croptop.feature.editor.text.TextOptions(ui, completed::add)
            }
        }

        compose.onNodeWithContentDescription("Font family, Roboto").performClick()
        compose.onNodeWithContentDescription("Add Test Sans to favorites").performClick()

        compose.runOnIdle {
            assertEquals(listOf("Test Sans"), favorites)
            assertEquals(listOf("Open font picker", "Add Test Sans to favorites"), completed)
        }
    }

    @Test
    fun fontPickerFavoritesFilterReportsItsAction() {
        val completed = mutableListOf<String>()
        val ui = app.croptop.feature.editor.text.TypeUi(
            families = emptyList(),
            onCharacter = {},
        )

        compose.setContent {
            CropTopTheme(density = AeDensity.Pointer) {
                app.croptop.feature.editor.text.TextOptions(ui, completed::add)
            }
        }

        compose.onNodeWithContentDescription("Font family, Roboto").performClick()
        compose.onNodeWithContentDescription("Show favorites only").performClick()

        compose.runOnIdle {
            assertEquals(listOf("Open font picker", "Show favorite fonts only"), completed)
        }
    }

    @Test
    fun navigatorZoomKeyboardCommitReportsItsAction() {
        val completed = mutableListOf<String>()
        var viewport = app.croptop.core.model.ViewportState(zoom = 1f, viewWidth = 800, viewHeight = 600)

        compose.setContent {
            CropTopTheme(density = AeDensity.Pointer) {
                app.croptop.feature.editor.panels.NavigatorPanelContent(
                    viewport = viewport,
                    document = app.croptop.core.model.DocSize(1600, 1200),
                    onViewport = { viewport = it },
                    onActionCompleted = completed::add,
                )
            }
        }

        compose.onNodeWithContentDescription("Zoom percent").performClick().performKeyInput {
            pressKey(Key.DirectionUp)
        }

        compose.runOnIdle {
            assertTrue(viewport.zoom > 1f)
            assertEquals(listOf("Set Navigator zoom"), completed)
        }
    }

    @Test
    fun characterPanelToggleReportsItsAction() {
        val completed = mutableListOf<String>()
        val updates = mutableListOf<app.croptop.core.model.CharacterStyle>()
        val ui = app.croptop.feature.editor.text.TypeUi(
            families = null,
            onCharacter = updates::add,
        )

        compose.setContent {
            CropTopTheme(density = AeDensity.Pointer) {
                Box(Modifier.width(320.dp).horizontalScroll(rememberScrollState())) {
                    Row(Modifier.width(1000.dp)) {
                        app.croptop.feature.editor.text.CharacterPanelContent(ui, completed::add)
                    }
                }
            }
        }

        compose.onNodeWithText("Underline").performScrollTo().performClick()

        compose.runOnIdle {
            assertTrue(updates.single().underline)
            assertEquals(listOf("Underline text"), completed)
        }
    }

    @Test
    fun retouchSourceOverlayToggleReportsItsAction() {
        val completed = mutableListOf<String>()
        val tool = RetouchTool(
            tool = app.croptop.core.model.ToolId.CLONE_STAMP,
            begin = { _, _, _ -> null },
            onResult = {},
            notify = {},
        ).apply { onActionCompleted = completed::add }

        compose.setContent {
            CropTopTheme(density = AeDensity.Pointer) {
                Box(Modifier.width(320.dp).horizontalScroll(rememberScrollState())) {
                    Row(Modifier.width(1000.dp)) { tool.Options() }
                }
            }
        }

        compose.onNodeWithText("Show source overlay").performScrollTo().performClick()

        compose.runOnIdle {
            assertFalse(tool.showSourceOverlay)
            assertEquals(listOf("Hide source overlay"), completed)
        }
    }
}
