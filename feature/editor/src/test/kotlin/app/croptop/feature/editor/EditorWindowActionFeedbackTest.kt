package app.croptop.feature.editor

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
import app.croptop.core.agent.OpenDocuments
import app.croptop.core.designsystem.theme.AeDensity
import app.croptop.core.designsystem.theme.CropTopTheme
import app.croptop.core.designsystem.theme.ThemePreference
import app.croptop.core.engine.api.RejectionCode
import app.croptop.core.engine.fake.FakeEngine
import app.croptop.core.input.ActionDispatcher
import app.croptop.core.input.LocalActionDispatcher
import app.croptop.core.input.ShortcutManager
import app.croptop.core.model.DocSize
import app.croptop.core.model.DockPanel
import app.croptop.core.model.LayerKind
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.RgbaColor
import app.croptop.core.model.WorkspaceLayout
import app.croptop.feature.editor.session.ActionFeedbackPhase
import app.croptop.feature.editor.session.EditorViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-mdpi")
class EditorWindowActionFeedbackTest {
    @get:Rule
    val compose = createComposeRule()
    private lateinit var actionDispatcher: ActionDispatcher

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun unavailableNewWindowActionIsEnabledAndExplainsUnsupportedState() {
        val vm = showEditor()

        openDocumentMenu()
        compose.onNodeWithText("Move to New Window").assertIsEnabled().performClick()
        compose.waitForIdle()

        assertEquals(ActionFeedbackPhase.REJECTED, vm.state.value.actionFeedback?.phase)
        assertEquals(RejectionCode.UNSUPPORTED, vm.state.value.actionFeedback?.rejectionCode)
        compose.onNodeWithText("Opening a document in a new window is unavailable here").assertExists()
    }

    @Test
    fun typeToolReportsTextEntryOpenAndCancel() {
        val vm = showEditor()

        compose.onNodeWithContentDescription("Text").performClick()
        compose.onAllNodes(isRoot()).get(0).performTouchInput { click(center) }
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Text content").assertExists()
        assertEquals("Place text", vm.state.value.actionFeedback?.action)

        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()

        assertEquals(ActionFeedbackPhase.COMPLETED, vm.state.value.actionFeedback?.phase)
        assertEquals("Cancel text entry", vm.state.value.actionFeedback?.action)
    }

    @Test
    fun documentTabStripSwitchesAndClosesDocumentsIndependently() {
        val current = EditorRoute()
        val child = EditorRoute(openDocumentId = 42L, smartObjectParentDocumentId = 7L, smartObjectParentLayerId = 3L)
        var selected: EditorRoute? = null
        var closed: EditorRoute? = null
        showEditor(
            documentTabs = listOf(
                EditorDocumentTab(current, "Parent document", dirty = true),
                EditorDocumentTab(child, "Contents.psb", dirty = false),
            ),
            onSelectDocumentTab = { selected = it },
            onCloseDocumentTab = { closed = it },
        )

        compose.onNodeWithText("Parent document").assertExists()
        compose.onNodeWithText("Contents.psb").performClick()
        assertEquals(child, selected)
        compose.onNodeWithContentDescription("Close Contents.psb").performClick()
        assertEquals(child, closed)
        compose.onNodeWithContentDescription("Close Parent document").performClick()
        assertEquals(current, closed)
    }

    @Test
    fun unsavedNewWindowActionReportsInvalidArgument() {
        val vm = showEditor(onOpenInNewWindow = {})
        vm.newLayer()
        compose.waitForIdle()

        openDocumentMenu()
        compose.onNodeWithText("Move to New Window").assertIsEnabled().performClick()
        compose.waitForIdle()

        assertEquals(ActionFeedbackPhase.REJECTED, vm.state.value.actionFeedback?.phase)
        assertEquals(RejectionCode.INVALID_ARGUMENT, vm.state.value.actionFeedback?.rejectionCode)
        compose.onNodeWithText("Save the document first to move it to a new window").assertExists()
    }

    @Test
    fun saveAndLoadSelectionMenuItemsSaveAndRestoreChannels() {
        val vm = showEditor()

        compose.onNodeWithText("Select").performClick()
        compose.onNodeWithText("Save Selection…").assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals(ActionFeedbackPhase.REJECTED, vm.state.value.actionFeedback?.phase)
        assertEquals(RejectionCode.INVALID_ARGUMENT, vm.state.value.actionFeedback?.rejectionCode)
        compose.onNodeWithText("Create a selection before saving it.").assertExists()

        compose.onNodeWithText("Select").performClick()
        compose.onNodeWithText("Load Selection…").assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals(ActionFeedbackPhase.REJECTED, vm.state.value.actionFeedback?.phase)
        assertEquals(RejectionCode.INVALID_ARGUMENT, vm.state.value.actionFeedback?.rejectionCode)
        compose.onNodeWithText("Save a selection first.").assertExists()

        vm.selectAll()
        compose.onNodeWithText("Select").performClick()
        compose.onNodeWithText("Save Selection…").assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals(listOf("Alpha 1"), vm.document.value?.selectionChannels?.value?.map { it.name })
        assertEquals(ActionFeedbackPhase.COMPLETED, vm.state.value.actionFeedback?.phase)

        vm.deselect()
        compose.onNodeWithText("Select").performClick()
        compose.onNodeWithText("Load Selection…").assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals(true, vm.state.value.hasSelection)
    }

    @Test
    fun selectAllDeselectReselectAndInverseMenuCommandsKeepSelectionStateInSync() {
        val vm = showEditor()

        compose.onNodeWithText("Select").performClick()
        compose.onNodeWithText("Select All").performClick()
        compose.waitForIdle()
        assertTrue(vm.state.value.hasSelection)

        compose.onNodeWithText("Select").performClick()
        compose.onNodeWithText("Deselect").performClick()
        compose.waitForIdle()
        assertEquals(false, vm.state.value.hasSelection)

        compose.onNodeWithText("Select").performClick()
        compose.onNodeWithText("Reselect").performClick()
        compose.waitForIdle()
        assertTrue(vm.state.value.hasSelection)

        compose.onNodeWithText("Select").performClick()
        compose.onNodeWithText("Inverse").performClick()
        compose.waitForIdle()
        assertEquals(false, vm.state.value.hasSelection)
    }

    @Test
    fun documentedSelectionShortcutsReachTheEditorActions() {
        val vm = showEditor()

        assertTrue(dispatchShortcut(AndroidKeyEvent.KEYCODE_A, AndroidKeyEvent.META_CTRL_ON))
        compose.waitForIdle()
        assertTrue(vm.state.value.hasSelection)

        assertTrue(dispatchShortcut(AndroidKeyEvent.KEYCODE_D, AndroidKeyEvent.META_CTRL_ON))
        compose.waitForIdle()
        assertEquals(false, vm.state.value.hasSelection)

        assertTrue(
            dispatchShortcut(AndroidKeyEvent.KEYCODE_D, AndroidKeyEvent.META_CTRL_ON or AndroidKeyEvent.META_SHIFT_ON),
        )
        compose.waitForIdle()
        assertTrue(vm.state.value.hasSelection)

        assertTrue(
            dispatchShortcut(AndroidKeyEvent.KEYCODE_I, AndroidKeyEvent.META_CTRL_ON or AndroidKeyEvent.META_SHIFT_ON),
        )
        compose.waitForIdle()
        assertEquals(false, vm.state.value.hasSelection)

        assertTrue(dispatchShortcut(AndroidKeyEvent.KEYCODE_A, AndroidKeyEvent.META_CTRL_ON))
        compose.waitForIdle()
        assertTrue(dispatchShortcut(AndroidKeyEvent.KEYCODE_F6, AndroidKeyEvent.META_SHIFT_ON))
        compose.waitForIdle()
        compose.onNodeWithText("Feather Radius").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
    }

    @Test
    fun selectionModifyDialogsCancelWithoutEditingAndCommitEachOperationOnce() {
        val vm = showEditor()
        vm.selectAll()
        val initialSelection = vm.state.value.selection
        val initialHistorySize = vm.state.value.history.entries.size

        compose.onNodeWithText("Select").performClick()
        compose.onNodeWithText("Expand…").performClick()
        compose.onNodeWithText("Expand Selection").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        assertEquals(initialSelection, vm.state.value.selection)
        assertEquals(initialHistorySize, vm.state.value.history.entries.size)

        val operations = listOf(
            "Expand…" to "Expand",
            "Contract…" to "Contract",
            "Border…" to "Border",
            "Smooth…" to "Smooth",
            "Feather…" to "Feather",
        )
        operations.forEach { (menuLabel, historyLabel) ->
            val historySize = vm.state.value.history.entries.size
            compose.onNodeWithText("Select").performClick()
            compose.onNodeWithText(menuLabel).performClick()
            compose.onNodeWithText("OK").performClick()
            compose.waitForIdle()
            assertEquals(historySize + 1, vm.state.value.history.entries.size, "$menuLabel should commit once")
            assertEquals(historyLabel, vm.state.value.history.entries.last().label)
        }
    }

    @Test
    fun colorRangeMenuPreviewCancelAndCommitBehaveAsOneSelectionWorkflow() {
        val vm = showEditor()
        compose.waitForIdle()
        assertEquals(false, vm.state.value.hasSelection)

        compose.onNodeWithText("Select").performClick()
        compose.onNodeWithText("Color Range…").assertIsEnabled().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Fuzziness").assertExists()
        compose.onNodeWithText("Click the image to sample · Shift adds · Alt removes").assertExists()

        compose.onNodeWithText("Invert").performClick()
        compose.waitForIdle()
        assertTrue(vm.state.value.hasSelection)

        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        assertEquals(false, vm.state.value.hasSelection)

        compose.onNodeWithText("Select").performClick()
        compose.onNodeWithText("Color Range…").performClick()
        compose.waitForIdle()
        compose.onAllNodes(isRoot()).get(0).performTouchInput { click(center) }
        compose.waitForIdle()
        assertTrue(vm.state.value.hasSelection)
        compose.onNodeWithText("OK").performClick()
        compose.waitForIdle()
        assertTrue(vm.state.value.hasSelection)
        assertEquals("Color Range", vm.state.value.history.undoLabel)
    }

    @Test
    fun selectAndMaskNeedsASelectionThenOpensTheWorkspace() {
        val vm = showEditor()

        compose.onNodeWithText("Select").performClick()
        compose.onNodeWithText("Select and Mask…").assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals(ActionFeedbackPhase.REJECTED, vm.state.value.actionFeedback?.phase)
        assertEquals(RejectionCode.INVALID_ARGUMENT, vm.state.value.actionFeedback?.rejectionCode)
        compose.onNodeWithText("Make a selection first.").assertExists()

        vm.selectAll()
        compose.onNodeWithText("Select").performClick()
        compose.onNodeWithText("Select and Mask…").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Smart Radius").assertExists()
    }

    @Test
    fun selectAndMaskCanvasStrokeReachesTheActiveRefineSession() {
        val vm = showEditor()
        vm.selectAll()

        compose.onNodeWithText("Select").performClick()
        compose.onNodeWithText("Select and Mask…").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Subtract").performClick()
        compose.onAllNodes(isRoot()).get(0).performTouchInput { click(center) }
        compose.waitForIdle()

        assertEquals("Select and Mask brush", vm.state.value.actionFeedback?.action)
    }

    @Test
    fun cropStraightenCanvasLineCommitsOneAngleAwareCrop() {
        val vm = showEditor()
        val historyBeforeCrop = vm.state.value.history.entries.size

        compose.onNodeWithContentDescription("Crop").performClick()
        compose.onNodeWithText("Straighten Line").performClick()
        compose.onAllNodes(isRoot()).get(0).performTouchInput {
            down(center + Offset(-80f, -20f))
            moveTo(center + Offset(80f, 20f))
            up()
        }
        compose.waitForIdle()
        compose.onAllNodesWithText("Crop").get(1).performClick()
        compose.waitForIdle()

        assertEquals(historyBeforeCrop + 1, vm.state.value.history.entries.size)
        assertEquals("Crop", vm.state.value.history.entries.last().label)
        assertEquals("Crop", vm.state.value.actionFeedback?.action)
        assertEquals(ActionFeedbackPhase.COMPLETED, vm.state.value.actionFeedback?.phase)
    }

    @Test
    fun selectAndMaskNewDocumentOutputOpensTheReturnedDocumentRoute() {
        var outputRoute: EditorRoute? = null
        val vm = showEditor(onOpenDocumentInTab = { outputRoute = it })
        vm.selectAll()

        compose.onNodeWithText("Select").performClick()
        compose.onNodeWithText("Select and Mask…").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("On White").performClick()
        compose.waitForIdle()
        compose.waitUntil { vm.state.value.actionFeedback?.action == "Set Select and Mask view" }
        assertEquals("Set Select and Mask view", vm.state.value.actionFeedback?.action)

        compose.onNodeWithText("New Document").performClick()
        compose.waitUntil { compose.onAllNodesWithText("Preview ready").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("OK").performClick()
        compose.waitForIdle()

        compose.waitUntil { outputRoute != null }
        assertEquals("Select and Mask", vm.state.value.actionFeedback?.action)
        assertTrue(outputRoute?.openDocumentId != null)
    }

    @Test
    fun selectAndMaskNewLayerOutputCreatesLayerAndCancelKeepsSelection() {
        val vm = showEditor()
        vm.newLayer(LayerKind.PIXEL)
        vm.selectAll()
        val selectedBeforeWorkspace = vm.state.value.selection
        val historyBeforeWorkspace = vm.state.value.history.entries.size

        compose.onNodeWithText("Select").performClick()
        compose.onNodeWithText("Select and Mask…").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("New Layer").performClick()
        compose.waitUntil { compose.onAllNodesWithText("Preview ready").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("OK").performClick()
        compose.waitForIdle()

        compose.waitUntil { vm.state.value.history.entries.size == historyBeforeWorkspace + 1 }
        assertEquals(historyBeforeWorkspace + 1, vm.state.value.history.entries.size)
        assertEquals("Layer via Copy", vm.state.value.history.entries.lastOrNull()?.label)
        assertTrue(vm.state.value.layers.flatten().any { it.kind == LayerKind.PIXEL && it.id != vm.state.value.primary })
        assertEquals(selectedBeforeWorkspace, vm.state.value.selection)

        val selectionAfterOutput = vm.state.value.selection
        compose.onNodeWithText("Select").performClick()
        compose.onNodeWithText("Select and Mask…").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Feather").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()

        assertEquals(selectionAfterOutput, vm.state.value.selection)
    }

    @Test
    fun selectAndMaskEscapeCancelsTheWorkspaceAndKeepsSelection() {
        val vm = showEditor()
        vm.selectAll()
        val selectionBeforeWorkspace = vm.state.value.selection

        compose.onNodeWithText("Select").performClick()
        compose.onNodeWithText("Select and Mask…").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Smart Radius").assertExists()
        compose.onAllNodes(isRoot()).get(0).performKeyInput { pressKey(Key.Escape) }
        compose.waitForIdle()

        compose.waitUntil(timeoutMillis = 5_000) {
            vm.state.value.actionFeedback?.action == "Cancel Select and Mask" &&
                compose.onAllNodesWithText("Smart Radius").fetchSemanticsNodes().isEmpty()
        }
        assertEquals(selectionBeforeWorkspace, vm.state.value.selection)
        assertEquals("Cancel Select and Mask", vm.state.value.actionFeedback?.action)
        assertTrue(compose.onAllNodesWithText("Smart Radius").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun transformSelectionMenuOpensCancelableTransformWithoutChangingSelection() {
        val vm = showEditor()
        vm.selectAll()
        val selectionBeforeTransform = vm.state.value.selection
        val historyBeforeTransform = vm.state.value.history.entries.size

        compose.onNodeWithText("Select").performClick()
        compose.onNodeWithText("Transform Selection").assertIsEnabled().performClick()
        compose.waitForIdle()

        assertEquals("Start Transform Selection", vm.state.value.actionFeedback?.action)
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()

        assertEquals(selectionBeforeTransform, vm.state.value.selection)
        assertEquals(historyBeforeTransform, vm.state.value.history.entries.size)
    }

    @Test
    fun transformSelectionRotateCommitsOneHistoryStepAndUpdatesBounds() {
        val vm = showEditor()
        vm.selectAll()
        val historyBeforeTransform = vm.state.value.history.entries.size

        compose.onNodeWithText("Select").performClick()
        compose.onNodeWithText("Transform Selection").assertIsEnabled().performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Rotate 90° clockwise").performClick()
        compose.onNodeWithText("Apply").performClick()
        compose.waitForIdle()

        assertEquals(historyBeforeTransform + 1, vm.state.value.history.entries.size)
        assertEquals("Transform Selection", vm.state.value.history.entries.last().label)
        assertEquals(
            app.croptop.core.model.DocRect(50, -50, 350, 350),
            (vm.state.value.selection as app.croptop.core.model.SelectionInfo.Active).bounds,
        )
        assertEquals("Transform Selection", vm.state.value.actionFeedback?.action)
        assertEquals(ActionFeedbackPhase.COMPLETED, vm.state.value.actionFeedback?.phase)
    }

    @Test
    fun quickMaskColorPickerUpdatesTheActiveDocumentThroughTheEditorPanel() {
        val layout = WorkspaceLayout.Essentials.update(DockPanel.CHANNELS) { it.copy(visible = true, expanded = true) }
        val vm = showEditor(layout = layout)

        compose.onNodeWithContentDescription("Quick Mask overlay color #FF0000").performClick()
        compose.onNodeWithText("Quick Mask Overlay Color").assertExists()
        compose.onNodeWithContentDescription("Hex color").performTextReplacement("33669980")
        compose.waitForIdle()

        assertEquals(RgbaColor.parseHex("33669980"), vm.document.value?.quickMaskOverlayColor?.value)
        compose.onNodeWithText("Done").performClick()
        compose.waitForIdle()
        assertEquals("Set Quick Mask color", vm.state.value.actionFeedback?.action)
    }

    @Test
    fun imageAndCanvasSizeMenuActionsOpenTheirDialogs() {
        val vm = showEditor()

        compose.onNodeWithText("Image").performClick()
        compose.onNodeWithText("Image Size…").assertIsEnabled().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Image Size").assertExists()
        assertEquals("Open Image Size", vm.state.value.actionFeedback?.action)

        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Image").performClick()
        compose.onNodeWithText("Canvas Size…").assertIsEnabled().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Canvas Size").assertExists()
        assertEquals("Open Canvas Size", vm.state.value.actionFeedback?.action)

        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Image").performClick()
        compose.onNodeWithText("Rotate 90° Clockwise").assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals(DocSize(300, 400), vm.state.value.info?.size)

        compose.onNodeWithText("Image").performClick()
        compose.onNodeWithText("Flip Canvas Horizontal").assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals("Flip canvas horizontally", vm.state.value.actionFeedback?.action)
    }

    @Test
    fun destructiveAdjustmentMenuPreviewsWithoutHistoryThenCancelOrApply() {
        val vm = showEditor()
        vm.newLayer()
        compose.waitForIdle()
        val baselineHistory = vm.state.value.history.current

        openBrightnessContrastDialog()
        chooseIncreaseContrastPreset()
        assertEquals(baselineHistory, vm.state.value.history.current, "Preset preview must not create a history entry")
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        assertEquals(baselineHistory, vm.state.value.history.current, "Cancel must restore the history baseline")
        assertEquals("Cancel Brightness/Contrast adjustment preview", vm.state.value.actionFeedback?.action)
        assertEquals(ActionFeedbackPhase.COMPLETED, vm.state.value.actionFeedback?.phase)

        openBrightnessContrastDialog()
        chooseIncreaseContrastPreset()
        assertEquals(baselineHistory, vm.state.value.history.current)
        compose.onNodeWithText("OK").performClick()
        compose.waitUntil(5_000) {
            // The history step lands before the feedback report; wait for both so the assertion is not a race.
            vm.state.value.history.current == baselineHistory + 1 &&
                vm.state.value.actionFeedback?.action == "Apply Brightness/Contrast adjustment"
        }
        assertEquals("Brightness/Contrast", vm.state.value.history.undoLabel)
        assertEquals("Apply Brightness/Contrast adjustment", vm.state.value.actionFeedback?.action)
        assertEquals(ActionFeedbackPhase.COMPLETED, vm.state.value.actionFeedback?.phase)
    }

    @Test
    fun warpModeShowsTheGridAndAnEditedMeshCommitsOneStep() {
        val vm = showEditor()
        vm.newLayer()
        compose.waitForIdle()
        val baseline = vm.state.value.history.current

        compose.runOnIdle { actionDispatcher.perform(app.croptop.core.input.ActionId("edit.warp")) }
        compose.waitForIdle()
        compose.onNodeWithText("Reset warp").assertExists()
        compose.onNodeWithText("Apply").performClick()
        compose.waitForIdle()
        assertEquals(baseline, vm.state.value.history.current, "an untouched warp grid applies nothing")

        val layer = checkNotNull(vm.state.value.primary)
        val size = checkNotNull(vm.state.value.info).size
        val identity = app.croptop.core.model.WarpMesh.identity(size.width, size.height, 3, 3)
        val bent = identity.copy(nodes = identity.nodes.mapIndexed { i, p -> if (i == 4) app.croptop.core.model.DocPoint(p.x + 30f, p.y) else p })
        assertTrue(vm.warpLayer(layer, bent))
        assertEquals(baseline + 1, vm.state.value.history.current)
    }

    @Test
    fun smartFilterMaskMenuAddsMasksAndPaintToolTakesOverTheCanvas() {
        val vm = showEditor()
        vm.newLayer()
        compose.waitForIdle()
        val layer = checkNotNull(vm.state.value.primary)
        assertTrue(vm.convertToSmartObject())
        compose.waitForIdle()
        assertTrue(vm.addSmartFilterMask(layer, app.croptop.core.engine.api.MaskFill.REVEAL_ALL))
        assertTrue(vm.state.value.layers.find(layer)?.smartObject?.filterMask != null)
        vm.startFilterMaskPaint(layer)
        compose.waitForIdle()
        compose.onNodeWithText("Filter mask").assertExists()
        compose.onNodeWithText("Done").performClick()
        compose.waitForIdle()
        assertEquals(null, vm.filterMaskPaintTarget.value)
        assertTrue(vm.deleteSmartFilterMask(layer))
        assertEquals(null, vm.state.value.layers.find(layer)?.smartObject?.filterMask)
    }

    private fun openBrightnessContrastDialog() {
        compose.onNodeWithText("Image").performClick()
        compose.onNodeWithText("Adjustments…").performClick()
        compose.onNodeWithText("Brightness/Contrast").performClick()
        compose.waitForIdle()
    }

    private fun chooseIncreaseContrastPreset() {
        compose.onNodeWithText("Default").performClick()
        compose.onNodeWithText("Increase Contrast").performClick()
        compose.waitForIdle()
    }

    private fun showEditor(
        layout: WorkspaceLayout = WorkspaceLayout.Essentials,
        onOpenInNewWindow: ((String) -> Unit)? = null,
        onOpenDocumentInTab: ((EditorRoute) -> Unit)? = null,
        documentTabs: List<EditorDocumentTab> = emptyList(),
        onSelectDocumentTab: (EditorRoute) -> Unit = {},
        onCloseDocumentTab: (EditorRoute) -> Unit = {},
    ): EditorViewModel {
        val engine = FakeEngine()
        val vm = EditorViewModel(
            engine,
            NoRecents,
            TempShareCache,
            OpenDocuments(engine),
            { null },
            EditorRoute(newSpec = NewDocumentSpec(name = "Move action", size = DocSize(400, 300))),
        )
        actionDispatcher = ActionDispatcher(ShortcutManager())
        compose.setContent {
            CompositionLocalProvider(LocalActionDispatcher provides actionDispatcher) {
                CropTopTheme(theme = ThemePreference.Dark, density = AeDensity.Pointer) {
                    EditorContent(
                        vm,
                        EditorRoute(),
                        onClose = {},
                        layout = layout,
                        onOpenInNewWindow = onOpenInNewWindow,
                        onOpenDocumentInTab = onOpenDocumentInTab,
                        documentTabs = documentTabs,
                        onSelectDocumentTab = onSelectDocumentTab,
                        onCloseDocumentTab = onCloseDocumentTab,
                    )
                }
            }
        }
        compose.waitForIdle()
        return vm
    }

    private fun dispatchShortcut(keyCode: Int, metaState: Int): Boolean = actionDispatcher.dispatch(
        AndroidKeyEvent(0, 0, AndroidKeyEvent.ACTION_DOWN, keyCode, 0, metaState),
    )

    private fun openDocumentMenu() {
        compose.onNodeWithText("Move action").performMouseInput { rightClick() }
        compose.waitForIdle()
        compose.onNodeWithText("Move to New Window").assertExists()
    }
}
