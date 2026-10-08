package app.croptop.feature.editor

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import app.croptop.core.agent.OpenDocuments
import app.croptop.core.designsystem.theme.AeDensity
import app.croptop.core.designsystem.theme.CropTopTheme
import app.croptop.core.designsystem.theme.ThemePreference
import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.fake.FakeEngine
import app.croptop.core.input.ActionDispatcher
import app.croptop.core.input.LocalActionDispatcher
import app.croptop.core.input.ShortcutManager
import app.croptop.core.model.DocRect
import app.croptop.core.model.DocRectF
import app.croptop.core.model.DocSize
import app.croptop.core.model.FillSource
import app.croptop.core.model.Guide
import app.croptop.core.model.GuideAxis
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.RgbaColor
import app.croptop.core.model.SelectionShape
import app.croptop.core.model.SnapTo
import app.croptop.core.model.ViewAids
import app.croptop.core.model.WorkspaceLayout
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
class EditorViewAidsCanvasIntegrationTest {
    @get:Rule
    val compose = createComposeRule()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun moveGestureOnCanvasUsesVisibleGuideSnapConfiguration() {
        val engine = FakeEngine()
        val openDocuments = OpenDocuments(engine)
        val vm = EditorViewModel(
            engine,
            NoRecents,
            TempShareCache,
            openDocuments,
            { null },
            EditorRoute(newSpec = NewDocumentSpec(name = "Guide snap", size = DocSize(400, 300))),
        )
        val dispatcher = ActionDispatcher(ShortcutManager())
        compose.setContent {
            CompositionLocalProvider(LocalActionDispatcher provides dispatcher) {
                CropTopTheme(theme = ThemePreference.Dark, density = AeDensity.Pointer) {
                    EditorContent(vm, EditorRoute(), onClose = {}, layout = WorkspaceLayout("Canvas only", emptyList()))
                }
            }
        }
        compose.waitForIdle()

        // Seed a real bounded layer so the post-gesture document geometry reveals whether snapping
        // happened; an empty layer has unknown bounds and cannot provide observable geometry.
        vm.newLayer()
        val document = requireNotNull(openDocuments.active.value)
        val layerId = requireNotNull(vm.state.value.primary)
        assertTrue(document.select(SelectionShape.Rect(DocRectF(160f, 110f, 240f, 190f))) is CommandResult.Accepted)
        assertTrue(vm.fill(FillSource.Solid(RgbaColor(0.8f, 0.2f, 0.1f))))
        compose.waitForIdle()
        assertEquals(DocRect(160, 110, 240, 190), vm.state.value.layers.find(layerId)?.bounds)

        // The fitted canvas is centered in the area after the 44 dp toolbar. The guide is 20
        // document pixels to the right of the layer center; the canvas drag ends within the normal
        // 8 view-pixel snapping radius, where snapping yields an exact 20-pixel translation.
        vm.viewAids.value = ViewAids(
            snap = true,
            snapTo = SnapTo(guides = true, grid = false, documentBounds = false, layers = false),
            guides = listOf(Guide(GuideAxis.VERTICAL, 220f)),
        )
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Move").performClick()
        compose.onRoot().performTouchInput {
            val canvasCenterX = width / 2f + 22f // half of the production 44 dp toolbar at mdpi
            val canvasCenterY = height / 2f
            down(Offset(canvasCenterX, canvasCenterY))
            moveTo(Offset(canvasCenterX + 42f, canvasCenterY))
            up()
        }
        compose.waitForIdle()

        assertEquals(listOf(Guide(GuideAxis.VERTICAL, 220f)), vm.viewAids.value.guides)
        assertEquals(DocRect(180, 110, 260, 190), vm.state.value.layers.find(layerId)?.bounds)
        assertEquals("Move", vm.state.value.history.undoLabel)
    }
}
