package app.croptop.feature.editor.tools

import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.QuickSelectionSession
import app.croptop.core.engine.api.QuickSelectionSpec
import app.croptop.core.engine.api.RejectionCode
import app.croptop.core.input.Modifiers
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocSize
import app.croptop.core.model.SelectionOp
import app.croptop.core.model.SelectionShape
import app.croptop.core.model.ViewPoint
import app.croptop.core.model.ViewportState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SmartSelectionToolsTest {
    private val ctx = object : ToolContext {
        override val viewport = ViewportState(zoom = 1f, viewWidth = 100, viewHeight = 100)
        override val document = DocSize(100, 100)
        override fun updateViewport(state: ViewportState) = Unit
    }

    private fun ev(phase: PointerPhase, x: Float, y: Float, modifiers: Modifiers = Modifiers.None) =
        ToolPointerEvent(phase, ViewPoint(x, y), DocPoint(x, y), 1f, modifiers, ViewPoint(x, y))

    private class RecordingSession(var result: CommandResult = CommandResult.Accepted(1)) : QuickSelectionSession {
        val points = mutableListOf<Float>()
        var ended = false
        var cancelled = false

        override fun add(points: FloatArray) {
            this.points += points.toList()
        }

        override fun end(): CommandResult {
            ended = true
            return result
        }

        override fun cancel() {
            cancelled = true
        }
    }

    @Test fun quickSelectionStreamsPointsAndSwitchesNewToAddAfterTheFirstStroke() {
        val specs = mutableListOf<QuickSelectionSpec>()
        val sessions = mutableListOf<RecordingSession>()
        val tool = QuickSelectionTool(
            begin = { spec ->
                RecordingSession().also {
                    specs += spec
                    sessions += it
                }
            },
            onResult = { it is CommandResult.Accepted },
            selectSubject = { true },
        )
        tool.size = 12f
        tool.onPointer(ev(PointerPhase.DOWN, 10f, 10f), ctx)
        tool.onPointer(ev(PointerPhase.MOVE, 20f, 15f), ctx)
        tool.onPointer(ev(PointerPhase.UP, 20f, 15f), ctx)

        assertEquals(QuickSelectionSpec(12f, SelectionOp.REPLACE), specs.single())
        assertEquals(listOf(10f, 10f, 20f, 15f), sessions.single().points)
        assertTrue(sessions.single().ended)
        assertEquals(QuickSelectMode.ADD, tool.mode)

        // Alt subtracts for one stroke without changing the mode.
        tool.onPointer(ev(PointerPhase.DOWN, 30f, 30f, Modifiers(alt = true)), ctx)
        tool.onPointer(ev(PointerPhase.UP, 30f, 30f), ctx)
        assertEquals(SelectionOp.SUBTRACT, specs.last().op)
        assertEquals(QuickSelectMode.ADD, tool.mode)
    }

    @Test fun quickSelectionCursorPreviewsModifiersAndLocksToTheCurrentStroke() {
        val session = RecordingSession()
        val specs = mutableListOf<QuickSelectionSpec>()
        val tool = QuickSelectionTool({ spec ->
            specs += spec
            session
        }, { true }, { true })

        assertEquals(SelectionOp.ADD, tool.cursorOp(Modifiers(shift = true)))
        assertEquals(SelectionOp.SUBTRACT, tool.cursorOp(Modifiers(alt = true)))
        // QuickSelectionSpec supports Add/Subtract, but not Intersect, so Alt wins when both are held.
        assertEquals(SelectionOp.SUBTRACT, tool.cursorOp(Modifiers(shift = true, alt = true)))
        tool.onPointer(ev(PointerPhase.DOWN, 10f, 10f, Modifiers(shift = true, alt = true)), ctx)
        assertEquals(SelectionOp.SUBTRACT, specs.single().op)
        // Modifier changes after pointer-down do not make the brush badge disagree with this stroke.
        assertEquals(SelectionOp.SUBTRACT, tool.cursorOp(Modifiers(alt = true)))
        tool.onPointer(ev(PointerPhase.CANCEL, 10f, 10f), ctx)
        assertEquals(SelectionOp.SUBTRACT, tool.cursorOp(Modifiers(alt = true)))
    }

    @Test fun quickSelectionStaysNewWhenTheStrokeIsRejectedAndCancelDoesNotCommit() {
        val session = RecordingSession(CommandResult.Rejected(RejectionCode.UNSUPPORTED, "Quick Selection isn't available"))
        val tool = QuickSelectionTool({ session }, { it is CommandResult.Accepted }, { true })
        tool.onPointer(ev(PointerPhase.DOWN, 10f, 10f), ctx)
        tool.onPointer(ev(PointerPhase.UP, 10f, 10f), ctx)
        assertEquals(QuickSelectMode.NEW, tool.mode)

        val cancelled = RecordingSession()
        var cancelReports = 0
        val other = QuickSelectionTool({ cancelled }, { true }, { true }, { cancelReports++ })
        other.onPointer(ev(PointerPhase.DOWN, 10f, 10f), ctx)
        other.onPointer(ev(PointerPhase.CANCEL, 10f, 10f), ctx)
        assertTrue(cancelled.cancelled)
        assertEquals(false, cancelled.ended)
        assertEquals(1, cancelReports)
    }

    @Test fun objectSelectSendsTheDraggedRegionAndAClickDeselects() {
        val regions = mutableListOf<Pair<SelectionShape, SelectionOp>>()
        var deselected = 0
        val tool = ObjectSelectTool(
            hasSelection = { true },
            selectObject = { shape, op, _ ->
                regions += shape to op
                true
            },
            deselect = {
                deselected++
                true
            },
            selectSubject = { true },
        )
        tool.onPointer(ev(PointerPhase.DOWN, 10f, 10f, Modifiers(shift = true)), ctx)
        tool.onPointer(ev(PointerPhase.MOVE, 40f, 30f), ctx)
        tool.onPointer(ev(PointerPhase.UP, 40f, 30f), ctx)
        val (shape, op) = regions.single()
        assertTrue(shape is SelectionShape.Rect)
        assertEquals(SelectionOp.ADD, op)
        assertEquals(30f, shape.bounds.width)

        tool.onPointer(ev(PointerPhase.DOWN, 50f, 50f), ctx)
        tool.onPointer(ev(PointerPhase.UP, 50f, 50f), ctx)
        assertEquals(1, deselected)

        tool.mode = ObjectSelectMode.LASSO
        tool.onPointer(ev(PointerPhase.DOWN, 10f, 10f), ctx)
        listOf(40f to 10f, 40f to 40f, 10f to 40f).forEach { (x, y) -> tool.onPointer(ev(PointerPhase.MOVE, x, y), ctx) }
        tool.onPointer(ev(PointerPhase.UP, 10f, 40f), ctx)
        assertTrue(regions.last().first is SelectionShape.Polygon)
    }
}
