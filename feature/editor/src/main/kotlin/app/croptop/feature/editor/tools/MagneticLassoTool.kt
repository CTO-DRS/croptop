package app.croptop.feature.editor.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.component.AeBarSlider
import app.croptop.core.designsystem.component.AeText
import app.croptop.core.designsystem.theme.Ae
import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.MagneticLassoSession
import app.croptop.core.engine.api.MagneticLassoSpec
import app.croptop.core.model.DocPoint
import app.croptop.core.model.ToolId
import app.croptop.core.model.ViewPoint
import app.croptop.core.model.ViewportState
import kotlin.math.hypot

/**
 * Magnetic Lasso (L): click to start, move along an edge (the live wire snaps to it and fastens itself), click to
 * add a fastening point, double-click or click the start to close. Backspace removes the last point, Esc cancels.
 */
class MagneticLassoTool(
    private val begin: (DocPoint, MagneticLassoSpec) -> MagneticLassoSession?,
    private val onResult: (CommandResult) -> Unit,
) : ToolController {
    override val tool: ToolId = ToolId.MAGNETIC_LASSO
    var width by mutableStateOf(10f)
    var contrast by mutableStateOf(10f) // percent
    var frequency by mutableStateOf(57f)
    var feather by mutableStateOf(0f)
    private var session: MagneticLassoSession? = null
    private var start: DocPoint? = null
    private var outline by mutableStateOf<List<DocPoint>>(emptyList())
    private var wire by mutableStateOf<List<DocPoint>>(emptyList())

    override fun cursor(modifiers: app.croptop.core.input.Modifiers): PointerIcon = PointerIcon.Crosshair

    override fun onFocusLost() {
        cancel()
    }

    override fun onPointer(event: ToolPointerEvent, ctx: ToolContext): Boolean {
        val active = session
        when (event.phase) {
            PointerPhase.DOWN -> {
                if (active == null) {
                    val spec = MagneticLassoSpec(
                        width = width.coerceIn(1f, 256f),
                        contrast = (contrast / 100f).coerceIn(0.01f, 1f),
                        frequency = frequency.coerceIn(0f, 100f),
                        op = selectionOp(event.modifiers),
                        feather = feather.coerceIn(0f, 1000f),
                    )
                    session = begin(event.doc, spec) ?: return true
                    start = event.doc
                    outline = listOf(event.doc)
                    wire = emptyList()
                    return true
                }
                val origin = start
                val closes = event.clickCount >= 2 ||
                    (origin != null && outline.size >= 3 && hypot(event.doc.x - origin.x, event.doc.y - origin.y) * ctx.viewport.zoom < CLOSE_PX)
                if (closes) {
                    close()
                } else {
                    active.anchor(event.doc)
                    outline = active.outline()
                    wire = emptyList()
                }
            }

            PointerPhase.MOVE -> if (active != null) {
                wire = active.move(event.doc)
                outline = active.outline()
            }

            PointerPhase.UP -> Unit

            PointerPhase.CANCEL -> cancel()
        }
        return true
    }

    private fun close() {
        val active = session ?: return
        session = null
        outline = emptyList()
        wire = emptyList()
        onResult(active.close())
    }

    fun cancel(): Boolean {
        val active = session ?: return false
        session = null
        outline = emptyList()
        wire = emptyList()
        active.cancel()
        return true
    }

    /** Backspace removes the last fastening point. */
    fun removeLastPoint(): Boolean {
        val active = session ?: return false
        val removed = active.undoAnchor()
        outline = active.outline()
        if (removed) wire = emptyList()
        return removed
    }

    /** Enter closes the outline; Esc abandons it. */
    fun onKey(enter: Boolean): Boolean = if (enter) {
        if (session == null) {
            false
        } else {
            close()
            true
        }
    } else {
        cancel()
    }

    @Composable
    override fun Overlay(viewport: ViewportState, hover: ViewPoint?) {
        // Mouse/pen hover drives the live wire between clicks (touch drives it through MOVE events).
        LaunchedEffect(hover) {
            val active = session
            if (active != null && hover != null) {
                wire = active.move(viewport.viewToDoc(hover))
                outline = active.outline()
            }
        }
        val path = outline + wire.drop(1)
        if (path.size >= 2) MarchingAnts(path, viewport, closed = false)
    }

    @Composable
    override fun Options() {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AeBarSlider("Width", width, { width = it }, range = 1f..256f, defaultValue = 10f, unit = "px", width = 150.dp, contentDescription = "Width")
            AeBarSlider("Contrast", contrast, { contrast = it }, range = 1f..100f, defaultValue = 10f, unit = "%", width = 150.dp, contentDescription = "Contrast")
            AeBarSlider("Frequency", frequency, { frequency = it }, range = 0f..100f, defaultValue = 57f, width = 150.dp, contentDescription = "Frequency")
            AeBarSlider("Feather", feather, { feather = it }, range = 0f..1000f, defaultValue = 0f, curve = 3f, decimals = 1, unit = "px", width = 160.dp, contentDescription = "Feather")
            AeText(
                "Click to start · click to fasten · double-click closes · Backspace undoes a point",
                style = Ae.type.caption,
                color = Ae.colors.textTertiary,
            )
        }
    }

    private companion object {
        const val CLOSE_PX = 8f
    }
}
