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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.component.AeBarSlider
import app.croptop.core.designsystem.component.AeText
import app.croptop.core.designsystem.theme.Ae
import app.croptop.core.model.DocPoint
import app.croptop.core.model.ToolId
import app.croptop.core.model.ViewPoint
import app.croptop.core.model.ViewportState

/**
 * Remove (J): paint over what should go; on release the AI fills the painted area from its surroundings on a new layer.
 * [apply] receives the stroke in document pixels and the brush radius in document pixels.
 */
class AiRemoveTool(private val apply: (List<DocPoint>, Float) -> Boolean) : ToolController {
    override val tool: ToolId = ToolId.AI_REMOVE

    /** Brush diameter in screen px, so the brush feels the same at every zoom. */
    var size by mutableStateOf(DEFAULT_SIZE)
    private var stroke by mutableStateOf<List<DocPoint>>(emptyList())
    private var radiusDoc by mutableStateOf(0f)

    override fun onPointer(event: ToolPointerEvent, ctx: ToolContext): Boolean {
        when (event.phase) {
            PointerPhase.DOWN -> {
                radiusDoc = size / 2f / ctx.viewport.zoom
                stroke = listOf(event.doc)
            }

            PointerPhase.MOVE -> if (stroke.isNotEmpty()) {
                val last = stroke.last()
                // Skip samples closer than a quarter of the brush so a long stroke stays a short list.
                if (kotlin.math.hypot(event.doc.x - last.x, event.doc.y - last.y) >= radiusDoc / 4f) stroke = stroke + event.doc
            }

            PointerPhase.UP -> {
                val points = if (stroke.isNotEmpty() && stroke.last() != event.doc) stroke + event.doc else stroke
                val radius = radiusDoc
                stroke = emptyList()
                if (points.isNotEmpty() && radius > 0f) apply(points, radius)
            }

            PointerPhase.CANCEL -> stroke = emptyList()
        }
        return true
    }

    @Composable
    override fun Overlay(viewport: ViewportState, hover: ViewPoint?) {
        val points = stroke
        if (points.isEmpty()) return
        Canvas(Modifier.fillMaxSize()) {
            val path = androidx.compose.ui.graphics.Path()
            points.forEachIndexed { i, p ->
                val v = viewport.docToView(p)
                if (i == 0) path.moveTo(v.x, v.y) else path.lineTo(v.x, v.y)
            }
            if (points.size == 1) {
                val v = viewport.docToView(points[0])
                path.lineTo(v.x + 0.01f, v.y)
            }
            drawPath(path, Color(0x66FF3B30), style = Stroke(this@AiRemoveTool.size, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }

    @Composable
    override fun Options() {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AeBarSlider("Size", size, { size = it }, range = 4f..400f, defaultValue = 40f, curve = 2f, unit = "px", width = 176.dp, contentDescription = "Remove brush size")
            AeText("Paint over what you want to remove", style = Ae.type.caption, color = Ae.colors.textTertiary)
        }
    }

    private companion object {
        const val DEFAULT_SIZE = 40f
    }
}
