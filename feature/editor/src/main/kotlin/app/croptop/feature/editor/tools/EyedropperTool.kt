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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.component.AeDropdown
import app.croptop.core.designsystem.component.AeText
import app.croptop.core.designsystem.theme.Ae
import app.croptop.core.engine.api.SampleMode
import app.croptop.core.input.Modifiers
import app.croptop.core.model.DocPoint
import app.croptop.core.model.RgbaColor
import app.croptop.core.model.ToolId
import app.croptop.core.model.ViewPoint
import app.croptop.core.model.ViewportState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Eyedropper (I): click samples into the foreground color, Alt-click into the background;
 * dragging updates live and the final color is added to recent colors.
 */
class EyedropperTool(
    private val sample: (DocPoint, SampleMode, Int) -> RgbaColor?,
    private val onForeground: (RgbaColor) -> Unit,
    private val onBackground: (RgbaColor) -> Unit,
    private val onCommit: (RgbaColor) -> Unit,
    private val activeColor: (background: Boolean) -> RgbaColor,
) : ToolController {
    override val tool = ToolId.EYEDROPPER
    var onActionCompleted: (String) -> Unit = {}
    var mode by mutableStateOf(SampleMode.MERGED)
    var sampleSize by mutableStateOf(1)
    private var sampled by mutableStateOf<RgbaColor?>(null)
    private var previous by mutableStateOf<RgbaColor?>(null)
    private var last: RgbaColor? = null
    private var lastSampledPoint: DocPoint? = null
    private var targetBackground = false
    private val pendingSamples = Channel<SampleRequest>(Channel.CONFLATED)
    private var samplerJob: Job? = null
    private var sampleDispatcher: CoroutineDispatcher = Dispatchers.Default
    private var gestureGeneration = 0L
    private var sampleRequestId = 0L
    private var latestSampleRequestId = 0L
    private var latestRequestedPoint: DocPoint? = null
    private var completedPoint: DocPoint? = null
    private var completedMode: SampleMode? = null
    private var completedSize: Int? = null
    private var completedColor: RgbaColor? = null

    private data class SampleRequest(
        val generation: Long,
        val id: Long,
        val point: DocPoint,
        val mode: SampleMode,
        val size: Int,
        val targetBackground: Boolean,
        val finishGesture: Boolean,
    )

    /** Serializes native pixel reads off the pointer thread and drops stale intermediate points. */
    fun useAsyncSampling(scope: CoroutineScope, dispatcher: CoroutineDispatcher = Dispatchers.Default) {
        check(samplerJob == null) { "Eyedropper async sampling is already active" }
        sampleDispatcher = dispatcher
        samplerJob = scope.launch {
            for (request in pendingSamples) {
                if (request.generation != gestureGeneration) continue
                val cached = request.point == completedPoint && request.mode == completedMode && request.size == completedSize
                var color = if (cached) {
                    completedColor
                } else {
                    withContext(sampleDispatcher) { sample(request.point, request.mode, request.size) }
                }
                if (color == null && request.finishGesture && request.generation == gestureGeneration) {
                    delay(16)
                    color = withContext(sampleDispatcher) { sample(request.point, request.mode, request.size) }
                }
                if (request.generation != gestureGeneration) continue
                if (color != null) {
                    completedPoint = request.point
                    completedMode = request.mode
                    completedSize = request.size
                    completedColor = color
                }
                if (request.id != latestSampleRequestId) continue
                if (color != null) {
                    last = color
                    lastSampledPoint = request.point
                    sampled = color
                    if (request.targetBackground) onBackground(color) else onForeground(color)
                }
                if (request.finishGesture) {
                    color?.let(onCommit)
                    last = null
                }
            }
        }
    }

    /** Closes the bounded sampler queue when the editor leaves composition. */
    fun close() {
        gestureGeneration++
        pendingSamples.close()
        samplerJob?.cancel()
        samplerJob = null
    }

    internal val ringPreview: Pair<RgbaColor, RgbaColor>?
        get() = sampled?.let { current -> previous?.let { before -> current to before } }

    /** Pointer leave and cancellation hide the transient preview; pointer-up leaves it visible. */
    fun clearRing() {
        gestureGeneration++
        sampled = null
        previous = null
        last = null
        lastSampledPoint = null
        latestRequestedPoint = null
    }

    override fun cursor(modifiers: Modifiers): PointerIcon = PointerIcon.Crosshair

    override fun onPointer(event: ToolPointerEvent, ctx: ToolContext): Boolean {
        if (event.phase == PointerPhase.CANCEL) {
            clearRing()
            return true
        }
        val p = event.doc
        val inside = p.x >= 0 && p.y >= 0 && p.x < ctx.document.width && p.y < ctx.document.height
        var targetChanged = false
        if (event.phase == PointerPhase.DOWN) {
            gestureGeneration++
            targetBackground = event.modifiers.alt
            previous = activeColor(targetBackground)
            sampled = null
            last = null
            lastSampledPoint = null
            latestRequestedPoint = null
            completedPoint = null
            completedMode = null
            completedSize = null
            completedColor = null
        } else if (event.modifiers.alt != targetBackground) {
            targetBackground = event.modifiers.alt
            previous = activeColor(targetBackground)
            targetChanged = true
        }
        if (samplerJob != null) {
            if (inside) {
                enqueueSample(p, event.modifiers.alt, event.phase == PointerPhase.UP)
            } else if (event.phase == PointerPhase.UP) {
                latestRequestedPoint?.let { enqueueSample(it, targetBackground, finishGesture = true) }
                if (latestRequestedPoint == null) last?.let(onCommit).also { last = null }
            }
        } else {
            if (inside && (event.phase != PointerPhase.UP || p != lastSampledPoint || targetChanged)) {
                sample(p, mode, sampleSize)?.let { c ->
                    last = c
                    lastSampledPoint = p
                    sampled = c
                    if (event.modifiers.alt) onBackground(c) else onForeground(c)
                }
            }
            if (event.phase == PointerPhase.UP) last?.let(onCommit).also { last = null }
        }
        return true
    }

    private fun enqueueSample(point: DocPoint, background: Boolean, finishGesture: Boolean) {
        val id = ++sampleRequestId
        latestSampleRequestId = id
        latestRequestedPoint = point
        pendingSamples.trySend(
            SampleRequest(gestureGeneration, id, point, mode, sampleSize, background, finishGesture),
        )
    }

    @Composable
    override fun Overlay(viewport: ViewportState, hover: ViewPoint?) {
        val at = hover ?: return
        val current = sampled ?: return
        val before = previous ?: return
        Canvas(Modifier.fillMaxSize()) {
            val diameter = 40.dp.toPx()
            val radius = diameter / 2f
            val center = Offset(at.x + 25.dp.toPx(), at.y + 25.dp.toPx())
            val topLeft = Offset(center.x - radius, center.y - radius)
            val size = androidx.compose.ui.geometry.Size(diameter, diameter)
            drawCircle(Color.White, radius + 2.dp.toPx(), center)
            drawArc(current.toComposeColor(), -90f, 180f, true, topLeft, size)
            drawArc(before.toComposeColor(), 90f, 180f, true, topLeft, size)
            drawCircle(Color.White, radius, center, style = Stroke(2.dp.toPx()))
            drawCircle(Color.Black, radius, center, style = Stroke(0.8.dp.toPx()))
            drawLine(Color.Black, Offset(center.x, center.y - radius), Offset(center.x, center.y + radius), 0.8.dp.toPx())
        }
    }

    @Composable
    override fun Options() {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AeText("Sample", style = Ae.type.caption, color = Ae.colors.textSecondary)
            AeDropdown(
                SampleMode.entries,
                mode,
                {
                    mode = it
                    onActionCompleted("Set eyedropper sample source to ${it.name.lowercase().replace('_', ' ')}")
                },
                {
                    when (it) {
                        SampleMode.MERGED -> "All layers"
                        SampleMode.ACTIVE_LAYER -> "Current layer"
                        SampleMode.ALL_LAYERS -> "All layers (no adjustments)"
                    }
                },
            )
            AeText("Size", style = Ae.type.caption, color = Ae.colors.textSecondary)
            AeDropdown(
                SAMPLE_SIZES,
                sampleSize,
                {
                    sampleSize = it
                    onActionCompleted("Set eyedropper sample size to $it×$it px")
                },
                { if (it == 1) "1 × 1 px" else "$it × $it px" },
            )
            AeText("Alt-click sets the background color", style = Ae.type.caption, color = Ae.colors.textTertiary)
        }
    }

    private fun RgbaColor.toComposeColor() = Color(r, g, b, a)

    private companion object {
        val SAMPLE_SIZES = listOf(1, 3, 5, 11, 31, 51, 101)
    }
}
