package app.croptop.feature.editor

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
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
import app.croptop.core.model.AdjustmentInfo
import app.croptop.core.model.BlendMode
import app.croptop.core.model.DocSize
import app.croptop.core.model.FillKind
import app.croptop.core.model.FilterCatalog
import app.croptop.core.model.LayerId
import app.croptop.core.model.LayerKind
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.ParamValue
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
class LayerMaskActionFeedbackTest {
    @get:Rule
    val compose = createComposeRule()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun selectionMaskMenuActionsExplainMissingSelection() {
        val vm = showEditor()

        listOf("Layer Mask: Reveal Selection", "Layer Mask: Hide Selection").forEach { action ->
            compose.onNodeWithText("Layer").performClick()
            compose.waitForIdle()
            compose.onNodeWithText(action).assertIsEnabled().performClick()
            compose.waitForIdle()

            assertEquals(ActionFeedbackPhase.REJECTED, vm.state.value.actionFeedback?.phase)
            assertEquals(RejectionCode.INVALID_ARGUMENT, vm.state.value.actionFeedback?.rejectionCode)
            assertEquals(action, vm.state.value.actionFeedback?.action)
            compose.onNodeWithText("Create a selection before adding a mask from it").assertExists()
        }
    }

    @Test
    fun layerViaCutWithoutSelectionRemainsReachableAndReportsEngineResult() {
        val vm = showEditor()

        compose.onNodeWithText("Layer").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Layer via Cut").assertIsEnabled().performClick()
        compose.waitForIdle()

        assertEquals(ActionFeedbackPhase.COMPLETED, vm.state.value.actionFeedback?.phase)
        assertEquals("Layer via cut", vm.state.value.actionFeedback?.action)
        assertEquals("Layer via Cut", vm.state.value.history.entries.lastOrNull()?.label)
    }

    @Test
    fun ungroupWithoutGroupRemainsReachableAndExplainsPrecondition() {
        val vm = showEditor()

        compose.onNodeWithText("Layer").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Ungroup Layers").assertIsEnabled().performClick()
        compose.waitForIdle()

        assertEquals(ActionFeedbackPhase.REJECTED, vm.state.value.actionFeedback?.phase)
        assertEquals(RejectionCode.INVALID_ARGUMENT, vm.state.value.actionFeedback?.rejectionCode)
        assertEquals("Ungroup", vm.state.value.actionFeedback?.action)
        compose.onNodeWithText("Select a group to ungroup").assertExists()
    }

    @Test
    fun smartObjectMenuActionsTrackSelectedLayerKind() {
        val vm = showEditor()
        vm.newLayer(LayerKind.PIXEL)
        val pixelId = checkNotNull(vm.state.value.primary)

        compose.onNodeWithText("Layer").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Convert to Smart Object").assertIsEnabled().performClick()
        compose.waitForIdle()

        assertEquals(LayerKind.SMART_OBJECT, vm.state.value.layers.find(pixelId)?.kind)
        compose.onNodeWithText("Smart object layer").assertExists()
        assertEquals("Convert to Smart Object", vm.state.value.history.entries.lastOrNull()?.label)
        compose.onNodeWithText("Layer").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Convert to Smart Object").assertIsNotEnabled()
        compose.onNodeWithText("Rasterize Smart Object").assertIsEnabled().performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals(LayerKind.PIXEL, vm.state.value.layers.find(pixelId)?.kind)
        assertEquals("Rasterize Layer", vm.state.value.history.entries.lastOrNull()?.label)
    }

    @Test
    fun smartObjectRowMenuOffersEditAndReplaceContents() {
        val vm = showEditor()
        vm.newLayer(LayerKind.PIXEL)
        val layerId = checkNotNull(vm.state.value.primary)
        assertTrue(vm.convertToSmartObject())

        val layerName = vm.state.value.layers.find(layerId)?.name ?: error("missing layer")
        compose.onNodeWithContentDescription("$layerName, visible").performMouseInput { rightClick() }
        compose.waitForIdle()

        compose.onNodeWithText("Edit Contents in New Tab").assertExists()
        compose.onNodeWithText("Replace Contents…").assertExists()
    }

    @Test
    fun editSmartObjectContentsOpensAChildEditorRoute() {
        var childRoute: app.croptop.feature.editor.EditorRoute? = null
        val vm = showEditor { childRoute = it }
        vm.newLayer(LayerKind.PIXEL)
        val layerId = checkNotNull(vm.state.value.primary)
        assertTrue(vm.convertToSmartObject())

        val layerName = vm.state.value.layers.find(layerId)?.name ?: error("missing layer")
        compose.onNodeWithContentDescription("$layerName, visible").performMouseInput { rightClick() }
        compose.waitForIdle()
        compose.onNodeWithText("Edit Contents in New Tab").performClick()
        compose.waitForIdle()

        val route = checkNotNull(childRoute)
        assertEquals(vm.document.value?.id?.value, route.smartObjectParentDocumentId)
        assertEquals(layerId.value, route.smartObjectParentLayerId)
        assertTrue(checkNotNull(route.openDocumentId) != vm.document.value?.id?.value)
    }

    @Test
    fun childEditorFileSavePushesContentsBackAndClosesChildRoute() {
        val engine = FakeEngine()
        val documents = OpenDocuments(engine)
        val parent = EditorViewModel(
            engine,
            NoRecents,
            TempShareCache,
            documents,
            { null },
            EditorRoute(newSpec = NewDocumentSpec(name = "Smart Object Parent", size = DocSize(64, 64))),
        )
        parent.newLayer(LayerKind.PIXEL)
        val layerId = checkNotNull(parent.state.value.primary)
        assertTrue(parent.convertToSmartObject())
        val previousSource = checkNotNull(parent.state.value.layers.find(layerId)?.smartObject?.sourceKey)
        val childRoute = checkNotNull(parent.openSmartObjectContents(layerId))
        val child = EditorViewModel(engine, NoRecents, TempShareCache, documents, { null }, childRoute)
        child.newLayer(LayerKind.PIXEL)
        var childClosed = false

        compose.setContent {
            CompositionLocalProvider(LocalActionDispatcher provides remember { ActionDispatcher(ShortcutManager()) }) {
                CropTopTheme(theme = ThemePreference.Dark, density = AeDensity.Pointer) {
                    EditorContent(child, childRoute, onClose = { childClosed = true })
                }
            }
        }
        compose.onNodeWithText("File").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()

        assertTrue(childClosed)
        assertEquals("Edit Smart Object Contents", parent.state.value.history.entries.last().label)
        val updatedSource = checkNotNull(parent.state.value.layers.find(layerId)?.smartObject?.sourceKey)
        assertTrue(updatedSource != previousSource)
    }

    @Test
    fun replacementSourceFileSavePushesContentsBackAndClosesChildRoute() {
        val engine = FakeEngine()
        val documents = OpenDocuments(engine)
        val parent = EditorViewModel(
            engine,
            NoRecents,
            TempShareCache,
            documents,
            { null },
            EditorRoute(newSpec = NewDocumentSpec(name = "Replacement Parent", size = DocSize(64, 64))),
        )
        parent.newLayer(LayerKind.PIXEL)
        val layerId = checkNotNull(parent.state.value.primary)
        assertTrue(parent.convertToSmartObject())
        val previousSource = checkNotNull(parent.state.value.layers.find(layerId)?.smartObject?.sourceKey)
        val childRoute = checkNotNull(parent.smartObjectReplacementRoute(layerId, "content://replacement/source.png"))
        val child = EditorViewModel(engine, NoRecents, TempShareCache, documents, { null }, childRoute)
        child.newLayer(LayerKind.PIXEL)
        var childClosed = false

        compose.setContent {
            CompositionLocalProvider(LocalActionDispatcher provides ActionDispatcher(ShortcutManager())) {
                CropTopTheme(theme = ThemePreference.Dark, density = AeDensity.Pointer) {
                    EditorContent(child, childRoute, onClose = { childClosed = true })
                }
            }
        }
        compose.onNodeWithText("File").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()

        assertTrue(childClosed)
        assertEquals("Edit Smart Object Contents", parent.state.value.history.entries.last().label)
        val updatedSource = checkNotNull(parent.state.value.layers.find(layerId)?.smartObject?.sourceKey)
        assertTrue(updatedSource != previousSource)
    }

    @Test
    fun newGradientFillMenuOpensEditorAndCreatesOneLayer() {
        val vm = showEditor()
        compose.onNodeWithText("Layer").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("New Fill Layer: Gradient").assertIsEnabled().performClick()
        compose.waitForIdle()

        compose.onNodeWithText("New Gradient Fill Layer").assertExists()
        compose.onNodeWithContentDescription("Gradient preview").assertExists()
        compose.onNodeWithText("Apply").performClick()
        compose.waitForIdle()

        val fill = vm.state.value.layers.flatten().single { it.kind == LayerKind.FILL }
        assertEquals(FillKind.GRADIENT, fill.fill?.kind)
        assertEquals("New Fill Layer", vm.state.value.history.entries.lastOrNull()?.label)
    }

    @Test
    fun fillRowMenuEditsFillContentsAsOneHistoryStep() {
        val vm = showEditor()
        val initial = vm.newFillLayerInfo(FillKind.SOLID)
        assertTrue(vm.addFillLayer(initial))
        val fill = vm.state.value.layers.flatten().single { it.kind == LayerKind.FILL }
        val fillId: LayerId = fill.id

        compose.onNodeWithContentDescription("${fill.name}, visible").performMouseInput { rightClick() }
        compose.waitForIdle()
        compose.onNodeWithText("Edit Fill Contents…").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Edit Solid Color Fill").assertExists()
        compose.onNodeWithContentDescription("Saturation and brightness").assertExists()
        compose.onNodeWithText("Apply").performClick()
        compose.waitForIdle()

        assertEquals(initial, vm.state.value.layers.find(fillId)?.fill)
        assertEquals("Edit Fill Layer", vm.state.value.history.entries.lastOrNull()?.label)
    }

    @Test
    fun filterMenuOpensCatalogWithEveryEngineFilter() {
        showEditor()
        compose.onNodeWithText("Filter").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Filters…").assertIsEnabled().performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Filters").assertExists()
        FilterCatalog.All.forEach { spec -> compose.onNodeWithText(spec.name).assertExists() }
    }

    @Test
    fun smartFilterStackReordersAndRemovesFilters() {
        val vm = showEditor()
        vm.newLayer(LayerKind.PIXEL)
        val layerId = checkNotNull(vm.state.value.primary)
        assertTrue(vm.convertToSmartObject())
        val stack = listOf(AdjustmentInfo("filter.gaussianBlur"), AdjustmentInfo("filter.unsharpMask"))
        assertTrue(vm.setSmartFilters(layerId, stack))
        assertEquals(stack, vm.state.value.layers.find(layerId)?.smartObject?.filters)

        val reordered = listOf(stack[1], stack[0])
        assertTrue(vm.setSmartFilters(layerId, reordered))
        assertEquals("filter.unsharpMask", vm.state.value.layers.find(layerId)?.smartObject?.filters?.first()?.specId)

        assertTrue(vm.setSmartFilters(layerId, listOf(stack[1])))
        assertEquals(listOf("filter.unsharpMask"), vm.state.value.layers.find(layerId)?.smartObject?.filters?.map { it.specId })
    }

    @Test
    fun smartFilterRowEditsParametersAndAppliesOneUpdatedStack() {
        val vm = showEditor()
        vm.newLayer(LayerKind.PIXEL)
        val layerId = checkNotNull(vm.state.value.primary)
        assertTrue(vm.convertToSmartObject())
        val initial = AdjustmentInfo("filter.gaussianBlur", mapOf("radius" to ParamValue.Num(8f)))
        assertTrue(vm.setSmartFilters(layerId, listOf(initial)))

        compose.onNodeWithText("Smart Filters").assertExists()
        compose.onNodeWithContentDescription("Edit Gaussian Blur smart filter 1").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Edit Gaussian Blur Smart Filter").assertExists()
        compose.onNodeWithContentDescription("Enable Gaussian Blur smart filter").performClick()
        compose.onAllNodesWithText("Normal").get(1).performClick()
        compose.onNodeWithText("Multiply").performClick()
        val opacitySlider = compose.onNode(
            hasContentDescription("Gaussian Blur smart filter opacity") and
                androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress),
            useUnmergedTree = true,
        )
        opacitySlider.performSemanticsAction(SemanticsActions.SetProgress) { it(42f) }
        compose.onNodeWithText("Radius").performTouchInput { doubleClick() }
        compose.onNodeWithText("Apply").performClick()
        compose.waitForIdle()

        val updated = vm.state.value.layers.find(layerId)?.smartObject?.filters?.single()
        assertEquals(ParamValue.Num(2f), updated?.values?.get("radius"))
        assertEquals(false, updated?.enabled)
        assertEquals(BlendMode.MULTIPLY, updated?.blendMode)
        assertEquals(0.42f, updated?.opacity)
        assertEquals("Smart Filters", vm.state.value.history.entries.lastOrNull()?.label)
    }

    @Test
    fun applyLayerMaskWithoutMaskRemainsReachableAndReportsEngineReason() {
        val vm = showEditor()
        vm.newLayer(LayerKind.PIXEL)
        val layerName = vm.state.value.primaryLayer?.name
        assertEquals(LayerKind.PIXEL, vm.state.value.primaryLayer?.kind)

        compose.onNodeWithText("Layer").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Layer Mask: Apply").assertIsEnabled().performClick()
        compose.waitForIdle()

        assertEquals(ActionFeedbackPhase.REJECTED, vm.state.value.actionFeedback?.phase)
        assertEquals(RejectionCode.INVALID_ARGUMENT, vm.state.value.actionFeedback?.rejectionCode)
        compose.onNodeWithText("“$layerName” has no mask").assertExists()
    }

    @Test
    fun applyLayerMaskOnNonPixelLayerRemainsReachableAndReportsEngineReason() {
        val vm = showEditor()
        vm.newLayer(LayerKind.GROUP)
        assertEquals(LayerKind.GROUP, vm.state.value.primaryLayer?.kind)
        vm.addMask()
        compose.waitForIdle()

        compose.onNodeWithText("Layer").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Layer Mask: Apply").assertIsEnabled().performClick()
        compose.waitForIdle()

        assertEquals(ActionFeedbackPhase.REJECTED, vm.state.value.actionFeedback?.phase)
        assertEquals(RejectionCode.INVALID_ARGUMENT, vm.state.value.actionFeedback?.rejectionCode)
        compose.onNodeWithText("Apply Layer Mask works on pixel layers. Delete the mask instead.").assertExists()
    }

    @Test
    fun deletingLastLayerThroughPanelActionsExplainsEnginePrecondition() {
        val vm = showEditor()
        assertEquals(1, vm.state.value.layers.size)
        val onlyLayer = vm.state.value.layers.flatten().single()
        assertTrue(vm.select(onlyLayer.id))
        val layerName = onlyLayer.name

        compose.onNodeWithContentDescription("Delete layer").assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals(ActionFeedbackPhase.REJECTED, vm.state.value.actionFeedback?.phase)
        assertEquals(RejectionCode.INVALID_ARGUMENT, vm.state.value.actionFeedback?.rejectionCode)
        compose.onNodeWithText("A document needs at least one layer").assertExists()

        compose.onNodeWithContentDescription("$layerName, visible").performMouseInput { rightClick() }
        compose.waitForIdle()
        compose.onNodeWithText("Delete Layer").assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals(ActionFeedbackPhase.REJECTED, vm.state.value.actionFeedback?.phase)
        assertEquals(RejectionCode.INVALID_ARGUMENT, vm.state.value.actionFeedback?.rejectionCode)
        compose.onNodeWithText("A document needs at least one layer").assertExists()
    }

    @Test
    fun cutWithoutSelectionRemainsReachableAndExplainsPrecondition() {
        val vm = showEditor()

        compose.onNodeWithText("Edit").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Cut").assertIsEnabled().performClick()
        compose.waitForIdle()

        assertEquals(ActionFeedbackPhase.REJECTED, vm.state.value.actionFeedback?.phase)
        assertEquals(RejectionCode.INVALID_ARGUMENT, vm.state.value.actionFeedback?.rejectionCode)
        assertEquals("Cut", vm.state.value.actionFeedback?.action)
        compose.onNodeWithText("Cut needs a selection").assertExists()
    }

    @Test
    fun unsupportedPaintOffersNewLayerWithoutChangingExistingContent() {
        val vm = showEditor()
        vm.newLayer(LayerKind.TEXT)
        val original = checkNotNull(vm.state.value.primaryLayer)
        assertEquals(null, vm.beginStroke(app.croptop.core.model.BrushSettings(), erase = false))
        compose.waitForIdle()
        compose.onNodeWithText("Add pixel layer").performClick()
        compose.waitForIdle()
        assertEquals(LayerKind.PIXEL, vm.state.value.primaryLayer?.kind)
        assertEquals(original, vm.state.value.layers.find(original.id))
        assertEquals(null, vm.canvasEditRecovery.value)
        compose.onNodeWithText("Editing pixels · ${vm.state.value.primaryLayer?.name}").assertExists()
    }

    @Test
    fun maskTargetIsVisibleAndRetouchOffersPixelTarget() {
        val vm = showEditor()
        vm.newLayer()
        assertTrue(vm.addMask())
        compose.waitForIdle()
        compose.onNodeWithText("Editing mask · ${vm.state.value.primaryLayer?.name}").assertExists()
        assertEquals(null, vm.beginRetouch(20f, 1f, app.croptop.core.engine.api.RetouchEffect.Clone(app.croptop.core.model.DocPoint(1f, 1f))))
        compose.waitForIdle()
        // The dialog and target bar each offer the same explicit action.
        compose.onNode(hasText("Edit pixels") and hasAnyAncestor(isDialog())).performClick()
        compose.waitForIdle()
        assertEquals(null, vm.maskTarget.value)
        compose.onNodeWithText("Editing pixels · ${vm.state.value.primaryLayer?.name}").assertExists()
    }

    @Test
    fun retouchRecoveryExplicitlyLeavesSelectionMaskMode() {
        val vm = showEditor()
        assertTrue(vm.toggleQuickMask())
        compose.waitForIdle()
        compose.onNodeWithText("Editing selection mask").assertExists()
        assertEquals(null, vm.beginRetouch(20f, 1f, app.croptop.core.engine.api.RetouchEffect.Clone(app.croptop.core.model.DocPoint(1f, 1f))))
        compose.waitForIdle()
        compose.onNodeWithText("Edit pixels").performClick()
        compose.waitForIdle()
        assertEquals(false, vm.document.value?.quickMask?.value)
        compose.onNodeWithText("Editing pixels · ${vm.state.value.primaryLayer?.name}").assertExists()
    }

    @Test
    fun recoveryDoesNotChangeAnotherSelectedLayer() {
        val vm = showEditor()
        val pixel = checkNotNull(vm.state.value.primary)
        vm.newLayer(LayerKind.TEXT)
        vm.beginStroke(app.croptop.core.model.BrushSettings(), erase = false)
        vm.select(pixel)
        val count = vm.state.value.layers.size
        vm.recoverCanvasEditing()
        assertEquals(count, vm.state.value.layers.size)
        assertEquals(pixel, vm.state.value.primary)
        assertEquals(null, vm.canvasEditRecovery.value)
    }

    private fun showEditor(
        onOpenSmartObjectContents: ((app.croptop.feature.editor.EditorRoute) -> Unit)? = null,
    ): EditorViewModel {
        val engine = FakeEngine()
        val vm = EditorViewModel(
            engine,
            NoRecents,
            TempShareCache,
            OpenDocuments(engine),
            { null },
            EditorRoute(newSpec = NewDocumentSpec(name = "Mask action", size = DocSize(400, 300))),
        )
        compose.setContent {
            CompositionLocalProvider(LocalActionDispatcher provides remember { ActionDispatcher(ShortcutManager()) }) {
                CropTopTheme(theme = ThemePreference.Dark, density = AeDensity.Pointer) {
                    EditorContent(vm, EditorRoute(), onClose = {}, onOpenSmartObjectContents = onOpenSmartObjectContents)
                }
            }
        }
        compose.waitForIdle()
        return vm
    }
}
