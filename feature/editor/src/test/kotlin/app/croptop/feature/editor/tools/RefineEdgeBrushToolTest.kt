package app.croptop.feature.editor.tools

import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.RefineEdgeBrushMode
import app.croptop.core.engine.api.RefineEdgeBrushSession
import app.croptop.core.engine.api.RefineEdgeBrushSpec
import app.croptop.core.engine.api.RefineEdgeOutput
import app.croptop.core.engine.api.RefineEdgeSession
import app.croptop.core.engine.api.RefineEdgeSettings
import app.croptop.core.engine.api.RefineEdgeViewMode
import app.croptop.core.engine.api.StrokeSampleLayout
import app.croptop.core.input.Modifiers
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocSize
import app.croptop.core.model.ToolId
import app.croptop.core.model.ViewPoint
import app.croptop.core.model.ViewportState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class RefineEdgeBrushToolTest {
    @Test
    fun `canvas down move up sends document samples and finishes once`() {
        val refine = RecordingRefineSession()
        val results = mutableListOf<CommandResult>()
        val tool = RefineEdgeBrushTool(RefineEdgeBrushController(refine)) { results += it }
        tool.setSpec(RefineEdgeBrushSpec(mode = RefineEdgeBrushMode.SUBTRACT, diameter = 48f))

        assertTrue(tool.onPointer(event(PointerPhase.DOWN, 10f, 20f, 0.5f, 1_000L), context()))
        assertTrue(tool.onPointer(event(PointerPhase.MOVE, 12f, 23f, 0.8f, 1_100L), context()))
        assertTrue(tool.onPointer(event(PointerPhase.UP, 12f, 23f, 0.8f, 1_120L), context()))

        val stroke = refine.strokes.single()
        assertEquals(RefineEdgeBrushMode.SUBTRACT, stroke.spec.mode)
        assertEquals(2, stroke.samples.size)
        assertEquals(10f, stroke.samples[0][StrokeSampleLayout.X])
        assertEquals(20f, stroke.samples[0][StrokeSampleLayout.Y])
        assertEquals(0f, stroke.samples[0][StrokeSampleLayout.TIME_SECONDS])
        assertEquals(0.1f, stroke.samples[1][StrokeSampleLayout.TIME_SECONDS])
        assertEquals(1, stroke.endCount)
        assertFalse(tool.isStrokeActive)
        assertEquals(listOf<CommandResult>(CommandResult.Accepted(3L)), results)
    }

    @Test
    fun `cancel and focus loss do not commit the brush stroke`() {
        val refine = RecordingRefineSession()
        val tool = RefineEdgeBrushTool(RefineEdgeBrushController(refine))

        tool.onPointer(event(PointerPhase.DOWN, 1f, 2f), context())
        tool.onPointer(event(PointerPhase.CANCEL, 1f, 2f), context())
        tool.onPointer(event(PointerPhase.DOWN, 3f, 4f), context())
        tool.onFocusLost()

        assertEquals(2, refine.strokes.size)
        assertEquals(1, refine.strokes[0].cancelCount)
        assertEquals(1, refine.strokes[1].cancelCount)
        assertEquals(0, refine.strokes.sumOf { it.endCount })
        assertFalse(tool.isStrokeActive)
    }

    private fun event(
        phase: PointerPhase,
        x: Float,
        y: Float,
        pressure: Float = 1f,
        timeMillis: Long = 0L,
    ) = ToolPointerEvent(
        phase = phase,
        view = ViewPoint(x, y),
        doc = DocPoint(x, y),
        pressure = pressure,
        modifiers = Modifiers.None,
        downAt = ViewPoint(x, y),
        eventTimeMillis = timeMillis,
    )

    private fun context() = object : ToolContext {
        override val viewport = ViewportState()
        override val document = DocSize(100, 100)
        override fun updateViewport(state: ViewportState) = Unit
    }

    private class RecordingRefineSession : RefineEdgeSession {
        val strokes = mutableListOf<RecordingStroke>()
        private val mode = MutableStateFlow(RefineEdgeViewMode.OVERLAY)
        override val viewMode: StateFlow<RefineEdgeViewMode> get() = mode
        override fun setViewMode(mode: RefineEdgeViewMode) = CommandResult.Accepted(3L)
        override fun update(settings: RefineEdgeSettings) = CommandResult.Accepted(3L)
        override fun commit(output: RefineEdgeOutput) = CommandResult.Accepted(3L)
        override fun cancel() = Unit

        override fun beginBrushStroke(spec: RefineEdgeBrushSpec) = RecordingStroke(spec).also(strokes::add)
    }

    private class RecordingStroke(val spec: RefineEdgeBrushSpec) : RefineEdgeBrushSession {
        val samples = mutableListOf<FloatArray>()
        var endCount = 0
        var cancelCount = 0

        override fun add(samples: FloatArray) {
            this.samples += samples.copyOf()
        }

        override fun end(): CommandResult {
            endCount++
            return CommandResult.Accepted(3L)
        }

        override fun cancel() {
            cancelCount++
        }
    }
}
