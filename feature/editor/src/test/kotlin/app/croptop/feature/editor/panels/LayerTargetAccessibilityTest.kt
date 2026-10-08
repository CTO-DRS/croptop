package app.croptop.feature.editor.panels

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.theme.AeDensity
import app.croptop.core.designsystem.theme.CropTopTheme
import app.croptop.core.engine.fake.FakeEngine
import app.croptop.core.input.ActionDispatcher
import app.croptop.core.input.LocalActionDispatcher
import app.croptop.core.input.ShortcutManager
import app.croptop.core.model.DocSize
import app.croptop.core.model.LayerStyles
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.StyleColorOverlay
import app.croptop.feature.editor.EditorRoute
import app.croptop.feature.editor.NoRecents
import app.croptop.feature.editor.TempShareCache
import app.croptop.feature.editor.session.EditorViewModel
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h600dp-mdpi")
class LayerTargetAccessibilityTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun layerVisibilityAndMaskTargetsExposeAccessibleFortyDpActions() {
        val engine = FakeEngine()
        val vm = EditorViewModel(
            engine,
            NoRecents,
            TempShareCache,
            app.croptop.core.agent.OpenDocuments(engine),
            { null },
            EditorRoute(newSpec = NewDocumentSpec(size = DocSize(100, 100))),
        )
        vm.newLayer()
        val layerId = checkNotNull(vm.state.value.primary)
        vm.addMask()
        assertTrue(vm.state.value.layers.find(layerId)?.mask != null)
        vm.targetPixels(layerId)

        compose.setContent {
            val state by vm.state.collectAsState()
            CompositionLocalProvider(LocalActionDispatcher provides ActionDispatcher(ShortcutManager())) {
                CropTopTheme(density = AeDensity.Touch) {
                    Column(Modifier.width(280.dp).height(500.dp)) { LayersPanelContent(state, vm) }
                }
            }
        }

        val mask = compose.onNode(
            hasContentDescription("Layer mask") and hasClickAction(),
            useUnmergedTree = true,
        )
        assertTrue(mask.fetchSemanticsNode().size.width >= 40 && mask.fetchSemanticsNode().size.height >= 40)
        mask.performClick()
        assertEquals(layerId, vm.maskTarget.value, "feedback=${vm.state.value.actionFeedback} message=${vm.state.value.message}")

        val pixels = compose.onNode(
            hasContentDescription("Target layer pixels") and hasClickAction(),
            useUnmergedTree = true,
        )
        assertTrue(pixels.fetchSemanticsNode().size.width >= 40 && pixels.fetchSemanticsNode().size.height >= 40)
        pixels.performSemanticsAction(SemanticsActions.OnClick) { it() }
        assertEquals(
            null,
            vm.maskTarget.value,
            "pixel target semantic action should return painting to layer pixels; feedback=${vm.state.value.actionFeedback}",
        )
        vm.targetMask(layerId)
        pixels.performTouchInput {
            down(center)
            up()
        }
        assertEquals(null, vm.maskTarget.value, "pointer click on the pixel thumbnail should target pixels")

        val eye = compose.onAllNodes(hasContentDescription("Hide layer"), useUnmergedTree = false).onFirst()
        assertTrue(eye.fetchSemanticsNode().size.width >= 40 && eye.fetchSemanticsNode().size.height >= 40)
        eye.performClick()
        assertEquals(false, vm.state.value.layers.find(layerId)?.visible)

        compose.onNodeWithContentDescription("Show layer", useUnmergedTree = false).performTouchInput {
            down(center)
            up()
        }
        assertEquals(true, vm.state.value.layers.find(layerId)?.visible)
    }

    @Test
    fun enabledLayerStyleShowsFxBadgeAndAccessibleState() {
        val engine = FakeEngine()
        val vm = EditorViewModel(
            engine,
            NoRecents,
            TempShareCache,
            app.croptop.core.agent.OpenDocuments(engine),
            { null },
            EditorRoute(newSpec = NewDocumentSpec(size = DocSize(100, 100))),
        )
        vm.newLayer()
        val layerId = checkNotNull(vm.state.value.primary)
        assertTrue(vm.setLayerStyles(layerId, LayerStyles(colorOverlay = StyleColorOverlay(enabled = true))))

        compose.setContent {
            val state by vm.state.collectAsState()
            CompositionLocalProvider(LocalActionDispatcher provides ActionDispatcher(ShortcutManager())) {
                CropTopTheme(density = AeDensity.Touch) {
                    Column(Modifier.width(280.dp).height(500.dp)) { LayersPanelContent(state, vm) }
                }
            }
        }

        compose.onNodeWithText("fx", useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription("Layer styles enabled", useUnmergedTree = true).assertExists()

        val layerName = vm.state.value.layers.find(layerId)?.name.orEmpty()
        compose.onNodeWithContentDescription("$layerName, visible, layer styles enabled", useUnmergedTree = true)
            .performTouchInput { longClick() }
        compose.onNodeWithText("Layer Style…").performClick()
        compose.onNodeWithText("Layer Style · $layerName").assertExists()
    }

    @Test
    fun layerContextMenuCanToggleSoloMaskView() {
        val engine = FakeEngine()
        val vm = EditorViewModel(
            engine,
            NoRecents,
            TempShareCache,
            app.croptop.core.agent.OpenDocuments(engine),
            { null },
            EditorRoute(newSpec = NewDocumentSpec(size = DocSize(100, 100))),
        )
        vm.newLayer()
        val layerId = checkNotNull(vm.state.value.primary)
        vm.addMask()
        assertTrue(vm.state.value.layers.find(layerId)?.mask != null)

        compose.setContent {
            val state by vm.state.collectAsState()
            CompositionLocalProvider(LocalActionDispatcher provides ActionDispatcher(ShortcutManager())) {
                CropTopTheme(density = AeDensity.Pointer) {
                    Column(Modifier.width(280.dp).height(500.dp)) { LayersPanelContent(state, vm) }
                }
            }
        }

        val layerName = vm.state.value.layers.find(layerId)?.name.orEmpty()
        val layer = compose.onNodeWithContentDescription("$layerName, visible", useUnmergedTree = true)
        layer.performTouchInput { longClick() }
        compose.onNodeWithText("Toggle Solo Mask View").performClick()
        assertEquals("View Mask queued", vm.state.value.actionFeedback?.statusText)

        layer.performTouchInput { longClick() }
        compose.onNodeWithText("Toggle Solo Mask View").performClick()
        assertEquals("Show composite queued", vm.state.value.actionFeedback?.statusText)
    }
}
