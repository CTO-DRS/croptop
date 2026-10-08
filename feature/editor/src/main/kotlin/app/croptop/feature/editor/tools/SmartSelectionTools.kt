package app.croptop.feature.editor.tools

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.component.AeButton
import app.croptop.core.designsystem.component.AeButtonStyle
import app.croptop.core.designsystem.component.AeCheckbox
import app.croptop.core.designsystem.component.AeBarSlider
import app.croptop.core.designsystem.component.AeSegmented
import app.croptop.core.designsystem.component.AeText
import app.croptop.core.designsystem.theme.Ae
import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.QuickSelectionSession
import app.croptop.core.engine.api.QuickSelectionSpec
import app.croptop.core.input.Modifiers
import app.croptop.core.model.DocPoint
import app.croptop.core.model.SelectionOp
import app.croptop.core.model.SelectionShape
import app.croptop.core.model.ToolId
import app.croptop.core.model.ViewPoint
import app.croptop.core.model.ViewportState

/** The Quick Selection brush mode. New switches to Add after the first stroke, like Photoshop. */
enum class QuickSelectMode(val label: String) { NEW("New"), ADD("Add"), SUBTRACT("Subtract") }

/**
 * Quick Selection (W): paint over an area and the selection grows to similar pixels and edges.
 * Shift adds, Alt subtracts. The engine previews live; each stroke is one history step.
 */
class QuickSelectionTool(
    private val begin: (QuickSelectionSpec) -> QuickSelectionSession?,
    private val onResult: (CommandResult) -> Boolean,
    private val selectSubject: (Boolean) -> Boolean,
    private val onCancel: () -> Unit = {},
) : ToolController {
    override val tool = ToolId.QUICK_SELECT
    var size by mutableStateOf(30f)
    var mode by mutableStateOf(QuickSelectMode.NEW)
    var sampleAll by mutableStateOf(false)
    var enhanceEdge by mutableStateOf(false)
    var onActionCompleted: (String) -> Unit = {}
    private var session: QuickSelectionSession? = null
    private var painting by mutableStateOf(false)
    private var strokeOp by mutableStateOf(SelectionOp.ADD)

    override fun cursor(modifiers: Modifiers): PointerIcon = PointerIcon.Crosshair

    /** Modifiers override the options-bar mode for one stroke. */
    fun opFor(m: Modifiers): SelectionOp = when {
        m.alt -> SelectionOp.SUBTRACT

        m.shift -> SelectionOp.ADD

        else -> when (mode) {
            QuickSelectMode.NEW -> SelectionOp.REPLACE
            QuickSelectMode.ADD -> SelectionOp.ADD
            QuickSelectMode.SUBTRACT -> SelectionOp.SUBTRACT
        }
    }

    /** The brush badge previews held modifier modes, then stays locked to the active stroke. */
    fun cursorOp(modifiers: Modifiers): SelectionOp = if (painting) strokeOp else opFor(modifiers)

    override fun onPointer(event: ToolPointerEvent, ctx: ToolContext): Boolean {
        when (event.phase) {
            PointerPhase.DOWN -> {
                strokeOp = opFor(event.modifiers)
                session = begin(QuickSelectionSpec(size.coerceIn(1f, QuickSelectionSpec.MAX_DIAMETER), strokeOp, sampleAll, enhanceEdge))
                session?.add(floatArrayOf(event.doc.x, event.doc.y))
                painting = session != null
            }

            PointerPhase.MOVE -> session?.add(floatArrayOf(event.doc.x, event.doc.y))

            PointerPhase.UP -> {
                val s = session
                if (s != null && onResult(s.end()) && strokeOp == SelectionOp.REPLACE) mode = QuickSelectMode.ADD
                session = null
                painting = false
            }

            PointerPhase.CANCEL -> {
                val cancelledSession = session
                cancelledSession?.cancel()
                if (cancelledSession != null) onCancel()
                session = null
                painting = false
            }
        }
        return true
    }

    @Composable
    override fun Overlay(viewport: ViewportState, hover: ViewPoint?) = Overlay(viewport, hover, Modifiers.None)

    @Composable
    fun Overlay(viewport: ViewportState, hover: ViewPoint?, modifiers: Modifiers) {
        val at = hover ?: return
        val r = (size / 2f * viewport.zoom).coerceAtLeast(3f)
        Canvas(Modifier.fillMaxSize()) { brushCursor(Offset(at.x, at.y), r, cursorOp(modifiers)) }
    }

    @Composable
    override fun Options() {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AeSegmented(
                QuickSelectMode.entries,
                mode,
                {
                    mode = it
                    onActionCompleted("Set quick selection mode to ${it.label.lowercase()}")
                },
                { it.label },
            )
            AeBarSlider("Size", size, {
                size = it
            }, range = 1f..QuickSelectionSpec.MAX_DIAMETER, defaultValue = 30f, curve = 2f, unit = "px", width = 176.dp, contentDescription = "Brush size", onValueChangeFinished = {
                onActionCompleted("Set quick selection size")
            })
            AeCheckbox(sampleAll, {
                sampleAll = it
                onActionCompleted(if (it) "Sample all layers for quick selection" else "Sample current layer for quick selection")
            }, "Sample all layers")
            AeCheckbox(enhanceEdge, {
                enhanceEdge = it
                onActionCompleted(if (it) "Enhance quick selection edges" else "Disable quick selection edge enhancement")
            }, "Enhance edge")
            AeButton("Select Subject", { selectSubject(sampleAll) }, style = AeButtonStyle.Ghost)
            AeText("Shift add · Alt subtract", style = Ae.type.caption, color = Ae.colors.textTertiary)
        }
    }
}

/** Brush ring with a +/− badge for the stroke's effect, black on white so it reads on any image. */
private fun DrawScope.brushCursor(at: Offset, r: Float, op: SelectionOp) {
    drawCircle(Color.White, r, at, style = Stroke(1.5.dp.toPx()))
    drawCircle(Color.Black, r, at, style = Stroke(0.75.dp.toPx()))
    if (op == SelectionOp.REPLACE) return
    val arm = 3.dp.toPx()
    val c = Offset(at.x + r * 0.7071f + arm, at.y + r * 0.7071f + arm)
    for ((color, width) in listOf(Color.White to 3.dp.toPx(), Color.Black to 1.25.dp.toPx())) {
        drawLine(color, Offset(c.x - arm, c.y), Offset(c.x + arm, c.y), width)
        if (op == SelectionOp.ADD) drawLine(color, Offset(c.x, c.y - arm), Offset(c.x, c.y + arm), width)
    }
}

enum class ObjectSelectMode(val label: String) { RECTANGLE("Rectangle"), LASSO("Lasso") }

/**
 * Object Selection (W): drag a rectangle or lasso around an object and the engine selects the object
 * inside. Shift adds, Alt subtracts, Shift+Alt intersects; a click deselects.
 */
class ObjectSelectTool(
    private val hasSelection: () -> Boolean,
    private val selectObject: (SelectionShape, SelectionOp, Boolean) -> Boolean,
    private val deselect: () -> Boolean,
    private val selectSubject: (Boolean) -> Boolean,
    /** A plain click: selects the object under the point (the click-driven model); null clears the selection instead. */
    private val selectAt: ((DocPoint, SelectionOp) -> Boolean)? = null,
) : ToolController {
    override val tool = ToolId.OBJECT_SELECT
    var mode by mutableStateOf(ObjectSelectMode.RECTANGLE)
    var sampleAll by mutableStateOf(false)
    var onActionCompleted: (String) -> Unit = {}

    private val apply: (SelectionShape, SelectionOp, Float) -> Boolean = { shape, op, _ ->
        if (shape.bounds.width <= 0f && shape.bounds.height <= 0f) {
            selectAt?.invoke(DocPoint(shape.bounds.left, shape.bounds.top), op) ?: deselect()
        } else {
            selectObject(shape, op, sampleAll)
        }
    }
    private val rectangle = MarqueeTool(ToolId.OBJECT_SELECT, ellipse = false, hasSelection, apply)
    private val lasso = LassoTool(ToolId.OBJECT_SELECT, polygonal = false, apply)
    private val active: ToolController get() = if (mode == ObjectSelectMode.RECTANGLE) rectangle else lasso

    override fun cursor(modifiers: Modifiers): PointerIcon = PointerIcon.Crosshair

    override fun onPointer(event: ToolPointerEvent, ctx: ToolContext): Boolean = active.onPointer(event, ctx)

    @Composable
    override fun Overlay(viewport: ViewportState, hover: ViewPoint?) = active.Overlay(viewport, hover)

    @Composable
    override fun Options() {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AeSegmented(
                ObjectSelectMode.entries,
                mode,
                {
                    mode = it
                    onActionCompleted("Set object selection mode to ${it.label.lowercase()}")
                },
                { it.label },
            )
            AeCheckbox(sampleAll, {
                sampleAll = it
                onActionCompleted(if (it) "Sample all layers for object selection" else "Sample current layer for object selection")
            }, "Sample all layers")
            AeButton("Select Subject", { selectSubject(sampleAll) }, style = AeButtonStyle.Ghost)
            AeText(
                "Click an object, or drag around it · Shift add · Alt subtract · Shift+Alt intersect",
                style = Ae.type.caption,
                color = Ae.colors.textTertiary,
            )
        }
    }
}
