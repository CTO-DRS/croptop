package app.croptop.feature.editor

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.croptop.core.agent.OpenDocuments
import app.croptop.core.data.RecentDocumentsRepository
import app.croptop.core.designsystem.theme.AeDensity
import app.croptop.core.designsystem.theme.CropTopTheme
import app.croptop.core.engine.fake.FakeEngine
import app.croptop.core.input.ActionDispatcher
import app.croptop.core.input.LocalActionDispatcher
import app.croptop.core.input.ShortcutManager
import app.croptop.core.model.DocSize
import app.croptop.core.model.DockPanel
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.RecentDocument
import app.croptop.core.model.WorkspaceLayout
import app.croptop.feature.editor.files.ShareCache
import app.croptop.feature.editor.session.EditorViewModel
import java.io.File
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Checks keyboard traversal through the editor's real menu, toolbar and Layers panel on Android. */
@RunWith(AndroidJUnit4::class)
class EditorAccessibilityDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private fun newEditor(): Pair<EditorRoute, EditorViewModel> {
        val route = EditorRoute(newSpec = NewDocumentSpec(size = DocSize(100, 100)))
        val viewModel = EditorViewModel(
            FakeEngine(),
            object : RecentDocumentsRepository {
                override fun observe(limit: Int) = flowOf(emptyList<RecentDocument>())
                override suspend fun recordOpened(uri: String, name: String, size: DocSize?, now: Long) = Unit
                override suspend fun remove(uri: String) = Unit
                override suspend fun setPinned(uri: String, pinned: Boolean) = Unit
            },
            object : ShareCache {
                override fun newFile(name: String) = File.createTempFile("croptop", name)
                override fun uriFor(file: File) = file.toURI().toString()
            },
            OpenDocuments(FakeEngine()),
            { null },
            route,
        )
        viewModel.newLayer()
        return route to viewModel
    }

    private fun SemanticsNode.walk(): List<SemanticsNode> = listOf(this) + children.flatMap { it.walk() }

    private fun clickableProblems(): List<String> {
        val root = compose.onRoot(useUnmergedTree = false).fetchSemanticsNode()
        val minimum = with(root.layoutInfo.density) { 40.dp.toPx() }
        return root.walk().mapNotNull { node ->
            if (node.config.getOrNull(SemanticsActions.OnClick) == null) return@mapNotNull null
            val bounds = node.boundsInRoot
            if (bounds.width <= 0f || bounds.height <= 0f) return@mapNotNull null
            val clippedByScroll = generateSequence(node.parent) { it.parent }.any { ancestor ->
                if (ancestor.config.getOrNull(SemanticsActions.ScrollBy) == null) {
                    false
                } else {
                    val viewport = ancestor.boundsInRoot
                    val measured = node.size
                    bounds.width < measured.width || bounds.height < measured.height ||
                        bounds.left < viewport.left || bounds.top < viewport.top ||
                        bounds.right > viewport.right || bounds.bottom > viewport.bottom
                }
            }
            if (clippedByScroll) return@mapNotNull null
            val label = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
                ?: node.config.getOrNull(SemanticsProperties.Text)?.joinToString()
                ?: node.config.getOrNull(SemanticsActions.OnClick)?.label
            when {
                label.isNullOrBlank() -> "Unlabeled clickable at $bounds"

                bounds.width < minimum - 0.5f || bounds.height < minimum - 0.5f ->
                    "Target '$label' too small: ${bounds.width}x${bounds.height}px"

                else -> null
            }
        }
    }

    @Test
    fun histogramPanelRendersAndCanBeClosedAndReopenedFromWindowMenu() {
        val (route, viewModel) = newEditor()
        var layout = WorkspaceLayout.Essentials
        listOf(DockPanel.NAVIGATOR, DockPanel.COLOR, DockPanel.PROPERTIES, DockPanel.HISTORY, DockPanel.LAYERS)
            .forEach { panel -> layout = layout.update(panel) { it.copy(visible = false) } }
        layout = layout.update(DockPanel.HISTOGRAM) { it.copy(visible = true, expanded = true) }

        compose.setContent {
            CompositionLocalProvider(LocalActionDispatcher provides ActionDispatcher(ShortcutManager())) {
                CropTopTheme(density = AeDensity.Pointer) {
                    var currentLayout by remember { mutableStateOf(layout) }
                    EditorContent(
                        viewModel,
                        route,
                        onClose = {},
                        layout = currentLayout,
                        onLayout = { next ->
                            currentLayout = next
                            kotlinx.coroutines.CompletableDeferred(Unit)
                        },
                    )
                }
            }
        }

        compose.onNodeWithText("Source").assertExists()
        compose.onNodeWithText("No pixels in this source").assertExists()
        compose.onNodeWithText("Window").performClick()
        compose.onNodeWithText("Histogram").performClick()
        compose.onNodeWithText("Source").assertDoesNotExist()
        compose.onNodeWithText("Window").performClick()
        compose.onNodeWithText("Histogram").performClick()
        compose.onNodeWithText("Source").assertExists()
    }

    @Test
    fun editorControlsHaveNamesAndTouchTargets() {
        val (route, viewModel) = newEditor()
        compose.setContent {
            CompositionLocalProvider(LocalActionDispatcher provides ActionDispatcher(ShortcutManager())) {
                CropTopTheme(density = AeDensity.Touch) { EditorContent(viewModel, route, onClose = {}) }
            }
        }

        val problems = clickableProblems()
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun cropStraightenDragCommitsOneCropOnAndroid() {
        val (route, viewModel) = newEditor()
        compose.setContent {
            CompositionLocalProvider(LocalActionDispatcher provides ActionDispatcher(ShortcutManager())) {
                CropTopTheme(density = AeDensity.Pointer) { EditorContent(viewModel, route, onClose = {}) }
            }
        }
        val historyBeforeCrop = viewModel.state.value.history.entries.size

        compose.onNodeWithContentDescription("Crop").performClick()
        compose.onNodeWithText("Straighten Line").performClick()
        compose.onRoot().performTouchInput {
            down(center + Offset(-80f, -20f))
            moveTo(center + Offset(80f, 20f))
            up()
        }
        compose.onAllNodesWithText("Crop").get(1).performClick()

        assertEquals(historyBeforeCrop + 1, viewModel.state.value.history.entries.size)
        assertEquals("Crop", viewModel.state.value.history.entries.last().label)
    }

    @OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)
    @Test
    fun tabTraversalReachesMenuToolsAndLayersControls() {
        val (route, viewModel) = newEditor()

        compose.setContent {
            CompositionLocalProvider(LocalActionDispatcher provides ActionDispatcher(ShortcutManager())) {
                CropTopTheme(density = AeDensity.Pointer) {
                    val inputModes = LocalInputModeManager.current
                    LaunchedEffect(Unit) { inputModes.requestInputMode(InputMode.Keyboard) }
                    EditorContent(viewModel, route, onClose = {})
                }
            }
        }

        val focusedLabels = linkedSetOf<String>()
        repeat(120) {
            compose.onRoot().performKeyInput { pressKey(Key.Tab) }
            compose.onAllNodes(isFocused(), useUnmergedTree = false).fetchSemanticsNodes().forEach { node ->
                val label = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
                    ?: node.config.getOrNull(SemanticsProperties.Text)?.joinToString()
                label?.let(focusedLabels::add)
            }
        }

        assertTrue("Tab traversal skipped editor menus: $focusedLabels", "File" in focusedLabels)
        assertTrue("Tab traversal skipped tool controls: $focusedLabels", focusedLabels.any { it.startsWith("Move") || it.startsWith("Hand") })
        assertTrue(
            "Tab traversal skipped layer controls: $focusedLabels",
            focusedLabels.any { it.contains("Opacity") || it.contains("New layer", ignoreCase = true) },
        )
        listOf("Navigator", "Color", "Properties", "History", "Layers").forEach { panel ->
            assertTrue("Tab traversal skipped $panel panel actions: $focusedLabels", "$panel panel options" in focusedLabels)
        }
    }

    @OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)
    @Test
    fun layerRowKeyboardVisibilityActionUpdatesAccessibleState() {
        val (route, viewModel) = newEditor()

        compose.setContent {
            CompositionLocalProvider(LocalActionDispatcher provides ActionDispatcher(ShortcutManager())) {
                CropTopTheme(density = AeDensity.Pointer) {
                    val inputModes = LocalInputModeManager.current
                    LaunchedEffect(Unit) { inputModes.requestInputMode(InputMode.Keyboard) }
                    EditorContent(viewModel, route, onClose = {})
                }
            }
        }

        val layer = compose.onNodeWithContentDescription("Background, visible")
        var layerReceivedFocus = false
        val focusedLabels = linkedSetOf<String>()
        repeat(140) {
            if (!layerReceivedFocus) {
                compose.onRoot().performKeyInput { pressKey(Key.Tab) }
                compose.onAllNodes(isFocused(), useUnmergedTree = false).fetchSemanticsNodes().forEach { focused ->
                    val label = focused.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
                        ?: focused.config.getOrNull(SemanticsProperties.Text)?.joinToString()
                    label?.let(focusedLabels::add)
                }
                layerReceivedFocus = runCatching { layer.assertIsFocused() }.isSuccess
            }
        }
        assertTrue("Tab traversal should focus the Background row; focused nodes: $focusedLabels", layerReceivedFocus)
        layer.performKeyInput { pressKey(Key.Spacebar) }

        val hiddenLayer = compose.onNodeWithContentDescription("Background, hidden").fetchSemanticsNode()
        assertTrue(
            hiddenLayer.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains("Background, hidden"),
        )

        compose.onNodeWithContentDescription("Background, hidden").performKeyInput { pressKey(Key.Spacebar) }
        val visibleLayer = compose.onNodeWithContentDescription("Background, visible").fetchSemanticsNode()
        assertTrue(
            visibleLayer.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains("Background, visible"),
        )
    }

    @Test
    fun viewMenuTogglesGuidesAndAddsDocumentGuide() {
        val (route, viewModel) = newEditor()

        compose.setContent {
            CompositionLocalProvider(LocalActionDispatcher provides ActionDispatcher(ShortcutManager())) {
                CropTopTheme(density = AeDensity.Touch) { EditorContent(viewModel, route, onClose = {}) }
            }
        }

        compose.onNodeWithText("View", useUnmergedTree = false).performClick()
        compose.onNodeWithText("Guides", useUnmergedTree = false).performClick()
        compose.runOnIdle { assertEquals(false, viewModel.viewAids.value.showGuides) }

        compose.onNodeWithText("View", useUnmergedTree = false).performClick()
        compose.onNodeWithText("Snap", useUnmergedTree = false).performClick()
        compose.runOnIdle { assertEquals(false, viewModel.viewAids.value.snap) }

        compose.onNodeWithText("View", useUnmergedTree = false).performClick()
        compose.onNodeWithText("Lock Guides", useUnmergedTree = false).performClick()
        compose.runOnIdle { assertEquals(true, viewModel.viewAids.value.lockGuides) }

        compose.onNodeWithText("View", useUnmergedTree = false).performClick()
        compose.onNodeWithText("New Guide…", useUnmergedTree = false).performClick()
        compose.onNodeWithText("Add Guide", useUnmergedTree = false).performClick()

        compose.runOnIdle {
            assertEquals(
                listOf(app.croptop.core.model.Guide(app.croptop.core.model.GuideAxis.VERTICAL, 50f)),
                viewModel.document.value?.documentGuides?.value?.guides,
            )
            assertEquals(true, viewModel.viewAids.value.showGuides)
        }

        compose.onNodeWithText("View", useUnmergedTree = false).performClick()
        compose.onNodeWithText("New Guide Layout…", useUnmergedTree = false).performClick()
        compose.onNodeWithText("Add Guides", useUnmergedTree = false).performClick()
        compose.runOnIdle {
            assertEquals(6, viewModel.document.value?.documentGuides?.value?.guides?.size)
        }

        compose.onNodeWithText("View", useUnmergedTree = false).performClick()
        compose.onNodeWithText("Grid Settings…", useUnmergedTree = false).performClick()
        val spacing = compose.onNodeWithContentDescription("Gridline spacing")
        spacing.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        spacing.performKeyInput { pressKey(Key.DirectionUp) }
        val subdivisions = compose.onNodeWithContentDescription("Subdivisions")
        subdivisions.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        subdivisions.performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Apply", useUnmergedTree = false).performClick()
        compose.runOnIdle {
            assertEquals(101f, viewModel.viewAids.value.gridSettings.spacing)
            assertEquals(3, viewModel.viewAids.value.gridSettings.subdivisions)
            assertEquals(101f, viewModel.document.value?.documentGuides?.value?.grid?.spacing)
            assertEquals(3, viewModel.document.value?.documentGuides?.value?.grid?.subdivisions)
        }
    }

    @Test
    fun draggingFromHorizontalRulerCreatesDocumentGuide() {
        val (route, viewModel) = newEditor()

        compose.setContent {
            CompositionLocalProvider(LocalActionDispatcher provides ActionDispatcher(ShortcutManager())) {
                CropTopTheme(density = AeDensity.Touch) { EditorContent(viewModel, route, onClose = {}) }
            }
        }

        compose.onNodeWithText("View", useUnmergedTree = false).performClick()
        compose.onNodeWithText("Rulers", useUnmergedTree = false).performClick()
        val horizontalRuler = compose.onNodeWithContentDescription("Horizontal ruler")
        horizontalRuler.performTouchInput {
            val x = width / 2f
            down(Offset(x, height / 2f))
            moveTo(Offset(x, height + 80f), delayMillis = 400)
            up()
        }

        compose.runOnIdle {
            val guides = viewModel.document.value?.documentGuides?.value?.guides.orEmpty()
            assertEquals(1, guides.size)
            assertEquals(app.croptop.core.model.GuideAxis.HORIZONTAL, guides.single().axis)
        }
    }

    @OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)
    @Test
    fun imageSizeDialogTabTraversalReachesFieldsAndDismissesWithEscape() {
        val (route, viewModel) = newEditor()

        compose.setContent {
            CompositionLocalProvider(LocalActionDispatcher provides ActionDispatcher(ShortcutManager())) {
                CropTopTheme(density = AeDensity.Pointer) {
                    val inputModes = LocalInputModeManager.current
                    LaunchedEffect(Unit) { inputModes.requestInputMode(InputMode.Keyboard) }
                    EditorContent(viewModel, route, onClose = {})
                }
            }
        }

        compose.onNodeWithText("Image", useUnmergedTree = false).performClick()
        compose.onNodeWithText("Image Size…", useUnmergedTree = false).performClick()

        val dialogKeyTarget = compose.onNodeWithText("Cancel")
        val focusedLabels = linkedSetOf<String>()
        repeat(80) {
            dialogKeyTarget.performKeyInput { pressKey(Key.Tab) }
            compose.onAllNodes(isFocused(), useUnmergedTree = false).fetchSemanticsNodes().forEach { node ->
                val label = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
                    ?: node.config.getOrNull(SemanticsProperties.Text)?.joinToString()
                label?.let(focusedLabels::add)
            }
        }
        listOf("Width", "Height", "Resolution", "Cancel", "OK").forEach { control ->
            assertTrue("Image Size dialog Tab traversal skipped '$control': $focusedLabels", focusedLabels.any { control in it })
        }

        dialogKeyTarget.performKeyInput { pressKey(Key.Escape) }
        assertTrue(
            "Image Size dialog should close after Escape",
            compose.onAllNodesWithText("Image Size", useUnmergedTree = false).fetchSemanticsNodes().isEmpty(),
        )
    }

    @OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)
    @Test
    fun canvasSizeDialogTabTraversalReachesResizeOptionsAndDismissesWithEscape() {
        val (route, viewModel) = newEditor()

        compose.setContent {
            CompositionLocalProvider(LocalActionDispatcher provides ActionDispatcher(ShortcutManager())) {
                CropTopTheme(density = AeDensity.Touch) {
                    val inputModes = LocalInputModeManager.current
                    LaunchedEffect(Unit) { inputModes.requestInputMode(InputMode.Keyboard) }
                    EditorContent(viewModel, route, onClose = {})
                }
            }
        }

        compose.onNodeWithText("Image", useUnmergedTree = false).performClick()
        compose.onNodeWithText("Canvas Size…", useUnmergedTree = false).performClick()

        listOf("Anchor center", "Anchor top left").forEach { label ->
            val node = compose.onNodeWithContentDescription(label).fetchSemanticsNode()
            val minimum = with(node.layoutInfo.density) { 40.dp.toPx() }
            assertTrue("Touch target '$label' is smaller than 40 dp: ${node.size}", node.size.width >= minimum && node.size.height >= minimum)
        }

        val dialogKeyTarget = compose.onNodeWithText("Cancel")
        val focusedLabels = linkedSetOf<String>()
        repeat(140) {
            dialogKeyTarget.performKeyInput { pressKey(Key.Tab) }
            compose.onAllNodes(isFocused(), useUnmergedTree = false).fetchSemanticsNodes().forEach { node ->
                val label = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
                    ?: node.config.getOrNull(SemanticsProperties.Text)?.joinToString()
                label?.let(focusedLabels::add)
            }
        }
        listOf("Canvas width", "Canvas height", "Relative", "Anchor center", "Anchor top left", "Cancel", "OK").forEach { control ->
            assertTrue("Canvas Size dialog Tab traversal skipped '$control': $focusedLabels", focusedLabels.any { control in it })
        }

        dialogKeyTarget.performKeyInput { pressKey(Key.Escape) }
        assertTrue(
            "Canvas Size dialog should close after Escape",
            compose.onAllNodesWithText("Canvas Size", useUnmergedTree = false).fetchSemanticsNodes().isEmpty(),
        )
    }
}
