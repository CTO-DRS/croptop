package app.croptop.feature.editor.tools

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.component.AeButton
import app.croptop.core.designsystem.component.AeButtonStyle
import app.croptop.core.designsystem.component.AeSlider
import app.croptop.core.designsystem.theme.Ae
import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.LiquifyDab
import app.croptop.core.engine.api.LiquifySession
import app.croptop.core.engine.api.LiquifyStroke
import app.croptop.core.engine.api.LiquifyTool
import app.croptop.core.input.Modifiers
import app.croptop.core.model.DocPoint
import app.croptop.core.model.ToolId
import app.croptop.core.model.ViewPoint
import app.croptop.core.model.ViewportState

/** Freeze (F) protects pixels from later strokes; Thaw (D) releases them. */
enum class LiquifyFreezeMode(val label: String) { FREEZE("Freeze"), THAW("Thaw") }

/**
 * The Liquify workspace as a tool: the first stroke opens an engine [LiquifySession], every gesture previews on the
 * canvas without history, and OK commits one "Liquify" step while Cancel (Esc) restores the layer. Switching tools commits.
 */
class LiquifyToolController(
    private val beginSession: () -> LiquifySession?,
    private val onResult: (String, CommandResult) -> Unit,
    private val rejectStroke: (String) -> Unit,
) : ToolController {
    override val tool: ToolId = ToolId.LIQUIFY

    var operation by mutableStateOf(LiquifyTool.FORWARD_WARP)
    var size by mutableFloatStateOf(120f)
    var density by mutableFloatStateOf(0.5f)
    var rate by mutableFloatStateOf(0.8f)

    /** Non-null while the Freeze / Thaw mask brush is selected instead of a warp tool. */
    var freezeMode by mutableStateOf<LiquifyFreezeMode?>(null)
    var showFrozenAreas by mutableStateOf(true)
    var reconstructAmount by mutableFloatStateOf(50f)

    private var session: LiquifySession? = null
    var active by mutableStateOf(false)
        private set

    private val dabs = mutableListOf<LiquifyDab>()
    private val freezePoints = mutableListOf<DocPoint>()
    private val freezeStrokes = mutableStateListOf<Pair<List<DocPoint>, Pair<Float, Boolean>>>()
    private var previous: DocPoint? = null
    private var overflowed = false

    override fun cursor(modifiers: Modifiers): PointerIcon = PointerIcon.Crosshair

    private fun ensureSession(): LiquifySession? {
        session?.let { return it }
        val started = beginSession() ?: return null
        session = started
        active = true
        return started
    }

    override fun onPointer(event: ToolPointerEvent, ctx: ToolContext): Boolean {
        when (event.phase) {
            PointerPhase.DOWN -> {
                clearGesture()
                append(event, ctx)
            }

            PointerPhase.MOVE -> append(event, ctx)

            PointerPhase.UP -> {
                append(event, ctx)
                finishGesture()
                clearGesture()
            }

            PointerPhase.CANCEL -> clearGesture()
        }
        return true
    }

    private fun finishGesture() {
        val mode = freezeMode
        if (mode != null) {
            if (freezePoints.isEmpty()) return
            val radius = (size / 2f).coerceAtLeast(1f)
            val points = freezePoints.toList()
            val live = ensureSession() ?: return
            val result = live.paintFreeze(points, radius, mode == LiquifyFreezeMode.FREEZE)
            onResult("${mode.label} Liquify mask", result)
            if (result is CommandResult.Accepted) freezeStrokes += points to (radius to (mode == LiquifyFreezeMode.FREEZE))
            return
        }
        if (overflowed) {
            rejectStroke("Liquify gesture exceeded the engine sample limit; use a shorter gesture")
        } else if (dabs.isNotEmpty()) {
            val live = ensureSession() ?: return
            onResult("Liquify stroke", live.stroke(LiquifyStroke(operation, dabs.toList())))
        }
    }

    private fun append(event: ToolPointerEvent, ctx: ToolContext) {
        if (overflowed) return
        val incoming = event.samples.map { sample ->
            val point = ctx.viewport.viewToDoc(ViewPoint(sample.x, sample.y))
            point to sample.pressure.coerceIn(0f, 1f)
        }.toMutableList()
        if (incoming.isEmpty() || incoming.last().first != event.doc) incoming += event.doc to event.pressure.coerceIn(0f, 1f)
        incoming.forEach { (point, pressure) ->
            if (freezeMode != null) {
                if (freezePoints.size < MAX_FREEZE_POINTS) freezePoints += point
                return@forEach
            }
            if (dabs.size == 65_536) {
                overflowed = true
                dabs.clear()
                previous = null
                return
            }
            val last = previous
            dabs += LiquifyDab(
                x = point.x.toDouble(),
                y = point.y.toDouble(),
                size = this@LiquifyToolController.size.toDouble(),
                density = density,
                pressure = if (pressure <= 0f) 1f else pressure,
                rate = rate,
                dx = last?.let { (point.x - it.x).toDouble() } ?: 0.0,
                dy = last?.let { (point.y - it.y).toDouble() } ?: 0.0,
            )
            previous = point
        }
    }

    private fun clearGesture() {
        dabs.clear()
        freezePoints.clear()
        previous = null
        overflowed = false
    }

    override fun onFocusLost() = clearGesture()

    /** OK: commits the preview as one history step. Returns true when there was nothing to commit or the engine accepted it. */
    fun commit(): Boolean {
        val live = session ?: return true
        val result = live.commit()
        onResult("Liquify", result)
        endSession()
        return result is CommandResult.Accepted
    }

    /** Cancel / Esc: restores the layer. */
    fun cancel() {
        session?.cancel()
        endSession()
    }

    private fun endSession() {
        session = null
        active = false
        freezeStrokes.clear()
        clearGesture()
    }

    private fun reconstructAll() {
        val live = ensureSession() ?: return
        onResult("Reconstruct Liquify", live.reconstructAll((reconstructAmount / 100f).coerceIn(0f, 1f)))
    }

    private fun clearFreeze() {
        val live = session ?: return
        val result = live.clearFreeze()
        onResult("Clear Liquify freeze", result)
        if (result is CommandResult.Accepted) freezeStrokes.clear()
    }

    @Composable
    override fun Options() {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            LiquifyTool.entries.forEach { choice ->
                AeButton(
                    choice.label(),
                    onClick = {
                        freezeMode = null
                        operation = choice
                    },
                    style = if (freezeMode == null && operation == choice) AeButtonStyle.Primary else AeButtonStyle.Secondary,
                )
            }
            LiquifyFreezeMode.entries.forEach { mode ->
                AeButton(
                    mode.label,
                    onClick = { freezeMode = mode },
                    style = if (freezeMode == mode) AeButtonStyle.Primary else AeButtonStyle.Secondary,
                )
            }
            AeSlider("Size", size, { size = it }, Modifier.width(180.dp), range = 1f..5000f, defaultValue = 120f, step = 1f)
            AeSlider("Density", density * 100f, { density = it / 100f }, Modifier.width(160.dp), range = 0f..100f, defaultValue = 50f)
            AeSlider(
                if (operation == LiquifyTool.RECONSTRUCT) "Reconstruct strength" else "Rate",
                rate * 100f,
                { rate = it / 100f },
                Modifier.width(180.dp),
                range = 0f..100f,
                defaultValue = 80f,
            )
            AeSlider("Reconstruct all", reconstructAmount, { reconstructAmount = it }, Modifier.width(180.dp), range = 0f..100f, defaultValue = 50f)
            AeButton("Reconstruct", ::reconstructAll, style = AeButtonStyle.Ghost)
            AeButton("Clear freeze", ::clearFreeze, style = AeButtonStyle.Ghost, enabled = freezeStrokes.isNotEmpty())
            AeButton(if (showFrozenAreas) "Hide frozen" else "Show frozen", { showFrozenAreas = !showFrozenAreas }, style = AeButtonStyle.Ghost)
            AeButton("Cancel", ::cancel, style = AeButtonStyle.Ghost, enabled = active)
            AeButton("OK", { commit() }, style = AeButtonStyle.Primary, enabled = active)
        }
    }

    @Composable
    override fun Overlay(viewport: ViewportState, hover: ViewPoint?) {
        val c = Ae.colors
        Canvas(Modifier.fillMaxSize()) {
            if (showFrozenAreas) {
                freezeStrokes.forEach { (points, spec) ->
                    val (radius, frozen) = spec
                    val color = if (frozen) Color(0x66E5484D) else Color(0x663DDC84)
                    for (i in points.indices) {
                        val a = viewport.docToView(points[i])
                        if (i == 0) {
                            drawCircle(color, radius * viewport.zoom, Offset(a.x, a.y))
                        } else {
                            val b = viewport.docToView(points[i - 1])
                            drawLine(color, Offset(b.x, b.y), Offset(a.x, a.y), radius * 2f * viewport.zoom, StrokeCap.Round)
                        }
                    }
                }
            }
            val point = hover ?: return@Canvas
            val radius = (this@LiquifyToolController.size * viewport.zoom / 2f).coerceIn(1f, this.size.maxDimension)
            drawCircle(Color.Black.copy(alpha = 0.8f), radius, Offset(point.x, point.y), style = Stroke(2f))
            drawCircle(c.textPrimary, radius, Offset(point.x, point.y), style = Stroke(1f))
        }
    }

    private companion object {
        const val MAX_FREEZE_POINTS = 4096
    }
}

private fun LiquifyTool.label(): String = name.lowercase().split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
