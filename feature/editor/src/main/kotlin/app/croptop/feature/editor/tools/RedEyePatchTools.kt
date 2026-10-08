package app.croptop.feature.editor.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.component.AeCheckbox
import app.croptop.core.designsystem.component.AeBarSlider
import app.croptop.core.designsystem.component.AeText
import app.croptop.core.designsystem.theme.Ae
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocRectF
import app.croptop.core.model.ToolId
import app.croptop.core.model.ViewPoint
import app.croptop.core.model.ViewportState
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Red Eye (J): click on an eye or drag around it; red pupils inside are neutralised and darkened. */
class RedEyeTool(private val apply: (DocRectF, Float, Float) -> Boolean) : ToolController {
    override val tool: ToolId = ToolId.RED_EYE
    var pupilSize by mutableStateOf(50f)
    var darken by mutableStateOf(50f)
    private var from by mutableStateOf<DocPoint?>(null)
    private var to by mutableStateOf<DocPoint?>(null)

    override fun onPointer(event: ToolPointerEvent, ctx: ToolContext): Boolean {
        when (event.phase) {
            PointerPhase.DOWN -> {
                from = event.doc
                to = event.doc
            }

            PointerPhase.MOVE -> to = event.doc

            PointerPhase.UP -> {
                val a = from ?: return true
                val b = event.doc
                // A click (tiny drag) checks a box around the point, about 40 screen px wide.
                val half = CLICK_BOX_VIEW_PX / ctx.viewport.zoom / 2f
                val rect = if (abs(b.x - a.x) * ctx.viewport.zoom < 4f && abs(b.y - a.y) * ctx.viewport.zoom < 4f) {
                    DocRectF(a.x - half, a.y - half, a.x + half, a.y + half)
                } else {
                    DocRectF(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y))
                }
                apply(rect, pupilSize / 100f, darken / 100f)
                from = null
                to = null
            }

            PointerPhase.CANCEL -> {
                from = null
                to = null
            }
        }
        return true
    }

    @Composable
    override fun Overlay(viewport: ViewportState, hover: ViewPoint?) {
        val a = from ?: return
        val b = to ?: return
        if (a == b) return
        MarchingAnts(listOf(a, DocPoint(b.x, a.y), b, DocPoint(a.x, b.y)), viewport, closed = true)
    }

    @Composable
    override fun Options() {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AeBarSlider("Pupil Size", pupilSize, { pupilSize = it }, range = 1f..100f, defaultValue = 50f, unit = "%", width = 176.dp, contentDescription = "Pupil size")
            AeBarSlider("Darken Amount", darken, { darken = it }, range = 1f..100f, defaultValue = 50f, unit = "%", width = 192.dp, contentDescription = "Darken amount")
            AeText("Click an eye or drag around it", style = Ae.type.caption, color = Ae.colors.textTertiary)
        }
    }

    private companion object {
        const val CLICK_BOX_VIEW_PX = 40f
    }
}

/**
 * Patch (J, Source mode): with an area selected, drag from inside it to clean texture elsewhere; on release the
 * selected area is rebuilt from where you dropped it, blended to fit (or synthesized with Content-Aware).
 */
class PatchTool(
    private val hasSelection: () -> Boolean,
    private val apply: (DocPoint, Boolean) -> Boolean,
    private val onNeedsSelection: () -> Unit,
) : ToolController {
    override val tool: ToolId = ToolId.PATCH
    var contentAware by mutableStateOf(false)
    private var from by mutableStateOf<DocPoint?>(null)
    private var to by mutableStateOf<DocPoint?>(null)

    override fun onPointer(event: ToolPointerEvent, ctx: ToolContext): Boolean {
        when (event.phase) {
            PointerPhase.DOWN -> {
                if (!hasSelection()) {
                    onNeedsSelection()
                    return true
                }
                from = event.doc
                to = event.doc
            }

            PointerPhase.MOVE -> if (from != null) to = event.doc

            PointerPhase.UP -> {
                val a = from ?: return true
                val offset = DocPoint(event.doc.x - a.x, event.doc.y - a.y)
                from = null
                to = null
                if (abs(offset.x) >= 1f || abs(offset.y) >= 1f) apply(offset, contentAware)
            }

            PointerPhase.CANCEL -> {
                from = null
                to = null
            }
        }
        return true
    }

    @Composable
    override fun Overlay(viewport: ViewportState, hover: ViewPoint?) {
        val a = from ?: return
        val b = to ?: return
        if (a != b) MarchingAnts(listOf(a, b), viewport, closed = false)
    }

    @Composable
    override fun Options() {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AeCheckbox(contentAware, { contentAware = it }, "Content-Aware")
            AeText("Select the area to fix, then drag it onto clean texture", style = Ae.type.caption, color = Ae.colors.textTertiary)
        }
    }
}
