package app.croptop.feature.editor.tools

import app.croptop.core.input.Modifiers
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocSize
import app.croptop.core.model.ViewPoint
import app.croptop.core.model.ViewportState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AiRemoveToolTest {
    private class Ctx(override var viewport: ViewportState = ViewportState(zoom = 2f)) : ToolContext {
        override val document = DocSize(1000, 800)
        override fun updateViewport(state: ViewportState) {
            viewport = state
        }
    }

    private fun event(phase: PointerPhase, x: Float, y: Float) =
        ToolPointerEvent(phase, ViewPoint(x * 2, y * 2), DocPoint(x, y), 1f, Modifiers.None, ViewPoint(0f, 0f))

    @Test
    fun `a stroke is sent on release with the brush radius in document pixels at the current zoom`() {
        var points: List<DocPoint>? = null
        var radius = 0f
        val tool = AiRemoveTool { p, r ->
            points = p
            radius = r
            true
        }
        tool.size = 40f
        val ctx = Ctx()
        tool.onPointer(event(PointerPhase.DOWN, 100f, 100f), ctx)
        tool.onPointer(event(PointerPhase.MOVE, 101f, 100f), ctx) // closer than a quarter of the brush: dropped
        tool.onPointer(event(PointerPhase.MOVE, 120f, 100f), ctx)
        tool.onPointer(event(PointerPhase.UP, 140f, 110f), ctx)
        assertEquals(10f, radius, "40 px brush at 2× zoom is 20 doc px across")
        assertEquals(listOf(DocPoint(100f, 100f), DocPoint(120f, 100f), DocPoint(140f, 110f)), points)
    }

    @Test
    fun `a tap is a dab and a cancelled gesture removes nothing`() {
        val sent = mutableListOf<List<DocPoint>>()
        val tool = AiRemoveTool { p, _ -> sent.add(p) }
        val ctx = Ctx()
        tool.onPointer(event(PointerPhase.DOWN, 50f, 60f), ctx)
        tool.onPointer(event(PointerPhase.UP, 50f, 60f), ctx)
        assertEquals(listOf(listOf(DocPoint(50f, 60f))), sent)
        tool.onPointer(event(PointerPhase.DOWN, 10f, 10f), ctx)
        tool.onPointer(event(PointerPhase.CANCEL, 10f, 10f), ctx)
        tool.onPointer(event(PointerPhase.UP, 10f, 10f), ctx)
        assertTrue(sent.size == 1)
    }
}
