package app.croptop.feature.editor.tools

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.unit.dp
import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.StrokeSampleLayout
import app.croptop.core.engine.api.StrokeSession
import app.croptop.core.input.Modifiers
import app.croptop.core.input.PointerKind
import app.croptop.core.input.PointerSample
import app.croptop.core.model.BrushDabs
import app.croptop.core.model.BrushSample
import app.croptop.core.model.BrushSettings
import app.croptop.core.model.Curve
import app.croptop.core.model.Dab
import app.croptop.core.model.RgbaColor
import app.croptop.core.model.SmoothingMode
import app.croptop.core.model.ToolId
import app.croptop.core.model.ViewPoint
import app.croptop.core.model.ViewportState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Coalesces high-rate pointer updates to one preview calculation per display frame. */
internal class BrushPreviewRefreshGate(private val intervalNanos: Long = 16_666_667L) {
    private var lastRefreshNanos = Long.MIN_VALUE

    fun shouldRefresh(nowNanos: Long, force: Boolean = false): Boolean {
        if (force || lastRefreshNanos == Long.MIN_VALUE || nowNanos - lastRefreshNanos >= intervalNanos) {
            lastRefreshNanos = nowNanos
            return true
        }
        return false
    }
}

/** Invalidates an in-flight off-main preview when its pointer stroke is canceled or replaced. */
internal class BrushPreviewGeneration {
    var current: Long = 0L
        private set

    fun next(): Long = ++current

    fun isCurrent(generation: Long): Boolean = current == generation
}

internal data class BrushPreviewFrame(val actual: List<Dab>, val visible: List<Dab>)

/** Mirrors native rope/average input filtering for the active and handoff previews. */
internal fun stabilizedBrushPreviewSamples(
    settings: BrushSettings,
    samples: List<BrushSample>,
    finish: Boolean = false,
): List<BrushSample> {
    if (samples.isEmpty()) return emptyList()
    val normalized = settings.normalized()
    val ropeLength =
        if (normalized.smoothingMode == SmoothingMode.ROPE) {
            min(1000f, normalized.smoothing * normalized.size)
        } else {
            0f
        }
    val averageWindow =
        if (normalized.smoothingMode == SmoothingMode.AVERAGE) {
            (normalized.smoothing * 16f).roundToInt()
        } else {
            0
        }
    val output = mutableListOf<BrushSample>()
    val recent = mutableListOf<BrushSample>()
    var brush: BrushSample? = null
    var lastPointer: BrushSample? = null

    fun average(point: BrushSample) {
        if (averageWindow <= 1) {
            output += point
            return
        }
        recent += point
        if (recent.size > averageWindow) recent.removeAt(0)
        var weightTotal = 0f
        var xTotal = 0f
        var yTotal = 0f
        recent.forEachIndexed { index, sample ->
            val weight = (index + 1).toFloat()
            weightTotal += weight
            xTotal += sample.x * weight
            yTotal += sample.y * weight
        }
        output += point.copy(x = xTotal / weightTotal, y = yTotal / weightTotal)
    }

    fun accept(pointer: BrushSample) {
        lastPointer = pointer
        val currentBrush = brush
        if (currentBrush == null || ropeLength <= 0f) {
            brush = pointer
            average(pointer)
            return
        }
        val dx = pointer.x - currentBrush.x
        val dy = pointer.y - currentBrush.y
        val distance = hypot(dx, dy)
        if (distance <= ropeLength) return
        val pull = (distance - ropeLength) / distance
        brush = pointer.copy(x = currentBrush.x + dx * pull, y = currentBrush.y + dy * pull)
        average(checkNotNull(brush))
    }

    samples.forEach(::accept)
    if (finish) {
        val pointer = checkNotNull(lastPointer)
        val currentBrush = checkNotNull(brush)
        var flushBrush = currentBrush
        if (ropeLength > 0f && (currentBrush.x != pointer.x || currentBrush.y != pointer.y)) {
            brush = pointer
            flushBrush = pointer
            average(pointer)
        }
        repeat((averageWindow - 1).coerceAtLeast(0)) { average(flushBrush) }
    }
    return output
}

/** Keep post-UP commit previews actual-only while extending the active overlay with future samples. */
internal fun brushPreviewFrame(settings: BrushSettings, actual: List<BrushSample>, predicted: List<BrushSample>): BrushPreviewFrame {
    val actualDabs = BrushDabs.dabs(settings, stabilizedBrushPreviewSamples(settings, actual))
    val visibleSamples = if (predicted.isEmpty()) actual else actual + predicted
    val visibleDabs = if (predicted.isEmpty()) {
        actualDabs
    } else {
        BrushDabs.dabs(settings, stabilizedBrushPreviewSamples(settings, visibleSamples))
    }
    return BrushPreviewFrame(actualDabs, visibleDabs)
}

/** Holds the last visible stroke dabs across UP until the committed canvas replaces the overlay. */
internal class PendingBrushPreviewDabs {
    private var nextId = 0L
    var values by mutableStateOf<Map<Long, List<Dab>>>(emptyMap())
        private set

    fun begin(initial: List<Dab>): Long {
        val id = ++nextId
        values = values + (id to initial)
        return id
    }

    fun update(id: Long, dabs: List<Dab>) {
        if (id in values) values = values + (id to dabs)
    }

    fun finish(id: Long) {
        values = values - id
    }
}

/**
 * Brush (B) / Eraser (E): streams pointer samples into the engine's [StrokeSession] (one history step
 * per stroke). While drawing, the stroke is previewed with the same dab rules as the engine
 * ([BrushDabs]); on hover a circle shows the brush size.
 */
class BrushTool(
    override val tool: ToolId,
    private val erase: Boolean,
    private val settings: () -> BrushSettings,
    private val color: () -> RgbaColor,
    private val begin: (BrushSettings, Boolean) -> StrokeSession?,
    private val onResult: (CommandResult) -> Unit,
    private val pressureCurve: () -> Curve = { Curve.Identity },
    private val onCancel: () -> Unit = {},
) : ToolController {
    private data class PendingPreview(
        val id: Long,
        val settings: BrushSettings,
        val color: RgbaColor,
        val samples: List<BrushSample>,
    )

    /** Production commits rasterize dabs on a worker; keep the preview until that commit completes. */
    var endStrokeOffMain: ((StrokeSession, (CommandResult) -> Unit) -> Unit)? = null
    var precisionCursor by mutableStateOf(false)
    private var session: StrokeSession? = null
    private val samples = mutableListOf<BrushSample>()
    private val predictedSamples = mutableListOf<BrushSample>()
    private var previewRevision by mutableIntStateOf(0)
    private var previewDabs by mutableStateOf<List<Dab>>(emptyList())
    private var actualPreviewDabs by mutableStateOf<List<Dab>>(emptyList())
    private val pendingPreviews = mutableStateListOf<PendingPreview>()
    private val pendingPreviewDabs = PendingBrushPreviewDabs()
    private val previewRefreshGate = BrushPreviewRefreshGate()
    private var startTime = 0L
    private var startEventTimeMillis = 0L
    private val strokeGeneration = BrushPreviewGeneration()

    internal fun samplesForOverlay(): List<BrushSample> = samples + predictedSamples

    /** Caps Lock toggles precision crosshair once on key-up, never on key repeat/down. */
    fun handlePrecisionCursorKey(key: Key, type: KeyEventType): Boolean {
        if (key != Key.CapsLock) return false
        if (type == KeyEventType.KeyUp) precisionCursor = !precisionCursor
        return true
    }

    override fun cursor(modifiers: Modifiers): PointerIcon = PointerIcon.Crosshair

    override fun onPointer(event: ToolPointerEvent, ctx: ToolContext): Boolean {
        when (event.phase) {
            PointerPhase.DOWN -> {
                strokeGeneration.next()
                predictedSamples.clear()
                val started = begin(settings(), erase)
                session = started
                startTime = System.nanoTime()
                startEventTimeMillis = event.samples.firstOrNull()?.timeMillis ?: event.eventTimeMillis
                if (started == null) {
                    // Don't show ink that cannot be committed (for example, on a locked Background).
                    clearPreview()
                } else {
                    val firstSamples = samples(event, ctx)
                    samples.clear()
                    samples.addAll(firstSamples)
                    refreshPreview(force = true)
                    started.add(pack(firstSamples))
                }
            }

            PointerPhase.MOVE -> session?.let { s ->
                val next = samples(event, ctx)
                samples.addAll(next)
                predictedSamples.clear()
                predictedSamples.addAll(convertSamples(event.predictedSamples, event.pointerKind, ctx))
                refreshPreview()
                s.add(pack(next))
            }

            PointerPhase.UP -> {
                val ending = session
                ending?.let {
                    predictedSamples.clear()
                    // Some input backends deliver release at a newer location than the last MOVE.
                    // Include it so the committed stroke reaches the actual pointer endpoint.
                    val terminal = samples(event, ctx)
                    samples.addAll(terminal)
                    refreshPreview(force = true)
                    it.add(pack(terminal))
                    val finishOffMain = endStrokeOffMain
                    val pending = if (finishOffMain != null) {
                        val id = pendingPreviewDabs.begin(actualPreviewDabs)
                        PendingPreview(id, settings(), color(), samples.toList()).also(pendingPreviews::add)
                    } else {
                        null
                    }
                    val completed: (CommandResult) -> Unit = { result ->
                        onResult(result)
                        if (pending != null) {
                            pendingPreviews.removeAll { it.id == pending.id }
                            pendingPreviewDabs.finish(pending.id)
                            refreshPreview(force = true)
                        }
                    }
                    if (finishOffMain == null) {
                        completed(it.end())
                        clearPreview()
                    } else {
                        clearActivePreview()
                        finishOffMain(it, completed)
                    }
                }
                session = null
                if (ending == null) clearPreview()
            }

            PointerPhase.CANCEL -> {
                strokeGeneration.next()
                val canceledSession = session
                canceledSession?.cancel()
                if (canceledSession != null) onCancel()
                session = null
                predictedSamples.clear()
                clearPreview()
            }
        }
        return true
    }

    private fun samples(e: ToolPointerEvent, ctx: ToolContext): List<BrushSample> = e.samples.takeIf { it.isNotEmpty() }?.map { raw ->
        convertSample(raw, e.pointerKind, ctx)
    } ?: listOf(
        BrushSample(
            e.doc.x,
            e.doc.y,
            // Mouse and touch report no meaningful pressure; preserve full-strength behavior.
            pressure = if (e.pressure <= 0f) 1f else e.pressure.coerceIn(0f, 1f),
            time = (System.nanoTime() - startTime) / 1e9f,
        ),
    )

    private fun convertSamples(raw: List<PointerSample>, kind: PointerKind, ctx: ToolContext): List<BrushSample> =
        raw.map { convertSample(it, kind, ctx) }

    private fun convertSample(raw: PointerSample, kind: PointerKind, ctx: ToolContext): BrushSample {
        val documentPoint = ctx.viewport.viewToDoc(ViewPoint(raw.x, raw.y))
        val pressure = if (kind == PointerKind.STYLUS || kind == PointerKind.ERASER) {
            pressureCurve().evaluate(raw.pressure.coerceIn(0f, 1f))
        } else {
            raw.pressure
        }
        val tiltMagnitude = (raw.tilt / (PI.toFloat() / 2f)).coerceIn(0f, 1f)
        val orientation = raw.orientation
        return BrushSample(
            x = documentPoint.x,
            y = documentPoint.y,
            pressure = if (pressure <= 0f && kind != PointerKind.STYLUS && kind != PointerKind.ERASER) 1f else pressure,
            tilt = tiltMagnitude,
            time = ((raw.timeMillis - startEventTimeMillis).coerceAtLeast(0L) / 1_000f),
            tiltX = tiltMagnitude * cos(orientation),
            tiltY = tiltMagnitude * sin(orientation),
            orientation = orientation,
        )
    }

    /** x, y, pressure, tiltX, tiltY, orientation, time (StrokeSession record layout). */
    private fun pack(samples: List<BrushSample>): FloatArray = FloatArray(samples.size * StrokeSampleLayout.STRIDE).also { packed ->
        samples.forEachIndexed { index, sample ->
            val offset = index * StrokeSampleLayout.STRIDE
            packed[offset + StrokeSampleLayout.X] = sample.x
            packed[offset + StrokeSampleLayout.Y] = sample.y
            packed[offset + StrokeSampleLayout.PRESSURE] = sample.pressure
            packed[offset + StrokeSampleLayout.TILT_X] = sample.tiltX
            packed[offset + StrokeSampleLayout.TILT_Y] = sample.tiltY
            packed[offset + StrokeSampleLayout.ORIENTATION] = sample.orientation
            packed[offset + StrokeSampleLayout.TIME_SECONDS] = sample.time
        }
    }

    private fun refreshPreview(force: Boolean = false) {
        if (previewRefreshGate.shouldRefresh(System.nanoTime(), force)) previewRevision++
    }

    private fun clearActivePreview() {
        strokeGeneration.next()
        samples.clear()
        predictedSamples.clear()
        previewDabs = emptyList()
        actualPreviewDabs = emptyList()
        refreshPreview(force = true)
    }

    private fun clearPreview() {
        clearActivePreview()
    }

    @Composable
    override fun Overlay(viewport: ViewportState, hover: ViewPoint?) {
        val s = settings()
        val revision = previewRevision
        val generation = strokeGeneration.current
        val pendingSnapshot = pendingPreviews.toList()
        LaunchedEffect(revision, generation, s) {
            val actualSnapshot = samples.toList()
            val provisionalSnapshot = predictedSamples.toList()
            val frame = withContext(Dispatchers.Default) { brushPreviewFrame(s, actualSnapshot, provisionalSnapshot) }
            if (previewRevision == revision && strokeGeneration.isCurrent(generation) && settings() == s) {
                actualPreviewDabs = frame.actual
                previewDabs = frame.visible
            }
        }
        LaunchedEffect(pendingSnapshot) {
            val renderedPending = withContext(Dispatchers.Default) {
                pendingSnapshot.associate {
                    it.id to BrushDabs.dabs(it.settings, stabilizedBrushPreviewSamples(it.settings, it.samples, finish = true))
                }
            }
            renderedPending.forEach(pendingPreviewDabs::update)
        }
        val pts = previewDabs
        val paint = if (erase) Color.White.copy(alpha = 0.6f) else color().let { Color(it.r, it.g, it.b, it.a) }
        val pendingSnapshotForDraw = pendingPreviews.toList()
        Canvas(Modifier.fillMaxSize()) {
            fun drawDabs(dabs: List<Dab>, brushSettings: BrushSettings, brushColor: Color) {
                dabs.forEach { d ->
                    val c = viewport.docToView(app.croptop.core.model.DocPoint(d.x, d.y))
                    val r = (d.diameter / 2f * viewport.zoom).coerceAtLeast(0.5f)
                    val hard = brushSettings.hardness.coerceIn(0f, 0.999f)
                    val col = brushColor.copy(alpha = brushColor.alpha * d.alpha)
                    val center = Offset(c.x, c.y)
                    val brush = Brush.radialGradient(0f to col, hard to col, 1f to col.copy(alpha = 0f), center = center, radius = r)
                    rotate(d.angle + viewport.rotationDegrees, center) { scale(1f, d.roundness, center) { drawCircle(brush, r, center) } }
                }
            }
            pendingSnapshotForDraw.forEach { pending ->
                val pendingColor = if (erase) {
                    Color.White.copy(
                        alpha = 0.6f,
                    )
                } else {
                    Color(pending.color.r, pending.color.g, pending.color.b, pending.color.a)
                }
                drawDabs(pendingPreviewDabs.values[pending.id].orEmpty(), pending.settings, pendingColor)
            }
            drawDabs(pts, s, paint)
            // Brush-size cursor, drawn black-on-white so it reads on any image.
            if (hover != null && samples.isEmpty()) {
                val at = Offset(hover.x, hover.y)
                if (precisionCursor) {
                    val arm = 7.dp.toPx()
                    drawLine(Color.White, Offset(at.x - arm, at.y), Offset(at.x + arm, at.y), 2.dp.toPx())
                    drawLine(Color.White, Offset(at.x, at.y - arm), Offset(at.x, at.y + arm), 2.dp.toPx())
                    drawLine(Color.Black, Offset(at.x - arm, at.y), Offset(at.x + arm, at.y), 0.75.dp.toPx())
                    drawLine(Color.Black, Offset(at.x, at.y - arm), Offset(at.x, at.y + arm), 0.75.dp.toPx())
                } else {
                    val r = (s.size / 2f * viewport.zoom).coerceAtLeast(1f)
                    drawCircle(Color.White, r, at, style = Stroke(1.5.dp.toPx()))
                    drawCircle(Color.Black, r, at, style = Stroke(0.75.dp.toPx()))
                }
            }
        }
    }
}
