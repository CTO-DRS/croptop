package app.croptop.feature.editor.tools

import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.DocumentHandle
import app.croptop.core.engine.fake.FakeEngine
import app.croptop.core.input.Modifiers
import app.croptop.core.model.DocSize
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.ViewPoint
import app.croptop.core.model.ViewportState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiquifyWorkspaceTest {
    private class Ctx : ToolContext {
        override var viewport = ViewportState(zoom = 1f, viewWidth = 400, viewHeight = 300)
        override val document = DocSize(400, 300)

        override fun updateViewport(state: ViewportState) {
            viewport = state
        }
    }

    private fun newDocument(): DocumentHandle = FakeEngine().newDocument(NewDocumentSpec(name = "L", size = DocSize(400, 300)))

    private fun drag(tool: LiquifyToolController, ctx: Ctx, from: Float, to: Float) {
        val down = ViewPoint(from, 100f)
        fun event(phase: PointerPhase, x: Float) =
            ToolPointerEvent(phase, ViewPoint(x, 100f), ctx.viewport.viewToDoc(ViewPoint(x, 100f)), 1f, Modifiers.None, down)
        tool.onPointer(event(PointerPhase.DOWN, from), ctx)
        tool.onPointer(event(PointerPhase.MOVE, (from + to) / 2f), ctx)
        tool.onPointer(event(PointerPhase.UP, to), ctx)
    }

    private fun tool(doc: DocumentHandle, results: MutableList<Pair<String, CommandResult>> = mutableListOf()) =
        LiquifyToolController({ doc.beginLiquify(checkNotNull(doc.layers.value.primary)) }, { a, r -> results += a to r }, {})

    @Test
    fun strokesPreviewWithoutHistoryAndOkCommitsOneStep() {
        val doc = newDocument()
        val steps = doc.history.value.entries.size
        val liquify = tool(doc)
        val ctx = Ctx()
        assertFalse(liquify.active)
        drag(liquify, ctx, 50f, 150f)
        assertTrue(liquify.active)
        drag(liquify, ctx, 150f, 250f)
        assertEquals(steps, doc.history.value.entries.size, "preview strokes never enter history")
        assertTrue(liquify.commit())
        assertFalse(liquify.active)
        assertEquals(steps + 1, doc.history.value.entries.size)
    }

    @Test
    fun cancelRestoresAndLeavesHistoryUntouched() {
        val doc = newDocument()
        val steps = doc.history.value.entries.size
        val liquify = tool(doc)
        drag(liquify, Ctx(), 50f, 150f)
        liquify.cancel()
        assertFalse(liquify.active)
        assertEquals(steps, doc.history.value.entries.size)
    }

    @Test
    fun freezeBrushPaintsAMaskInTheSameSession() {
        val doc = newDocument()
        val results = mutableListOf<Pair<String, CommandResult>>()
        val liquify = tool(doc, results)
        liquify.freezeMode = LiquifyFreezeMode.FREEZE
        drag(liquify, Ctx(), 40f, 120f)
        assertTrue(liquify.active)
        assertEquals("Freeze Liquify mask", results.last().first)
        assertTrue(results.last().second is CommandResult.Accepted)
        liquify.cancel()
    }

    @Test
    fun committingWithNothingStartedIsANoOp() {
        val liquify = tool(newDocument())
        assertTrue(liquify.commit())
    }
}
