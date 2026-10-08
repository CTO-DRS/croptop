package app.croptop.feature.editor.tools

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.croptop.core.input.Modifiers
import app.croptop.core.model.DocPoint
import app.croptop.core.model.LayerId
import app.croptop.core.model.ToolId
import app.croptop.core.model.ViewPoint
import app.croptop.core.model.ViewportState
import kotlin.math.abs
import kotlin.math.min

/** What a Type tool gesture asks for: where, how wide a paragraph box (null = point text), and which text layer it edits. */
data class TextPlacement(val at: DocPoint, val boxWidth: Float?, val editing: LayerId?)

/**
 * Type tool (T): click for point text, drag for a paragraph box, click on existing text to edit it. The gesture only decides
 * the placement; the editor opens the on-canvas text field for it.
 */
class TextTool(
    /** The text layer under a document point, or null. */
    private val textLayerAt: (DocPoint) -> LayerId?,
    private val onPlace: (TextPlacement) -> Unit,
    /** The open on-canvas editor's rectangle in view space, or null; presses on it belong to the field, not the tool. */
    private val editorBounds: () -> androidx.compose.ui.geometry.Rect? = { null },
) : ToolController {
    override val tool = ToolId.TEXT
    var onActionCompleted: (String) -> Unit = {}

    private var dragStart by mutableStateOf<DocPoint?>(null)
    private var dragEnd by mutableStateOf<DocPoint?>(null)

    override fun cursor(modifiers: Modifiers): PointerIcon = PointerIcon.Text

    private var ignoringGesture = false

    override fun onPointer(event: ToolPointerEvent, ctx: ToolContext): Boolean {
        if (event.phase == PointerPhase.DOWN) {
            ignoringGesture = editorBounds()?.contains(Offset(event.view.x, event.view.y)) == true
        }
        if (ignoringGesture) {
            if (event.phase == PointerPhase.UP || event.phase == PointerPhase.CANCEL) ignoringGesture = false
            return false
        }
        when (event.phase) {
            PointerPhase.DOWN -> {
                dragStart = event.doc
                dragEnd = event.doc
            }
            PointerPhase.MOVE -> if (dragStart != null) dragEnd = event.doc
            PointerPhase.UP -> {
                val start = dragStart ?: return false
                dragStart = null
                dragEnd = null
                val slop = DRAG_SLOP_DP * ctx.density
                val movedPx = abs(event.view.x - event.downAt.x) + abs(event.view.y - event.downAt.y)
                val placement = if (event.cancelled) null else placementFor(start, event.doc, movedPx > slop, ctx.viewport.zoom)
                if (placement != null) {
                    val editing = if (placement.boxWidth == null) textLayerAt(placement.at) else null
                    onPlace(placement.copy(editing = editing))
                    onActionCompleted(if (editing != null) "Edit text" else "Place text")
                }
            }
            PointerPhase.CANCEL -> {
                dragStart = null
                dragEnd = null
            }
        }
        return true
    }

    @Composable
    override fun Overlay(viewport: ViewportState, hover: ViewPoint?) {
        val a = dragStart ?: return
        val b = dragEnd ?: return
        val p = viewport.docToView(a)
        val q = viewport.docToView(b)
        Canvas(Modifier.fillMaxSize()) {
            val left = min(p.x, q.x)
            val top = min(p.y, q.y)
            drawRect(
                Color.White,
                Offset(left, top),
                Size(abs(q.x - p.x), abs(q.y - p.y)),
                style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))),
            )
        }
    }

    companion object {
        const val DRAG_SLOP_DP = 6f

        /** A paragraph box needs at least this much width on screen to count as a drag rather than a jittery click. */
        const val MIN_BOX_VIEW_PX = 24f

        /**
         * The placement for a gesture from [start] to [end]: point text for a click (the document point), a paragraph box for a
         * drag (top-left of the dragged rectangle and its width). Null for a drag too narrow to be a box.
         */
        fun placementFor(start: DocPoint, end: DocPoint, dragged: Boolean, zoom: Float): TextPlacement? {
            if (!dragged) return TextPlacement(start, null, null)
            val width = abs(end.x - start.x)
            if (width * zoom < MIN_BOX_VIEW_PX) return TextPlacement(start, null, null)
            return TextPlacement(DocPoint(min(start.x, end.x), min(start.y, end.y)), width, null)
        }
    }
}
