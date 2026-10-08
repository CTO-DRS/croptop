package app.croptop.feature.editor

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.TransformSession
import app.croptop.core.input.Modifiers
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocRect
import app.croptop.core.model.DocRectF
import app.croptop.core.model.DocSize
import app.croptop.core.model.GridSettings
import app.croptop.core.model.Guide
import app.croptop.core.model.GuideAxis
import app.croptop.core.model.LayerId
import app.croptop.core.model.Matrix3
import app.croptop.core.model.SnapTo
import app.croptop.core.model.ToolId
import app.croptop.core.model.ViewAids
import app.croptop.core.model.ViewPoint
import app.croptop.core.model.ViewportState
import app.croptop.feature.editor.canvas.GuideDragger
import app.croptop.feature.editor.tools.MoveTool
import app.croptop.feature.editor.tools.PointerPhase
import app.croptop.feature.editor.tools.ToolContext
import app.croptop.feature.editor.tools.ToolPointerEvent
import app.croptop.feature.editor.tools.TransformTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runs the production guide/move/transform controllers on an Android device using isolated geometry. */
@RunWith(AndroidJUnit4::class)
class ViewAidsSnappingDeviceTest {
    private val viewport = ViewportState(zoom = 1f, viewWidth = 1000, viewHeight = 800)
    private val context = object : ToolContext {
        override val viewport = this@ViewAidsSnappingDeviceTest.viewport
        override val document = DocSize(1000, 800)
        override fun updateViewport(state: ViewportState) = Unit
    }

    private fun event(phase: PointerPhase, x: Float, y: Float) = ToolPointerEvent(
        phase = phase,
        view = ViewPoint(x, y),
        doc = DocPoint(x, y),
        pressure = 1f,
        modifiers = Modifiers.None,
        downAt = ViewPoint(x, y),
    )

    @Test
    fun rulerGuideCreatesMovesToGridAndDeletesWhenDroppedOutsideCanvas() {
        var aids = ViewAids(grid = true, gridSettings = GridSettings(spacing = 100f, subdivisions = 4))
        val dragger = GuideDragger({ aids }, { aids = it }, { ToolId.MOVE }, { context.document })

        dragger.beginNew(GuideAxis.VERTICAL, ViewPoint(0f, 20f), viewport)
        dragger.drag(ViewPoint(222f, 20f), viewport, snapToTicks = false)
        dragger.end(ViewPoint(222f, 20f), viewport)
        assertEquals(listOf(Guide(GuideAxis.VERTICAL, 225f)), aids.guides)

        assertTrue(dragger.onPointer(event(PointerPhase.DOWN, 225f, 40f), context))
        dragger.onPointer(event(PointerPhase.MOVE, 250f, 40f), context)
        dragger.onPointer(event(PointerPhase.UP, 250f, 40f), context)
        assertEquals(250f, aids.guides.single().position)

        assertTrue(dragger.onPointer(event(PointerPhase.DOWN, 250f, 40f), context))
        dragger.onPointer(event(PointerPhase.UP, 250f, -1f), context)
        assertTrue(aids.guides.isEmpty())
    }

    @Test
    fun lockedGuidesRejectDragAndLeaveGuideGeometryUnchanged() {
        val guide = Guide(GuideAxis.VERTICAL, 200f)
        var aids = ViewAids(lockGuides = true, guides = listOf(guide))
        val dragger = GuideDragger({ aids }, { aids = it }, { ToolId.MOVE }, { context.document })

        assertFalse(dragger.onPointer(event(PointerPhase.DOWN, 200f, 40f), context))
        assertEquals(listOf(guide), aids.guides)
    }

    @Test
    fun moveTranslationSnapsToGuidesGridAndActiveSelectionBounds() {
        fun translate(aids: ViewAids, selection: DocRect? = null, dx: Float): Pair<Float, Float> {
            var committed: Pair<Float, Float>? = null
            val tool = MoveTool(
                layerAt = { LayerId(1) },
                selectLayer = {},
                selectedBounds = { DocRect(100, 100, 200, 200) },
                viewAids = { aids },
                commit = { moveX, moveY ->
                    committed = moveX to moveY
                    true
                },
                selectionBounds = { selection },
            )
            tool.onPointer(event(PointerPhase.DOWN, 150f, 150f), context)
            tool.onPointer(event(PointerPhase.MOVE, 150f + dx, 150f), context)
            tool.onPointer(event(PointerPhase.UP, 150f + dx, 150f), context)
            return requireNotNull(committed)
        }

        val guideAids = ViewAids(
            snapTo = SnapTo(guides = true, grid = false, documentBounds = false, layers = false),
            guides = listOf(Guide(GuideAxis.VERTICAL, 210f)),
        )
        assertEquals(10f to 0f, translate(guideAids, dx = 8f))

        val gridAids = ViewAids(
            grid = true,
            gridSettings = GridSettings(spacing = 100f, subdivisions = 4),
            snapTo = SnapTo(guides = false, grid = true, documentBounds = false, layers = false),
        )
        assertEquals(25f to 0f, translate(gridAids, dx = 18f))

        val selectionAids = ViewAids(
            snapTo = SnapTo(guides = false, grid = false, documentBounds = false, layers = true),
        )
        assertEquals(10f to 0f, translate(selectionAids, selection = DocRect(210, 100, 310, 200), dx = 8f))
    }

    @Test
    fun transformTranslationSnapsToActiveSelectionBounds() {
        val matrices = mutableListOf<Matrix3>()
        val session = object : TransformSession {
            override fun update(matrix: Matrix3) {
                matrices += matrix
            }

            override fun commit() = CommandResult.Accepted(1)

            override fun cancel() = Unit
        }
        val tool = TransformTool(session, DocRectF(100f, 100f, 600f, 600f), "Transform", { true }, {})
        tool.viewAidsForSnap = {
            ViewAids(snapTo = SnapTo(guides = false, grid = false, documentBounds = false, layers = true))
        }
        tool.selectionBoundsForSnap = { DocRect(610, 100, 710, 600) }

        tool.onPointer(event(PointerPhase.DOWN, 150f, 350f), context)
        tool.onPointer(event(PointerPhase.MOVE, 158f, 350f), context)

        assertEquals(610f, matrices.last().map(DocPoint(600f, 350f)).x, 0.01f)
        assertEquals(350f, matrices.last().map(DocPoint(600f, 350f)).y, 0.01f)
    }
}
