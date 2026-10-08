package app.croptop.feature.editor.tools

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.component.AeButton
import app.croptop.core.designsystem.component.AeButtonStyle
import app.croptop.core.designsystem.component.AeSegmented
import app.croptop.core.designsystem.component.AeSlider
import app.croptop.core.designsystem.component.AeText
import app.croptop.core.designsystem.theme.Ae
import app.croptop.core.model.DocPoint
import app.croptop.core.model.ToolId
import app.croptop.core.model.ViewPoint
import app.croptop.core.model.ViewportState
import kotlin.math.hypot

/**
 * Paints the shared smart-filter mask of a smart object: Hide (black) removes the filters under the brush, Reveal (white) brings
 * them back. Each gesture is one history step. Takes over canvas input until Done / Esc.
 */
class FilterMaskBrushTool(
    private val paint: (points: List<DocPoint>, radius: Float, reveal: Boolean) -> Boolean,
    private val onDone: () -> Unit,
) : ToolController {
    override val tool: ToolId = ToolId.BRUSH

    var reveal by mutableStateOf(false)
    var size by mutableFloatStateOf(80f)

    private val points = mutableListOf<DocPoint>()

    override fun onPointer(event: ToolPointerEvent, ctx: ToolContext): Boolean {
        when (event.phase) {
            PointerPhase.DOWN -> {
                points.clear()
                points += event.doc
            }

            PointerPhase.MOVE -> {
                val last = points.lastOrNull() ?: return true
                val step = (size / 8f).coerceAtLeast(1f)
                if (hypot(event.doc.x - last.x, event.doc.y - last.y) >= step && points.size < MAX_POINTS) points += event.doc
            }

            PointerPhase.UP -> {
                if (points.lastOrNull() != event.doc && points.size < MAX_POINTS) points += event.doc
                if (points.isNotEmpty()) paint(points.toList(), (size / 2f).coerceAtLeast(0.5f), reveal)
                points.clear()
            }

            PointerPhase.CANCEL -> points.clear()
        }
        return true
    }

    override fun onFocusLost() = points.clear()

    @Composable
    override fun Options() {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AeText("Filter mask", style = Ae.type.label)
            AeSegmented(listOf(false, true), reveal, { reveal = it }, { if (it) "Reveal" else "Hide" })
            AeSlider("Size", size, { size = it }, Modifier.width(180.dp), range = 1f..1000f, defaultValue = 80f)
            AeButton("Done", onDone, style = AeButtonStyle.Primary)
        }
    }

    @Composable
    override fun Overlay(viewport: ViewportState, hover: ViewPoint?) {
        val point = hover ?: return
        Canvas(Modifier.fillMaxSize()) {
            val radius = (this@FilterMaskBrushTool.size * viewport.zoom / 2f).coerceIn(1f, this.size.maxDimension)
            drawCircle(Color.Black.copy(alpha = 0.8f), radius, Offset(point.x, point.y), style = Stroke(2f))
            drawCircle(if (reveal) Color.White else Color(0xFFE5484D), radius, Offset(point.x, point.y), style = Stroke(1f))
        }
    }

    private companion object {
        const val MAX_POINTS = 4096
    }
}
