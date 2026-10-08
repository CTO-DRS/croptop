package app.croptop.feature.editor.tools

import androidx.compose.ui.input.pointer.PointerIcon
import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.MagneticLassoSession
import app.croptop.core.engine.api.MagneticLassoSpec
import app.croptop.core.input.Modifiers
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocSize
import app.croptop.core.model.SelectionOp
import app.croptop.core.model.ToolId
import app.croptop.core.model.ViewPoint
import app.croptop.core.model.ViewportState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MagneticLassoToolTest {
    private val context = object : ToolContext {
        override val viewport = ViewportState(zoom = 1f, viewWidth = 800, viewHeight = 600)
        override val document = DocSize(4000, 3000)

        override fun updateViewport(state: ViewportState) = Unit
    }

    private fun event(
        phase: PointerPhase,
        x: Float,
        y: Float,
        modifiers: Modifiers = Modifiers.None,
        clickCount: Int = 1,
    ) = ToolPointerEvent(
        phase = phase,
        view = ViewPoint(x, y),
        doc = DocPoint(x, y),
        pressure = 1f,
        modifiers = modifiers,
        downAt = ViewPoint(0f, 0f),
        clickCount = clickCount,
    )

    @Test
    fun `magnetic lasso exposes crosshair and modifier mode badges`() {
        val tool = MagneticLassoTool({ _, _ -> RecordingSession() }, {})
        assertEquals(PointerIcon.Crosshair, tool.cursor(Modifiers.None))
        assertEquals(SelectionOp.ADD, selectionModeCursorOperation(ToolId.MAGNETIC_LASSO, Modifiers(shift = true)))
        assertEquals(SelectionOp.SUBTRACT, selectionModeCursorOperation(ToolId.MAGNETIC_LASSO, Modifiers(alt = true)))
        assertEquals(
            SelectionOp.INTERSECT,
            selectionModeCursorOperation(ToolId.MAGNETIC_LASSO, Modifiers(shift = true, alt = true)),
        )
        assertEquals(null, selectionModeCursorOperation(ToolId.MAGNETIC_LASSO, Modifiers.None))
        assertEquals(null, selectionModeCursorOperation(ToolId.BRUSH, Modifiers(shift = true)))
    }

    @Test
    fun `begins with clamped settings and modifier operation and routes anchors and backspace`() {
        val session = RecordingSession()
        var receivedSpec: MagneticLassoSpec? = null
        val results = mutableListOf<CommandResult>()
        val tool = MagneticLassoTool({ _, spec ->
            receivedSpec = spec
            session
        }, results::add).apply {
            width = 300f
            contrast = 120f
            frequency = -10f
            feather = 1200f
        }

        tool.onPointer(event(PointerPhase.DOWN, 10f, 20f, Modifiers(shift = true, alt = true)), context)
        assertEquals(
            MagneticLassoSpec(
                width = 256f,
                contrast = 1f,
                frequency = 0f,
                op = SelectionOp.INTERSECT,
                feather = 1000f,
            ),
            receivedSpec,
        )
        tool.onPointer(event(PointerPhase.MOVE, 30f, 40f), context)
        tool.onPointer(event(PointerPhase.DOWN, 50f, 60f), context)
        assertEquals(listOf(DocPoint(30f, 40f)), session.moves)
        assertEquals(listOf(DocPoint(50f, 60f)), session.anchors)

        assertTrue(tool.removeLastPoint())
        assertEquals(1, session.undoCount)
        assertEquals(emptyList(), results)
    }

    @Test
    fun `Enter and double click close once and report the engine result`() {
        val session = RecordingSession()
        val results = mutableListOf<CommandResult>()
        val tool = MagneticLassoTool({ _, _ -> session }, results::add)

        tool.onPointer(event(PointerPhase.DOWN, 10f, 10f), context)
        tool.onPointer(event(PointerPhase.DOWN, 20f, 20f), context)
        assertTrue(tool.onKey(enter = true))
        assertEquals(1, session.closeCount)
        assertEquals(listOf<CommandResult>(CommandResult.Accepted(17)), results)
        assertFalse(tool.onKey(enter = true))

        val doubleClickSession = RecordingSession()
        val doubleClickTool = MagneticLassoTool({ _, _ -> doubleClickSession }, {})
        doubleClickTool.onPointer(event(PointerPhase.DOWN, 10f, 10f), context)
        doubleClickTool.onPointer(event(PointerPhase.DOWN, 20f, 20f, clickCount = 2), context)
        assertEquals(1, doubleClickSession.closeCount)
    }

    @Test
    fun `Escape and pointer cancellation discard without publishing a result`() {
        val first = RecordingSession()
        val results = mutableListOf<CommandResult>()
        val tool = MagneticLassoTool({ _, _ -> first }, results::add)
        tool.onPointer(event(PointerPhase.DOWN, 10f, 10f), context)
        assertTrue(tool.onKey(enter = false))
        assertEquals(1, first.cancelCount)
        assertFalse(tool.onKey(enter = false))

        val second = RecordingSession()
        val pointerCancel = MagneticLassoTool({ _, _ -> second }, results::add)
        pointerCancel.onPointer(event(PointerPhase.DOWN, 10f, 10f), context)
        pointerCancel.onPointer(event(PointerPhase.CANCEL, 20f, 20f), context)
        assertEquals(1, second.cancelCount)

        val third = RecordingSession()
        val focusLost = MagneticLassoTool({ _, _ -> third }, results::add)
        focusLost.onPointer(event(PointerPhase.DOWN, 10f, 10f), context)
        focusLost.onFocusLost()
        assertEquals(1, third.cancelCount)
        assertEquals(emptyList(), results)
    }

    private class RecordingSession : MagneticLassoSession {
        val moves = mutableListOf<DocPoint>()
        val anchors = mutableListOf<DocPoint>()
        var undoCount = 0
        var closeCount = 0
        var cancelCount = 0

        override fun move(point: DocPoint): List<DocPoint> {
            moves += point
            return listOf(point)
        }

        override fun anchor(point: DocPoint) {
            anchors += point
        }

        override fun undoAnchor(): Boolean {
            undoCount++
            return anchors.isNotEmpty()
        }

        override fun outline(): List<DocPoint> = anchors.toList()

        override fun close(): CommandResult {
            closeCount++
            return CommandResult.Accepted(17)
        }

        override fun cancel() {
            cancelCount++
        }
    }
}
