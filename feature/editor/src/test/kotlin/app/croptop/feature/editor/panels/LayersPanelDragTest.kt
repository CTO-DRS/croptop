package app.croptop.feature.editor.panels

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.theme.AeDensity
import app.croptop.core.designsystem.theme.CropTopTheme
import app.croptop.core.engine.fake.FakeEngine
import app.croptop.core.input.ActionDispatcher
import app.croptop.core.input.LocalActionDispatcher
import app.croptop.core.input.ShortcutManager
import app.croptop.core.model.DocSize
import app.croptop.core.model.LayerKind
import app.croptop.core.model.NewDocumentSpec
import app.croptop.feature.editor.EditorRoute
import app.croptop.feature.editor.NoRecents
import app.croptop.feature.editor.TempShareCache
import app.croptop.feature.editor.session.EditorViewModel
import kotlin.test.assertEquals
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
class LayersPanelDragTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun dragLayerBelowAnother() {
        val vm =
            EditorViewModel(
                FakeEngine(),
                NoRecents,
                TempShareCache,
                app.croptop.core.agent.OpenDocuments(FakeEngine()),
                { null },
                EditorRoute(newSpec = NewDocumentSpec(size = DocSize(100, 100))),
            )
        vm.newLayer()
        vm.newLayer()
        compose.setContent {
            val state by vm.state.collectAsState()
            CompositionLocalProvider(LocalActionDispatcher provides ActionDispatcher(ShortcutManager())) {
                CropTopTheme(density = AeDensity.Pointer) {
                    Column(Modifier.width(280.dp).height(500.dp)) { LayersPanelContent(state, vm) }
                }
            }
        }
        val names = { vm.state.value.layers.flatten().map { it.name } }
        assertEquals(listOf("Layer 2", "Layer 1", "Background"), names())
        compose.onNodeWithText("Layer 2").performTouchInput {
            down(center)
            advanceEventTime(600)
            // After the reorder long-press, move into the lower half of "Layer 1" (rows are 48px at mdpi).
            moveBy(Offset(0f, 20f))
            moveBy(Offset(0f, 30f))
            up()
        }
        compose.waitForIdle()
        assertEquals(listOf("Layer 1", "Layer 2", "Background"), names())
    }

    @Test
    fun canceledLayerDragDoesNotCommitPendingDrop() {
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
        vm.newLayer()
        compose.setContent {
            val state by vm.state.collectAsState()
            CompositionLocalProvider(LocalActionDispatcher provides ActionDispatcher(ShortcutManager())) {
                CropTopTheme(density = AeDensity.Pointer) {
                    Column(Modifier.width(280.dp).height(500.dp)) { LayersPanelContent(state, vm) }
                }
            }
        }
        val names = { vm.state.value.layers.flatten().map { it.name } }
        assertEquals(listOf("Layer 2", "Layer 1", "Background"), names())
        compose.onNodeWithText("Layer 2").performTouchInput {
            down(center)
            advanceEventTime(600)
            moveBy(Offset(0f, 50f))
            cancel()
        }
        compose.waitForIdle()
        assertEquals(listOf("Layer 2", "Layer 1", "Background"), names())
    }

    @Test
    fun expandingAndCollapsingLayerGroupReportsEachCompletedAction() {
        val engine = FakeEngine()
        val vm = EditorViewModel(
            engine,
            NoRecents,
            TempShareCache,
            app.croptop.core.agent.OpenDocuments(engine),
            { null },
            EditorRoute(newSpec = NewDocumentSpec(size = DocSize(100, 100))),
        )
        vm.newLayer(LayerKind.GROUP)
        compose.setContent {
            val state by vm.state.collectAsState()
            CompositionLocalProvider(LocalActionDispatcher provides ActionDispatcher(ShortcutManager())) {
                CropTopTheme(density = AeDensity.Pointer) {
                    Column(Modifier.width(280.dp).height(500.dp)) { LayersPanelContent(state, vm) }
                }
            }
        }

        compose.onNodeWithContentDescription("Collapse group").performClick()
        compose.waitForIdle()
        assertEquals("Collapse Group 1", vm.state.value.actionFeedback?.action)

        compose.onNodeWithContentDescription("Expand group").performClick()
        compose.waitForIdle()
        assertEquals("Expand Group 1", vm.state.value.actionFeedback?.action)
    }

    @Test
    fun openingAdjustmentLayerMenuReportsItsAction() {
        val engine = FakeEngine()
        val vm = EditorViewModel(
            engine,
            NoRecents,
            TempShareCache,
            app.croptop.core.agent.OpenDocuments(engine),
            { null },
            EditorRoute(newSpec = NewDocumentSpec(size = DocSize(100, 100))),
        )
        compose.setContent {
            val state by vm.state.collectAsState()
            CompositionLocalProvider(LocalActionDispatcher provides ActionDispatcher(ShortcutManager())) {
                CropTopTheme(density = AeDensity.Pointer) {
                    Column(Modifier.width(280.dp).height(500.dp)) { LayersPanelContent(state, vm) }
                }
            }
        }

        compose.onNodeWithContentDescription("New adjustment layer").performClick()

        assertEquals("Open adjustment layer menu", vm.state.value.actionFeedback?.action)
    }

    @Test
    fun layerRenameOpenAndEscapeCancelReportActions() {
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
        compose.setContent {
            val state by vm.state.collectAsState()
            CompositionLocalProvider(LocalActionDispatcher provides ActionDispatcher(ShortcutManager())) {
                CropTopTheme(density = AeDensity.Pointer) {
                    Column(Modifier.width(280.dp).height(500.dp)) { LayersPanelContent(state, vm) }
                }
            }
        }

        compose.onNodeWithContentDescription("Layer 1, visible").performMouseInput { rightClick() }
        compose.onNodeWithText("Rename…").performClick()
        compose.waitForIdle()
        assertEquals("Open Rename Layer 1", vm.state.value.actionFeedback?.action)

        compose.onNodeWithText("Layer 1").performKeyInput { pressKey(Key.Escape) }
        compose.waitForIdle()
        assertEquals("Cancel Rename Layer 1", vm.state.value.actionFeedback?.action)

        compose.onNodeWithContentDescription("Layer 1, visible").performMouseInput { rightClick() }
        compose.onNodeWithText("Hide Layer").performClick()
        compose.waitForIdle()
        assertEquals("Hide Layer 1", vm.state.value.actionFeedback?.action)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h600dp-mdpi")
class LayersPanelScaleTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** Acceptance: a 200-layer document stays lazy — only visible rows are composed. */
    @Test
    fun twoHundredLayersComposeOnlyVisibleRows() {
        val vm =
            EditorViewModel(
                FakeEngine(),
                NoRecents,
                TempShareCache,
                app.croptop.core.agent.OpenDocuments(FakeEngine()),
                { null },
                EditorRoute(newSpec = NewDocumentSpec(size = DocSize(100, 100))),
            )
        repeat(200) { vm.newLayer() }
        compose.setContent {
            val state by vm.state.collectAsState()
            CompositionLocalProvider(LocalActionDispatcher provides ActionDispatcher(ShortcutManager())) {
                CropTopTheme(density = AeDensity.Pointer) {
                    Column(Modifier.width(280.dp).height(500.dp)) { LayersPanelContent(state, vm) }
                }
            }
        }
        val start = System.nanoTime()
        compose.waitForIdle()
        val composed = compose.onAllNodes(androidx.compose.ui.test.hasText("Layer ", substring = true)).fetchSemanticsNodes().size
        assertEquals(201, vm.state.value.layers.size)
        kotlin.test.assertTrue(composed in 5..20, "composed $composed rows; expected only the visible ones")
        kotlin.test.assertTrue(System.nanoTime() - start < 2_000_000_000L)
    }
}
