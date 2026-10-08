package app.croptop.feature.editor.tools

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.RefineEdgeBrushMode
import app.croptop.core.engine.api.RefineEdgeBrushSpec
import app.croptop.core.model.ToolId

/** Adapts CanvasViewport's document-space pointer lifecycle to a Select and Mask brush session. */
class RefineEdgeBrushTool(
    private val controller: RefineEdgeBrushController,
    private val onResult: (CommandResult) -> Unit = {},
) : ToolController {
    override val tool: ToolId = ToolId.BRUSH

    val isStrokeActive: Boolean get() = controller.isActive

    fun setSpec(spec: RefineEdgeBrushSpec) = controller.setSpec(spec)

    fun setBrush(mode: RefineEdgeBrushMode, diameter: Float) =
        setSpec(RefineEdgeBrushSpec(mode = mode, diameter = diameter.coerceIn(1f, 5000f)))

    override fun onPointer(event: ToolPointerEvent, ctx: ToolContext): Boolean {
        when (event.phase) {
            PointerPhase.DOWN -> controller.begin(event.doc, event.pressure, event.eventTimeMillis)?.let(onResult)

            PointerPhase.MOVE -> {
                if (!controller.isActive) return false
                controller.move(event.doc, event.pressure, event.eventTimeMillis)?.let(onResult)
            }

            PointerPhase.UP -> {
                if (!controller.isActive) return false
                onResult(controller.end())
            }

            PointerPhase.CANCEL -> {
                if (!controller.isActive) return false
                controller.cancel()
            }
        }
        return true
    }

    override fun onFocusLost() = controller.cancel()

    @Composable
    override fun Overlay(viewport: app.croptop.core.model.ViewportState, hover: app.croptop.core.model.ViewPoint?) {
        val point = hover ?: return
        Canvas(Modifier.fillMaxSize()) {
            val radius = (controller.currentSpec.diameter * viewport.zoom / 2f).coerceIn(1f, size.maxDimension)
            val center = Offset(point.x, point.y)
            drawCircle(Color.Black.copy(alpha = 0.85f), radius, center, style = Stroke(2.dp.toPx()))
            drawCircle(Color.White, radius, center, style = Stroke(1.dp.toPx()))
        }
    }
}
