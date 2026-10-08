package app.croptop.feature.editor.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.component.AeButton
import app.croptop.core.designsystem.component.AeButtonStyle
import app.croptop.core.designsystem.component.AeText
import app.croptop.core.designsystem.theme.Ae
import app.croptop.core.input.Modifiers
import app.croptop.core.model.ToolId
import app.croptop.core.model.ViewPoint
import app.croptop.core.model.ViewportState
import kotlin.math.atan2
import kotlin.math.pow
import kotlin.math.roundToInt

/** Hand (H): drag pans. */
class HandTool : ToolController {
    override val tool = ToolId.HAND
    var onActionCompleted: (String) -> Unit = {}
    private var last: ViewPoint? = null
    private var panned = false

    override fun cursor(modifiers: Modifiers): PointerIcon = PointerIcon.Hand

    override fun onPointer(event: ToolPointerEvent, ctx: ToolContext): Boolean {
        when (event.phase) {
            PointerPhase.DOWN -> {
                last = event.view
                panned = false
            }

            PointerPhase.MOVE -> last?.let { l ->
                if (event.view.x != l.x || event.view.y != l.y) panned = true
                ctx.updateViewport(ctx.viewport.panBy(event.view.x - l.x, event.view.y - l.y))
                last = event.view
            }

            PointerPhase.UP -> {
                if (panned) onActionCompleted("Pan canvas view")
                last = null
                panned = false
            }

            PointerPhase.CANCEL -> {
                last = null
                panned = false
            }
        }
        return true
    }
}

/**
 * Zoom (Z): click zooms in at the point, Alt-click zooms out, horizontal drag scrubs zoom
 * continuously around the press point (Photoshop "scrubby zoom").
 */
class ZoomTool(private val onFit: (ToolContext) -> Unit = { it.updateViewport(it.viewport.fit(it.document)) }) : ToolController {
    override val tool = ToolId.ZOOM
    var onActionCompleted: (String) -> Unit = {}
    private var start: ViewportState? = null
    private var dragged = false

    override fun cursor(modifiers: Modifiers): PointerIcon = PointerIcon(
        if (modifiers.alt) android.view.PointerIcon.TYPE_ZOOM_OUT else android.view.PointerIcon.TYPE_ZOOM_IN,
    )

    override fun onPointer(event: ToolPointerEvent, ctx: ToolContext): Boolean {
        when (event.phase) {
            PointerPhase.DOWN -> {
                start = ctx.viewport
                dragged = false
            }

            PointerPhase.MOVE -> {
                val s = start ?: return true
                val dx = event.view.x - event.downAt.x
                if (!dragged && kotlin.math.abs(dx) < DRAG_SLOP) return true
                dragged = true
                ctx.updateViewport(s.zoomAt(event.downAt, scrubFactor(dx)))
            }

            PointerPhase.UP -> {
                if (!dragged) {
                    if (event.clickCount >= 2) {
                        ctx.updateViewport(ctx.viewport.actualSize(ctx.document))
                        onActionCompleted("Set zoom to actual pixels")
                    } else {
                        ctx.updateViewport(ctx.viewport.stepZoom(event.view, zoomIn = !event.modifiers.alt))
                        onActionCompleted(if (event.modifiers.alt) "Zoom out" else "Zoom in")
                    }
                } else {
                    onActionCompleted("Scrub zoom")
                }
                start = null
            }

            PointerPhase.CANCEL -> start?.let(ctx::updateViewport).also { start = null }
        }
        return true
    }

    @Composable
    override fun Options() {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AeText("Click to zoom in · Alt-click to zoom out · Drag to scrub", style = Ae.type.caption, color = Ae.colors.textSecondary)
        }
    }

    companion object {
        const val DRAG_SLOP = 4f

        /** 200 px of drag = 2× zoom. */
        fun scrubFactor(dx: Float): Float = 2f.pow(dx / 200f)
    }
}

/** Rotate View (R): drag rotates the canvas around the view center; Shift snaps to 15°; Esc resets. */
class RotateViewTool : ToolController {
    override val tool = ToolId.ROTATE_VIEW
    var onActionCompleted: (String) -> Unit = {}
    private var startAngle = 0f
    private var startState: ViewportState? = null

    override fun cursor(modifiers: Modifiers): PointerIcon = PointerIcon(android.view.PointerIcon.TYPE_GRAB)

    private fun angleOf(p: ViewPoint, c: ViewPoint) = Math.toDegrees(atan2((p.y - c.y).toDouble(), (p.x - c.x).toDouble())).toFloat()

    override fun onPointer(event: ToolPointerEvent, ctx: ToolContext): Boolean {
        val center = ViewPoint(ctx.viewport.viewWidth / 2f, ctx.viewport.viewHeight / 2f)
        when (event.phase) {
            PointerPhase.DOWN -> {
                startState = ctx.viewport
                startAngle = angleOf(event.view, center)
            }

            PointerPhase.MOVE -> {
                val s = startState ?: return true
                var target = s.rotationDegrees + angleOf(event.view, center) - startAngle
                if (event.modifiers.shift) target = (target / SNAP).roundToInt() * SNAP
                ctx.updateViewport(s.rotateAt(center, target - s.rotationDegrees))
            }

            PointerPhase.UP -> {
                if (startState != null) onActionCompleted("Rotate canvas view")
                startState = null
            }

            PointerPhase.CANCEL -> startState?.let(ctx::updateViewport).also { startState = null }
        }
        return true
    }

    @Composable
    override fun Options() = Unit

    companion object {
        const val SNAP = 15f
    }
}

/** Options-bar "Reset View" for Rotate View. */
@Composable
fun ResetRotationButton(viewport: ViewportState, onChange: (ViewportState) -> Unit, onActionCompleted: () -> Unit = {}) {
    AeButton(
        "Reset View",
        onClick = {
            onChange(viewport.rotateAt(ViewPoint(viewport.viewWidth / 2f, viewport.viewHeight / 2f), -viewport.rotationDegrees))
            onActionCompleted()
        },
        style = AeButtonStyle.Secondary,
        enabled = viewport.rotationDegrees != 0f,
    )
}
