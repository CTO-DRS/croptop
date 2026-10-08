package app.croptop.feature.editor.tools

import app.croptop.core.input.Modifiers
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocSize
import app.croptop.core.model.ViewPoint
import app.croptop.core.model.ViewportState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FilterMaskBrushToolTest {
    private class Ctx : ToolContext {
        override var viewport = ViewportState(zoom = 1f, viewWidth = 400, viewHeight = 300)
        override val document = DocSize(400, 300)

        override fun updateViewport(state: ViewportState) {
            viewport = state
        }
    }

    private fun event(ctx: Ctx, phase: PointerPhase, x: Float, y: Float) =
        ToolPointerEvent(phase, ViewPoint(x, y), ctx.viewport.viewToDoc(ViewPoint(x, y)), 1f, Modifiers.None, ViewPoint(10f, 10f))

    @Test
    fun aGestureBecomesOneHideStrokeWithTheBrushRadius() {
        val painted = mutableListOf<Triple<List<DocPoint>, Float, Boolean>>()
        val tool = FilterMaskBrushTool({ p, r, reveal -> painted += Triple(p, r, reveal); true }, {})
        tool.size = 40f
        val ctx = Ctx()
        tool.onPointer(event(ctx, PointerPhase.DOWN, 10f, 10f), ctx)
        tool.onPointer(event(ctx, PointerPhase.MOVE, 60f, 10f), ctx)
        tool.onPointer(event(ctx, PointerPhase.UP, 120f, 10f), ctx)
        assertEquals(1, painted.size)
        val (points, radius, reveal) = painted.single()
        assertTrue(points.size >= 3)
        assertEquals(20f, radius)
        assertFalse(reveal)
    }

    @Test
    fun revealModeAndCancelledGestures() {
        val painted = mutableListOf<Boolean>()
        val tool = FilterMaskBrushTool({ _, _, reveal -> painted += reveal; true }, {})
        tool.reveal = true
        val ctx = Ctx()
        tool.onPointer(event(ctx, PointerPhase.DOWN, 10f, 10f), ctx)
        tool.onPointer(event(ctx, PointerPhase.CANCEL, 10f, 10f), ctx)
        assertEquals(emptyList(), painted)
        tool.onPointer(event(ctx, PointerPhase.DOWN, 10f, 10f), ctx)
        tool.onPointer(event(ctx, PointerPhase.UP, 10f, 10f), ctx)
        assertEquals(listOf(true), painted)
    }
}
