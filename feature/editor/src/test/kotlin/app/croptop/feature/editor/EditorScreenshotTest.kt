package app.croptop.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import app.croptop.core.data.GradientRepository
import app.croptop.core.designsystem.component.AePanel
import app.croptop.core.designsystem.theme.AeDensity
import app.croptop.core.designsystem.theme.CropTopTheme
import app.croptop.core.designsystem.theme.ThemePreference
import app.croptop.core.engine.fake.FakeEngine
import app.croptop.core.input.ActionDispatcher
import app.croptop.core.input.LocalActionDispatcher
import app.croptop.core.input.ShortcutManager
import app.croptop.core.model.ColorGradient
import app.croptop.core.model.DocSize
import app.croptop.core.model.DockPanel
import app.croptop.core.model.GradientPreset
import app.croptop.core.model.LayerKind
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.WorkspaceLayout
import app.croptop.feature.editor.files.ExportDialog
import app.croptop.feature.editor.files.UnsavedChangesDialog
import app.croptop.feature.editor.panels.LayersPanelContent
import app.croptop.feature.editor.session.EditorViewModel
import app.croptop.feature.editor.tools.GradientPresetDialog
import app.croptop.feature.editor.tools.GradientPresetsViewModel
import com.github.takahirom.roborazzi.captureRoboImage
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-mdpi")
class EditorScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    @Config(qualifiers = "w1280dp-h120dp-mdpi")
    fun colorRangeOptionsBar() {
        val session = object : app.croptop.core.engine.api.ColorRangeSession {
            override fun update(spec: app.croptop.core.engine.api.ColorRangeSpec) = Unit
            override fun commit() = app.croptop.core.engine.api.CommandResult.Accepted(1)
            override fun cancel() = Unit
        }
        val colors = listOf(app.croptop.core.model.RgbaColor(0.85f, 0.35f, 0.2f), app.croptop.core.model.RgbaColor(0.2f, 0.45f, 0.85f))
        var i = 0
        val mode = app.croptop.feature.editor.tools.ColorRangeMode(session, { colors[i++ % colors.size] }, { true }) {}
        val ctx = object : app.croptop.feature.editor.tools.ToolContext {
            override val viewport = app.croptop.core.model.ViewportState(zoom = 1f, viewWidth = 100, viewHeight = 100)
            override val document = DocSize(100, 100)
            override fun updateViewport(state: app.croptop.core.model.ViewportState) = Unit
        }
        repeat(2) { n ->
            val p = app.croptop.core.model.DocPoint(10f + n, 10f)
            mode.onPointer(
                app.croptop.feature.editor.tools.ToolPointerEvent(
                    app.croptop.feature.editor.tools.PointerPhase.DOWN,
                    app.croptop.core.model.ViewPoint(p.x, p.y),
                    p,
                    1f,
                    app.croptop.core.input.Modifiers(shift = true),
                    app.croptop.core.model.ViewPoint(p.x, p.y),
                ),
                ctx,
            )
        }
        compose.setContent {
            CropTopTheme(theme = ThemePreference.Dark, density = AeDensity.Pointer) {
                androidx.compose.foundation.layout.Row(
                    androidx.compose.ui.Modifier.fillMaxWidth().height(
                        40.dp,
                    ).background(app.croptop.core.designsystem.theme.Ae.colors.surface2).padding(horizontal = 12.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) { mode.Options() }
            }
        }
        compose.onRoot().captureRoboImage("src/test/snapshots/editor_color_range_bar.png")
    }

    @Test
    fun gradientEditorAndPresets() {
        val repository = object : GradientRepository {
            override val customPresets = flowOf(listOf(GradientPreset(1, "Ocean", ColorGradient.BlackToWhite)))
            override suspend fun save(name: String, gradient: ColorGradient) = GradientPreset(2, name, gradient)
            override suspend fun update(id: Long, name: String, gradient: ColorGradient) = true
            override suspend fun delete(id: Long) = true
        }
        val presets = GradientPresetsViewModel(repository)
        compose.setContent {
            CropTopTheme(theme = ThemePreference.Dark, density = AeDensity.Pointer) {
                GradientPresetDialog(ColorGradient.BlackToWhite, {}, {}, presets)
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/snapshots/editor_gradient_presets.png")
    }

    @Test
    fun layersPanelWithGroupAndHiddenLayer() {
        val vm =
            EditorViewModel(
                FakeEngine(),
                NoRecents,
                TempShareCache,
                app.croptop.core.agent.OpenDocuments(FakeEngine()),
                { null },
                EditorRoute(newSpec = NewDocumentSpec(name = "Shot", size = DocSize(4000, 3000))),
            )
        vm.newLayer()
        vm.newLayer()
        vm.groupSelected()
        vm.newLayer(LayerKind.ADJUSTMENT)
        val hidden = vm.state.value.layers.flatten().first { it.name == "Layer 2" }.id
        vm.setVisible(hidden, false)
        vm.setOpacity(65f)
        compose.setContent {
            CompositionLocalProvider(LocalActionDispatcher provides ActionDispatcher(ShortcutManager())) {
                CropTopTheme(theme = ThemePreference.Dark, density = AeDensity.Pointer) {
                    AePanel("Layers", Modifier.width(280.dp).height(640.dp)) {
                        LayersPanelContent(vm.state.collectAsState().value, vm)
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/snapshots/layers_panel_dark.png")
    }

    @Test
    fun imageSizeDialog() {
        compose.setContent {
            CropTopTheme(theme = ThemePreference.Dark, density = AeDensity.Pointer) {
                app.croptop.feature.editor.dialogs.ImageSizeDialog(DocSize(6000, 4000), 300f, 1, onApply = { _, _ -> }, onDismiss = {})
            }
        }
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Width")).performTextReplacement("3000")
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Width")).performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Enter) }
        compose.waitForIdle()
        compose.onNode(isDialog()).captureRoboImage("src/test/snapshots/dialog_image_size.png")
    }

    @Test
    fun canvasSizeDialog() {
        compose.setContent {
            CropTopTheme(theme = ThemePreference.Dark, density = AeDensity.Pointer) {
                app.croptop.feature.editor.dialogs.CanvasSizeDialog(DocSize(6000, 4000), 300f, onApply = { _, _ -> }, onDismiss = {})
            }
        }
        compose.onNode(isDialog()).captureRoboImage("src/test/snapshots/dialog_canvas_size.png")
    }

    @Test
    fun exportDialog() {
        compose.setContent {
            CropTopTheme(theme = ThemePreference.Dark, density = AeDensity.Pointer) {
                ExportDialog(DocSize(6000, 4000), onExport = {}, onCancel = {})
            }
        }
        compose.onNode(isDialog()).captureRoboImage("src/test/snapshots/dialog_export.png")
    }

    @Test
    fun unsavedDialog() {
        compose.setContent {
            CropTopTheme(theme = ThemePreference.Dark, density = AeDensity.Pointer) {
                UnsavedChangesDialog("sunset.jpg", onSave = {}, onDiscard = {}, onCancel = {})
            }
        }
        compose.onNode(isDialog()).captureRoboImage("src/test/snapshots/dialog_unsaved.png")
    }

    private fun editor(
        name: String,
        layout: WorkspaceLayout = WorkspaceLayout.Essentials,
        aids: app.croptop.core.model.ViewAids? = null,
        fontScale: Float = 1f,
        typeUi: app.croptop.feature.editor.text.TypeUi = app.croptop.feature.editor.text.TypeUi(families = null),
        clickTool: String? = null,
        brushUi: app.croptop.feature.editor.brush.BrushUi = app.croptop.feature.editor.brush.BrushUi(),
        setup: (EditorViewModel) -> Unit = {},
    ) {
        val vm =
            EditorViewModel(
                FakeEngine(),
                NoRecents,
                TempShareCache,
                app.croptop.core.agent.OpenDocuments(FakeEngine()),
                { null },
                EditorRoute(newSpec = NewDocumentSpec(name = "Portrait", size = DocSize(4000, 5000))),
            )
        vm.newLayer()
        vm.newLayer(LayerKind.ADJUSTMENT)
        setup(vm)
        aids?.let { vm.viewAids.value = it }
        compose.setContent {
            CompositionLocalProvider(LocalActionDispatcher provides ActionDispatcher(ShortcutManager())) {
                val d = androidx.compose.ui.platform.LocalDensity.current
                CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density, fontScale)) {
                    CropTopTheme(theme = ThemePreference.Dark, density = AeDensity.Pointer) {
                        EditorContent(vm, EditorRoute(), onClose = {}, layout = layout, typeUi = typeUi, brushUi = brushUi)
                    }
                }
            }
        }
        compose.waitForIdle()
        clickTool?.let {
            compose.onNode(androidx.compose.ui.test.hasContentDescription(it) and androidx.compose.ui.test.hasClickAction()).performClick()
            compose.waitForIdle()
        }
        compose.onRoot().captureRoboImage("src/test/snapshots/editor_$name.png")
    }

    @Test
    fun fullEditorLaptop() = editor("1280x800_dark")

    @Test
    fun gradientToolOptions() = editor("gradient_tool", clickTool = "Gradient")

    @Test
    fun objectSelectionToolOptions() = editor("object_select_tool", clickTool = "Object Selection")

    @Test
    fun layersWithMasksAndClipping() = editor("layers_masks") { vm ->
        vm.newLayer() // "Layer 2", clipped to Layer 1 with a disabled mask
        vm.addMask()
        vm.toggleMask(vm.state.value.primary!!)
        vm.toggleClipping()
        vm.select(vm.state.value.layers.flatten().first { it.name == "Adjustment 1" }.id)
        vm.addMask()
    }

    @Test
    @Config(qualifiers = "w1280dp-h1000dp-mdpi")
    fun maskProperties() = editor("mask_properties") { vm ->
        vm.newLayer()
        vm.addMask()
        vm.setMask(vm.state.value.primary!!, app.croptop.core.model.LayerMaskInfo(density = 0.8f, feather = 12.5f))
    }

    @Test
    fun cloneStampToolOptions() = editor("clone_stamp_tool", clickTool = "Clone Stamp")

    @Test
    fun dodgeToolOptions() = editor("dodge_tool", clickTool = "Dodge")

    @Test
    @Config(qualifiers = "w720dp-h800dp-mdpi")
    fun mediumWindowRail() = editor("720x800_rail")

    @Test
    @Config(qualifiers = "w480dp-h800dp-mdpi")
    fun compactWindow() = editor("480x800_compact")

    @Test
    @Config(qualifiers = "w1280dp-h1000dp-mdpi")
    fun paintingWorkspaceColorPanel() = editor("1280x1000_painting", WorkspaceLayout.Painting)

    @Test
    fun floatingNavigator() = editor(
        "1280x800_floating",
        WorkspaceLayout.Photography.update(DockPanel.NAVIGATOR) { it.copy(floating = true, floatX = 420f, floatY = 60f) },
    )

    @Test
    fun rulersGridGuides() = editor(
        "1280x800_rulers",
        aids = app.croptop.core.model.ViewAids(rulers = true, grid = true, gridSettings = app.croptop.core.model.GridSettings(500f, 2))
            .let { app.croptop.core.model.GuideLayout(columns = 3, columnGutter = 100f, margin = 200f).applyTo(it, DocSize(4000, 5000)) }
            .addGuide(app.croptop.core.model.Guide(app.croptop.core.model.GuideAxis.HORIZONTAL, 1667f)),
    )

    /** LS-D8: chrome at 150% font scale must still fit (no clipped labels in bars and panels). */
    @Test
    fun largeFontScale() = editor("1280x800_font150", fontScale = 1.5f)

    /** The editor chrome and dock panels at the supported 200% system text scale. */
    @Test
    fun largeFontScaleTwoHundredPercent() = editor("1280x800_font200", fontScale = 2f)

    /** The Text options bar with the Character and Paragraph panels docked. */
    @Test
    @Config(qualifiers = "w1280dp-h1000dp-mdpi")
    fun textToolAndTypePanels() {
        val faces = listOf(
            app.croptop.core.model.FontFace("Roboto", "Regular", "/none"),
            app.croptop.core.model.FontFace("Roboto", "Bold", "/none", weight = 700),
            app.croptop.core.model.FontFace(
                "Roboto Flex",
                "Regular",
                "/none",
                axes = listOf(
                    app.croptop.core.model.FontAxis("wght", "Weight", 100f, 400f, 1000f),
                    app.croptop.core.model.FontAxis("wdth", "Width", 25f, 100f, 151f),
                ),
            ),
        )
        var layout = WorkspaceLayout.Essentials
        listOf(DockPanel.NAVIGATOR, DockPanel.COLOR, DockPanel.HISTORY, DockPanel.PROPERTIES).forEach { p ->
            layout =
                layout.update(p) { it.copy(visible = false) }
        }
        layout = layout.update(DockPanel.LAYERS) { it.copy(expanded = false) }
            .update(DockPanel.CHARACTER) { it.copy(visible = true) }
            .update(DockPanel.PARAGRAPH) { it.copy(visible = true) }
        editor(
            "1280x1000_text",
            layout,
            typeUi = app.croptop.feature.editor.text.TypeUi(
                families = app.croptop.core.model.FontFamily.group(faces),
                character = app.croptop.core.model.CharacterStyle(family = "Roboto Flex", sizePt = 36f, tracking = 20),
            ),
            clickTool = "Text",
        )
    }

    /** Brush options bar and panels with a live stroke preview. */
    @Test
    @Config(qualifiers = "w1280dp-h1000dp-mdpi")
    fun brushToolAndPanels() {
        var layout = WorkspaceLayout.Painting
        listOf(DockPanel.NAVIGATOR, DockPanel.COLOR, DockPanel.HISTORY).forEach { p -> layout = layout.update(p) { it.copy(visible = false) } }
        layout = layout.update(DockPanel.LAYERS) { it.copy(expanded = false) }
            .update(DockPanel.BRUSHES) { it.copy(expanded = true) }
            .update(DockPanel.BRUSH_SETTINGS) { it.copy(expanded = true) }
        val ink = app.croptop.core.model.BrushPreset.BuiltIn.first { it.name == "Ink Pen" }
        editor(
            "1280x1000_brush",
            layout,
            clickTool = "Brush",
            brushUi = app.croptop.feature.editor.brush.BrushUi(
                presets = app.croptop.core.model.BrushPreset.BuiltIn.map { if (it.id == -2L) it.copy(favorite = true) else it },
                settings = ink.settings.copy(size = 18f),
                presetId = ink.id,
            ),
        )
    }

    /** The History panel with named snapshots above its edit steps. */
    @Test
    fun historyPanelWithSnapshots() {
        val vm = EditorViewModel(
            FakeEngine(),
            NoRecents,
            TempShareCache,
            app.croptop.core.agent.OpenDocuments(FakeEngine()),
            { null },
            EditorRoute(newSpec = NewDocumentSpec(name = "Shot", size = DocSize(4000, 3000))),
        )
        vm.createSnapshot("Before retouch")
        vm.newLayer()
        vm.selectAll()
        vm.createSnapshot("Snapshot 1")
        vm.newLayer()
        vm.undo()
        compose.setContent {
            CompositionLocalProvider(LocalActionDispatcher provides ActionDispatcher(ShortcutManager())) {
                CropTopTheme(theme = ThemePreference.Dark, density = AeDensity.Pointer) {
                    AePanel("History", Modifier.width(280.dp).height(360.dp)) {
                        app.croptop.feature.editor.panels.HistoryPanelContent(vm.state.collectAsState().value.history, onJump = {})
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/snapshots/history_panel_snapshots.png")
    }

    private fun liveEditor(): EditorViewModel {
        val vm = EditorViewModel(
            FakeEngine(),
            NoRecents,
            TempShareCache,
            app.croptop.core.agent.OpenDocuments(FakeEngine()),
            { null },
            EditorRoute(newSpec = NewDocumentSpec(name = "Tools", size = DocSize(4000, 3000))),
        )
        compose.setContent {
            CompositionLocalProvider(LocalActionDispatcher provides ActionDispatcher(ShortcutManager())) {
                CropTopTheme(theme = ThemePreference.Dark, density = AeDensity.Pointer) {
                    EditorContent(vm, EditorRoute(), onClose = {}, layout = WorkspaceLayout.Essentials)
                }
            }
        }
        compose.waitForIdle()
        return vm
    }

    private fun pickTool(name: String) {
        compose.onNode(androidx.compose.ui.test.hasContentDescription(name) and androidx.compose.ui.test.hasClickAction()).performClick()
        compose.waitForIdle()
    }

    /** A Rectangular Marquee drag creates a selection with marching ants. */
    @Test
    fun marqueeDragSelects() {
        val vm = liveEditor()
        pickTool("Rectangular Marquee")
        compose.onRoot().performTouchInput {
            down(androidx.compose.ui.geometry.Offset(420f, 250f))
            moveTo(androidx.compose.ui.geometry.Offset(560f, 380f))
            moveTo(androidx.compose.ui.geometry.Offset(700f, 520f))
            up()
        }
        compose.waitForIdle()
        val sel = vm.state.value.selection
        assert(sel is app.croptop.core.model.SelectionInfo.Active) { "expected a selection, got $sel" }
        assertEquals("Rectangular Marquee", vm.state.value.history.entries.last().label)
        compose.mainClock.autoAdvance = false // freeze the ants for a stable image
        compose.onRoot().captureRoboImage("src/test/snapshots/editor_marquee_selection.png")
    }

    /** A Move drag creates one history step. */
    @Test
    fun moveDragIsOneStep() {
        val vm = liveEditor()
        vm.newLayer()
        pickTool("Move")
        val steps = vm.state.value.history.entries.size
        compose.onRoot().performTouchInput {
            down(androidx.compose.ui.geometry.Offset(560f, 400f))
            moveTo(androidx.compose.ui.geometry.Offset(600f, 420f))
            moveTo(androidx.compose.ui.geometry.Offset(640f, 440f))
            up()
        }
        compose.waitForIdle()
        assertEquals(steps + 1, vm.state.value.history.entries.size)
        assertEquals("Move", vm.state.value.history.entries.last().label)
    }

    /** A canvas arrow key press and release create one Move history step. */
    @Test
    fun moveArrowKeyBurstIsOneHistoryStep() {
        val vm = liveEditor()
        vm.newLayer()
        pickTool("Move")
        val steps = vm.state.value.history.entries.size
        compose.onRoot().performTouchInput {
            down(androidx.compose.ui.geometry.Offset(560f, 400f))
            up()
        }
        compose.onRoot().performKeyInput {
            pressKey(Key.DirectionRight)
        }
        compose.waitForIdle()
        assertEquals(steps + 1, vm.state.value.history.entries.size)
        assertEquals("Move", vm.state.value.history.entries.last().label)
    }

    /** Crop tool box with thirds and handles; the Crop button crops through the engine. */
    @Test
    fun cropToolCrops() {
        val vm = liveEditor()
        pickTool("Crop")
        compose.onRoot().captureRoboImage("src/test/snapshots/editor_crop_tool.png")
        // Drag the bottom-right handle inward, then commit.
        val vp = vm.state.value
        compose.onNode(
            androidx.compose.ui.test.hasText("1 : 1 (Square)", substring = false).not() and androidx.compose.ui.test.hasText("Crop") and
                androidx.compose.ui.test.hasClickAction(),
        ).performClick()
        compose.waitForIdle()
        assertEquals("Crop", vm.state.value.history.entries.last().label)
    }

    /** Ctrl+T Free Transform — corner drag, Enter applies as one "Free Transform" step. */
    @Test
    fun freeTransformApplies() {
        val dispatcher = ActionDispatcher(ShortcutManager())
        val vm = EditorViewModel(
            FakeEngine(),
            NoRecents,
            TempShareCache,
            app.croptop.core.agent.OpenDocuments(FakeEngine()),
            { null },
            EditorRoute(newSpec = NewDocumentSpec(name = "T", size = DocSize(4000, 3000))),
        )
        compose.setContent {
            CompositionLocalProvider(LocalActionDispatcher provides dispatcher) {
                CropTopTheme(theme = ThemePreference.Dark, density = AeDensity.Pointer) {
                    EditorContent(vm, EditorRoute(), onClose = {}, layout = WorkspaceLayout.Essentials)
                }
            }
        }
        compose.waitForIdle()
        vm.newLayer()
        compose.waitForIdle()
        compose.runOnIdle { dispatcher.perform(app.croptop.core.input.ActionId("edit.freeTransform")) }
        compose.waitForIdle()
        compose.onNode(androidx.compose.ui.test.hasText("Apply") and androidx.compose.ui.test.hasClickAction()).assertExists()
        compose.onRoot().captureRoboImage("src/test/snapshots/editor_free_transform.png")
        // Drag the canvas' bottom-right corner handle inward (view coords of the fitted 4000×3000 canvas).
        compose.onRoot().performTouchInput {
            down(androidx.compose.ui.geometry.Offset(932f, 750f))
            moveTo(androidx.compose.ui.geometry.Offset(800f, 650f))
            up()
        }
        compose.onNode(androidx.compose.ui.test.hasText("Apply") and androidx.compose.ui.test.hasClickAction()).performClick()
        compose.waitForIdle()
        assertEquals("Free Transform", vm.state.value.history.entries.last().label)
    }

    /** an adjustment layer's Properties panel is generated from its spec. */
    @Test
    @Config(qualifiers = "w1280dp-h900dp-mdpi")
    fun adjustmentLayerProperties() {
        val vm = liveEditor()
        vm.addAdjustmentLayer("blackWhite")
        vm.editAdjustment(vm.state.value.primary!!, mapOf("tint" to app.croptop.core.model.ParamValue.Bool(true)))
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/snapshots/editor_adjustment_properties.png")
    }

    /** a Brush drag streams a stroke into the engine (one "Brush Tool" step) with a live preview. */
    @Test
    fun brushStrokePaints() {
        val vm = liveEditor()
        vm.newLayer()
        pickTool("Brush")
        compose.onRoot().performTouchInput {
            down(androidx.compose.ui.geometry.Offset(300f, 300f))
            listOf(360f to 330f, 430f to 380f, 520f to 400f, 610f to 380f, 700f to 330f).forEach { (x, y) ->
                moveTo(androidx.compose.ui.geometry.Offset(x, y))
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/snapshots/editor_brush_stroke.png")
        compose.onRoot().performTouchInput { up() }
        compose.waitForIdle()
        assertEquals("Brush Tool", vm.state.value.history.entries.last().label)
    }
}
