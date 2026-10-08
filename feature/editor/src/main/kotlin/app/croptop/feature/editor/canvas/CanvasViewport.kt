package app.croptop.feature.editor.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.input.motionprediction.MotionEventPredictor
import app.croptop.core.engine.api.OverlayState
import app.croptop.core.input.CanvasGesture
import app.croptop.core.input.CanvasPointerAction
import app.croptop.core.input.Modifiers
import app.croptop.core.input.MotionEvents
import app.croptop.core.input.PalmRejectionGate
import app.croptop.core.input.PointerKind
import app.croptop.core.input.PointerSample
import app.croptop.core.input.RawScroll
import app.croptop.core.input.ScrollClassifier
import app.croptop.core.input.canvasPointerAction
import app.croptop.core.input.rejectsPalmContact
import app.croptop.core.model.BrushSettings
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocSize
import app.croptop.core.model.StylusBarrelAction
import app.croptop.core.model.ToolId
import app.croptop.core.model.ViewPoint
import app.croptop.core.model.ViewportState
import app.croptop.core.model.WheelBehavior
import app.croptop.feature.editor.tools.PointerPhase
import app.croptop.feature.editor.tools.ToolContext
import app.croptop.feature.editor.tools.ToolController
import app.croptop.feature.editor.tools.ToolPointerEvent
import kotlin.math.pow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val DOUBLE_CLICK_MS = 400L

internal fun initialViewportForSize(
    viewport: ViewportState,
    document: DocSize,
    size: IntSize,
    alreadyFit: Boolean,
): ViewportState {
    val sized = viewport.copy(viewWidth = size.width, viewHeight = size.height)
    return if (alreadyFit || size.width <= 0 || size.height <= 0) sized else sized.fit(document)
}

/** Idempotent cleanup for transient HUD state when a gesture ends or its pointerInput restarts. */
internal class BrushHudCleanup(private val clear: () -> Unit) : AutoCloseable {
    private var closed = false

    override fun close() {
        if (!closed) {
            closed = true
            clear()
        }
    }
}

/** Emits one action result when a viewport gesture changes the canvas and ends normally. */
internal class ViewportGestureFeedback(private val onActionCompleted: (String) -> Unit) {
    private var action: String? = null
    private var changed = false

    fun begin(action: String) {
        this.action = action
        changed = false
    }

    fun update(didChange: Boolean) {
        changed = changed || didChange
    }

    fun finish(cancelled: Boolean = false) {
        if (!cancelled && changed) action?.let(onActionCompleted)
        action = null
        changed = false
    }
}

/** Coalesces high-rate wheel samples into one completion after the scroll burst settles. */
internal class ViewportScrollFeedback(
    private val scope: CoroutineScope,
    private val settleMillis: Long = 350L,
    private val onActionCompleted: (String) -> Unit,
) {
    private var pending: Job? = null

    fun schedule(action: String) {
        pending?.cancel()
        pending = scope.launch {
            delay(settleMillis)
            onActionCompleted(action)
            pending = null
        }
    }
}

/** Guarantees one terminal pointer event even when pointer input is canceled or its ID disappears. */
internal class PointerGestureLifecycle<T>(
    initialEvent: T,
    private val asCancel: (T) -> T,
    private val dispatch: (T) -> Unit,
) {
    private var lastEvent: T? = initialEvent
    private var ended = false

    fun send(event: T) {
        if (ended) return
        lastEvent = event
        dispatch(event)
    }

    fun finish(event: T) {
        if (ended) return
        lastEvent = event
        dispatch(event)
        ended = true
    }

    fun cancel(event: T? = null) {
        if (ended) return
        ended = true
        (event ?: lastEvent)?.let { dispatch(asCancel(it)) }
    }
}

/**
 * The canvas area: hosts the renderer and owns navigation input.
 * - wheel pans; Ctrl+wheel zooms at the cursor; Shift+wheel pans horizontally; Alt+wheel = brush size
 * - trackpad/touch pinch zooms at the centroid, two-finger drag pans
 * - middle-drag, or [panMode] (Space held / Hand tool) + drag, pans
 */
@Composable
fun CanvasViewport(
    viewport: ViewportState,
    onViewportChange: (ViewportState) -> Unit,
    document: DocSize,
    modifier: Modifier = Modifier,
    background: Color,
    panMode: Boolean = false,
    wheel: WheelBehavior = WheelBehavior.SCROLL,
    onBrushSizeSteps: (Float) -> Unit = {},
    brushHudSettings: BrushSettings? = null,
    onBrushHudSettingsChange: (BrushSettings) -> Unit = {},
    tool: ToolController? = null,
    eraserTool: ToolController? = null,
    eyedropperTool: ToolController? = null,
    stylusBarrelAction: StylusBarrelAction = StylusBarrelAction.EYEDROPPER,
    onHover: (ViewPoint?) -> Unit = {},
    onHoverPointerKind: (PointerKind?) -> Unit = {},
    onHoverModifiers: (Modifiers) -> Unit = {},
    interceptor: CanvasInterceptor? = null,
    handle: app.croptop.core.engine.api.DocumentHandle? = null,
    engineOverlays: OverlayState = OverlayState(),
    /** A press with a tool that can't act on the canvas yet (so the editor can say why). */
    onToolUnavailable: () -> Unit = {},
    /** Where (view space) the tap that found no tool landed; called just before [onToolUnavailable]. Touch has no hover. */
    onToolUnavailableAt: (ViewPoint) -> Unit = {},
    /** One-shot host interaction; when present, the next primary canvas press bypasses the active tool. */
    onCanvasPick: ((DocPoint) -> Unit)? = null,
    onActionCompleted: (String) -> Unit = {},
    overlay: @Composable () -> Unit = {},
) {
    val intercept by rememberUpdatedState(interceptor)
    var hoverPoint by remember { mutableStateOf<ViewPoint?>(null) }
    val activeTool by rememberUpdatedState(tool)
    val eraser by rememberUpdatedState(eraserTool)
    val eyedropper by rememberUpdatedState(eyedropperTool)
    val barrelAction by rememberUpdatedState(stylusBarrelAction)
    val unavailable by rememberUpdatedState(onToolUnavailable)
    val unavailableAt by rememberUpdatedState(onToolUnavailableAt)
    val canvasPick by rememberUpdatedState(onCanvasPick)
    val doc by rememberUpdatedState(document)
    val hover by rememberUpdatedState(onHover)
    val hoverKind by rememberUpdatedState(onHoverPointerKind)
    val hoverMods by rememberUpdatedState(onHoverModifiers)
    var hoverModifiers by remember { mutableStateOf(Modifiers.None) }
    var brushHudPosition by remember { mutableStateOf<Offset?>(null) }
    val palmRejection = remember { PalmRejectionGate() }
    val current by rememberUpdatedState(viewport)
    val latestViewport by rememberUpdatedState(viewport)
    val change by rememberUpdatedState(onViewportChange)
    val pan by rememberUpdatedState(panMode)
    val hudSettings by rememberUpdatedState(brushHudSettings)
    val hudChange by rememberUpdatedState(onBrushHudSettingsChange)
    val actionCompleted by rememberUpdatedState(onActionCompleted)
    val viewportScope = rememberCoroutineScope()
    val scrollFeedback = remember(viewportScope) {
        ViewportScrollFeedback(viewportScope) { action -> actionCompleted(action) }
    }
    val host = LocalCanvasHost.current
    val hostView = LocalView.current
    var measuredSize by remember { mutableStateOf(IntSize.Zero) }
    var initialFitApplied by remember(handle?.id, document) { mutableStateOf(false) }
    LaunchedEffect(handle?.id, document, measuredSize) {
        if (initialFitApplied || measuredSize.width <= 0 || measuredSize.height <= 0) return@LaunchedEffect
        // A size callback can report an intermediate constrained measurement. Let two layout
        // frames settle; a newer size restarts this effect before it can consume that old value.
        withFrameNanos { }
        withFrameNanos { }
        change(initialViewportForSize(latestViewport, document, measuredSize, alreadyFit = false))
        initialFitApplied = true
    }
    val screenDensity = androidx.compose.ui.platform.LocalDensity.current.density
    val latestDensity by androidx.compose.runtime.rememberUpdatedState(screenDensity)
    val ctx = remember {
        object : ToolContext {
            override val density: Float get() = latestDensity
            override val viewport: ViewportState get() = current
            override val document: DocSize get() = doc
            override fun updateViewport(state: ViewportState) = change(state)
        }
    }
    val fitted = viewport.viewWidth > 0 && initialFitApplied

    Box(
        modifier
            .clipToBounds()
            .background(background)
            .onSizeChanged { size ->
                measuredSize = size
                // Track the latest usable bounds immediately; the first fit waits for layout to settle.
                change(current.copy(viewWidth = size.width, viewHeight = size.height))
            }
            .pointerHoverIcon(
                when {
                    panMode -> PointerIcon.Hand

                    else -> hoverPoint?.let { interceptor?.hoverCursor(it, hoverModifiers, viewport) ?: tool?.cursorAt(it, hoverModifiers, viewport) } ?: tool?.cursor(hoverModifiers)
                        ?: PointerIcon.Crosshair
                },
            ),
    ) {
        if (fitted) host.Content(viewport, document, handle, Modifier.fillMaxSize(), engineOverlays)
        if (fitted) overlay()
        val hudPosition = brushHudPosition
        val hud = brushHudSettings
        if (hudPosition != null && hud != null) {
            Text(
                "Size ${hud.size.toInt()} px  ·  Hardness ${(hud.hardness * 100).toInt()}%",
                color = Color.White,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.offset { IntOffset(hudPosition.x.toInt() + 16, hudPosition.y.toInt() + 16) }
                    .background(Color(0xDD202124), RoundedCornerShape(2.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        if (fitted) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(wheel) {
                        awaitPointerEventScope {
                            while (true) {
                                val ev = awaitPointerEvent()
                                if (ev.type != PointerEventType.Scroll) continue
                                val ch = ev.changes.first()
                                val km = ev.keyboardModifiers
                                val raw = RawScroll(
                                    x = ch.position.x,
                                    y = ch.position.y,
                                    // Compose reports +y for wheel-down; Android AXIS_VSCROLL is +1 for wheel-up.
                                    vScroll = -ch.scrollDelta.y,
                                    hScroll = ch.scrollDelta.x,
                                    kind = if (ch.type == PointerType.Mouse) PointerKind.MOUSE else PointerKind.TOUCHPAD,
                                    modifiers = Modifiers(km.isCtrlPressed, km.isShiftPressed, km.isAltPressed, km.isMetaPressed),
                                )
                                when (val g = ScrollClassifier.classify(raw, wheel)) {
                                    is CanvasGesture.Pan -> {
                                        change(current.panBy(g.dx, g.dy))
                                        scrollFeedback.schedule("Scroll canvas view")
                                    }

                                    is CanvasGesture.Zoom -> {
                                        change(current.zoomAt(ViewPoint(g.anchorX, g.anchorY), g.factor))
                                        scrollFeedback.schedule("Zoom canvas view")
                                    }

                                    is CanvasGesture.BrushSize -> {
                                        onBrushSizeSteps(g.steps)
                                        scrollFeedback.schedule("Adjust brush size")
                                    }

                                    null -> Unit
                                }
                                ev.changes.forEach { it.consume() }
                            }
                        }
                    }
                    .pointerInput(brushHudSettings != null) {
                        // Alt + right-drag adjusts brush diameter horizontally and hardness vertically.
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val event = currentEvent
                            if (!event.buttons.isSecondaryPressed || !event.keyboardModifiers.isAltPressed || hudSettings == null) {
                                return@awaitEachGesture
                            }
                            var last = down.position
                            brushHudPosition = down.position
                            val cleanup = BrushHudCleanup { brushHudPosition = null }
                            try {
                                down.consume()
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val pointer = ev.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!pointer.pressed) break
                                    val delta = pointer.position - last
                                    last = pointer.position
                                    brushHudPosition = pointer.position
                                    hudSettings?.let { settings ->
                                        val size = (settings.size * 2.0.pow((delta.x / 120f).toDouble()).toFloat()).coerceIn(1f, 5000f)
                                        val hardness = (settings.hardness - delta.y / 120f).coerceIn(0f, 1f)
                                        hudChange(settings.copy(size = size, hardness = hardness))
                                    }
                                    pointer.consume()
                                }
                            } finally {
                                cleanup.close()
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        // Middle-drag always pans; primary-drag pans in pan mode (Space / Hand tool).
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val first = currentEvent.buttons
                            val panning = first.isTertiaryPressed || (pan && (first.isPrimaryPressed || down.type != PointerType.Mouse))
                            if (!panning) return@awaitEachGesture
                            val feedback = ViewportGestureFeedback(actionCompleted)
                            feedback.begin("Pan canvas view")
                            var last = down.position
                            var released = false
                            while (true) {
                                val ev = awaitPointerEvent()
                                val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!c.pressed) {
                                    released = true
                                    break
                                }
                                val d = c.position - last
                                last = c.position
                                feedback.update(d.x != 0f || d.y != 0f)
                                change(current.panBy(d.x, d.y))
                                c.consume()
                            }
                            feedback.finish(cancelled = !released)
                        }
                    }
                    .pointerInput(Unit) {
                        // Hover: cursor position for the status bar/info panel, modifier-dependent cursors.
                        awaitPointerEventScope {
                            while (true) {
                                val ev = awaitPointerEvent()
                                val motion = ev.motionEvent
                                val contact = ev.changes.firstOrNull()
                                val motionKind = motion?.let { MotionEvents.kind(it) }
                                val kind = motionKind?.takeUnless { it == PointerKind.UNKNOWN } ?: when (contact?.type) {
                                    PointerType.Touch -> PointerKind.FINGER
                                    PointerType.Mouse -> PointerKind.MOUSE
                                    PointerType.Stylus -> PointerKind.STYLUS
                                    else -> PointerKind.UNKNOWN
                                }
                                if (kind == PointerKind.STYLUS || kind == PointerKind.ERASER) {
                                    if (motion?.actionMasked == android.view.MotionEvent.ACTION_HOVER_EXIT) {
                                        palmRejection.observeStylusExit(ev.changes.firstOrNull()?.uptimeMillis ?: motion.eventTime)
                                    } else {
                                        palmRejection.observe(kind, ev.changes.firstOrNull()?.uptimeMillis ?: motion?.eventTime ?: 0L)
                                    }
                                }
                                val km = ev.keyboardModifiers
                                hoverModifiers = Modifiers(km.isCtrlPressed, km.isShiftPressed, km.isAltPressed, km.isMetaPressed)
                                when (ev.type) {
                                    PointerEventType.Exit -> {
                                        hoverPoint = null
                                        hover(null)
                                        hoverKind(null)
                                        hoverMods(Modifiers.None)
                                    }

                                    else -> contact?.let {
                                        hoverPoint = ViewPoint(it.position.x, it.position.y)
                                        hover(hoverPoint)
                                        hoverKind(kind)
                                        val km = ev.keyboardModifiers
                                        hoverMods(Modifiers(km.isCtrlPressed, km.isShiftPressed, km.isAltPressed, km.isMetaPressed))
                                    }
                                }
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        // Primary button / pen / single finger → active tool (unless panning).
                        var lastUp = 0L
                        var clicks = 0
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = true)
                            val buttons = currentEvent.buttons
                            clicks = if (down.uptimeMillis - lastUp < DOUBLE_CLICK_MS) clicks + 1 else 1
                            val origin = ViewPoint(down.position.x, down.position.y)
                            fun event(phase: PointerPhase, c: androidx.compose.ui.input.pointer.PointerInputChange): ToolPointerEvent {
                                val km = currentEvent.keyboardModifiers
                                val vp = ViewPoint(c.position.x, c.position.y)
                                val motion = currentEvent.motionEvent
                                val pointerIndex = motion?.findPointerIndex(c.id.value.toInt())?.takeIf { it >= 0 } ?: 0
                                val kind = motion?.let { MotionEvents.kind(it, pointerIndex) } ?: PointerKind.UNKNOWN
                                val pointerSamples = motion?.let { raw ->
                                    val xOffset = vp.x - raw.getX(pointerIndex)
                                    val yOffset = vp.y - raw.getY(pointerIndex)
                                    MotionEvents.samples(raw, pointerIndex).map { it.copy(x = it.x + xOffset, y = it.y + yOffset) }
                                }.orEmpty()
                                return ToolPointerEvent(
                                    phase,
                                    vp,
                                    current.viewToDoc(vp),
                                    c.pressure,
                                    Modifiers(km.isCtrlPressed, km.isShiftPressed, km.isAltPressed, km.isMetaPressed),
                                    origin,
                                    clicks,
                                    pointerKind = kind,
                                    samples = pointerSamples,
                                    stylusBarrelPressed = motion?.let { MotionEvents.stylusBarrelPressed(it) } == true,
                                    cancelled = motion?.let { MotionEvents.cancelled(it) } == true,
                                    eventTimeMillis = c.uptimeMillis,
                                )
                            }
                            val downEvent = event(PointerPhase.DOWN, down)
                            if (pan || buttons.isTertiaryPressed) return@awaitEachGesture
                            if (downEvent.pointerKind == PointerKind.FINGER && palmRejection.rejects(PointerKind.FINGER, down.uptimeMillis)) {
                                down.consume()
                                return@awaitEachGesture
                            }
                            if (downEvent.pointerKind == PointerKind.STYLUS || downEvent.pointerKind == PointerKind.ERASER) {
                                palmRejection.observe(downEvent.pointerKind, down.uptimeMillis)
                            }
                            val pointerAction = canvasPointerAction(downEvent.pointerKind, downEvent.stylusBarrelPressed, barrelAction)
                            val barrelPan = pointerAction == CanvasPointerAction.PAN
                            if (
                                pointerAction != CanvasPointerAction.PAN && buttons.isSecondaryPressed &&
                                downEvent.pointerKind != PointerKind.STYLUS
                            ) {
                                return@awaitEachGesture
                            }
                            // Guides (and later on-canvas handles) get first pick of the gesture.
                            canvasPick?.let { pick ->
                                pick(downEvent.doc)
                                down.consume()
                                return@awaitEachGesture
                            }
                            val t = when {
                                pointerAction == CanvasPointerAction.ERASER -> eraser
                                pointerAction == CanvasPointerAction.EYEDROPPER -> eyedropper
                                else -> activeTool
                            }
                            val captured = !barrelPan && intercept?.onPointer(downEvent, ctx) == true
                            val motionPredictor = if (
                                !barrelPan && !captured && (t?.tool == ToolId.BRUSH || t?.tool == ToolId.ERASER) &&
                                (downEvent.pointerKind == PointerKind.STYLUS || downEvent.pointerKind == PointerKind.ERASER)
                            ) {
                                MotionEventPredictor.newInstance(hostView)
                            } else {
                                null
                            }
                            var predictionStreamValid = motionPredictor != null
                            val downMotion = currentEvent.motionEvent
                            val activeAndroidPointerId = downMotion?.let { raw ->
                                val index = raw.findPointerIndex(down.id.value.toInt()).takeIf { it >= 0 } ?: 0
                                raw.getPointerId(index)
                            }
                            if (motionPredictor != null && downMotion != null) {
                                try {
                                    motionPredictor.record(downMotion)
                                } catch (_: IllegalArgumentException) {
                                    predictionStreamValid = false
                                }
                            }
                            fun recordAndPredict(
                                raw: android.view.MotionEvent?,
                                change: androidx.compose.ui.input.pointer.PointerInputChange,
                            ): List<PointerSample> {
                                val predictor = motionPredictor ?: return emptyList()
                                if (!predictionStreamValid || raw == null) return emptyList()
                                try {
                                    predictor.record(raw)
                                } catch (_: IllegalArgumentException) {
                                    predictionStreamValid = false
                                    return emptyList()
                                }
                                if (!change.pressed || raw.actionMasked != android.view.MotionEvent.ACTION_MOVE) return emptyList()
                                val predicted = try {
                                    predictor.predict()
                                } catch (_: IllegalArgumentException) {
                                    predictionStreamValid = false
                                    null
                                } ?: return emptyList()
                                try {
                                    val rawIndex = raw.findPointerIndex(down.id.value.toInt()).takeIf { it >= 0 } ?: 0
                                    val pointerId = activeAndroidPointerId ?: return emptyList()
                                    val predictedIndex = predicted.findPointerIndex(pointerId)
                                    if (predictedIndex < 0) return emptyList()
                                    val offsetX = change.position.x - raw.getX(rawIndex)
                                    val offsetY = change.position.y - raw.getY(rawIndex)
                                    return MotionEvents.samples(predicted, predictedIndex)
                                        .asSequence()
                                        .filter { it.timeMillis > raw.eventTime }
                                        .map { it.copy(x = it.x + offsetX, y = it.y + offsetY) }
                                        .toList()
                                } finally {
                                    predicted.recycle()
                                }
                            }
                            if (!barrelPan && !captured && t == null) {
                                if (!panMode) {
                                    unavailableAt(ViewPoint(down.position.x, down.position.y))
                                    unavailable()
                                }
                                return@awaitEachGesture
                            }
                            var lastPanPosition = downEvent.view
                            val lifecycle = PointerGestureLifecycle(
                                initialEvent = downEvent,
                                asCancel = { it.copy(phase = PointerPhase.CANCEL, cancelled = true) },
                                dispatch = { pointerEvent ->
                                    if (barrelPan) {
                                        if (pointerEvent.phase == PointerPhase.MOVE || pointerEvent.phase == PointerPhase.UP) {
                                            change(current.panBy(pointerEvent.view.x - lastPanPosition.x, pointerEvent.view.y - lastPanPosition.y))
                                            lastPanPosition = pointerEvent.view
                                        }
                                    } else if (captured) {
                                        intercept?.onPointer(pointerEvent, ctx)
                                    } else {
                                        t?.onPointer(pointerEvent, ctx)
                                    }
                                },
                            )
                            try {
                                if (!captured) lifecycle.send(downEvent)
                                down.consume()
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val c = ev.changes.firstOrNull { it.id == down.id }
                                    if (c == null) {
                                        lifecycle.cancel()
                                        break
                                    }
                                    val pointerEvent = event(if (c.pressed) PointerPhase.MOVE else PointerPhase.UP, c).copy(
                                        predictedSamples = recordAndPredict(ev.motionEvent, c),
                                    )
                                    val allPointersReleased = ev.changes.isNotEmpty() && ev.changes.all { !it.pressed && it.previousPressed }
                                    if (ev.motionEvent == null && allPointersReleased) {
                                        lifecycle.cancel(pointerEvent.copy(phase = PointerPhase.CANCEL, cancelled = true))
                                        break
                                    }
                                    if (pointerEvent.cancelled) {
                                        lifecycle.cancel(pointerEvent.copy(phase = PointerPhase.CANCEL))
                                        break
                                    }
                                    if (pointerEvent.pointerKind == PointerKind.STYLUS || pointerEvent.pointerKind == PointerKind.ERASER) {
                                        palmRejection.observe(pointerEvent.pointerKind, c.uptimeMillis)
                                    }
                                    val motion = ev.motionEvent
                                    val additional = ev.changes.filter { it.id != down.id && it.pressed }
                                    val rejectedPalms = additional.filter { contact ->
                                        val index = motion?.findPointerIndex(contact.id.value.toInt())?.takeIf { it >= 0 } ?: -1
                                        // Compose pointer IDs are not guaranteed to equal Android IDs on every backend.
                                        // Preserve the normalized contact type as a reliable fallback for touch palms.
                                        val kind = when (contact.type) {
                                            PointerType.Touch -> PointerKind.FINGER
                                            PointerType.Mouse -> PointerKind.MOUSE
                                            else -> if (index >= 0) MotionEvents.kind(motion!!, index) else PointerKind.UNKNOWN
                                        }
                                        rejectsPalmContact(pointerEvent.pointerKind, kind, palmRejection, contact.uptimeMillis)
                                    }
                                    rejectedPalms.forEach { it.consume() }
                                    val activeAdditionalCount = additional.size - rejectedPalms.size
                                    if (activeAdditionalCount > 0) {
                                        lifecycle.cancel(pointerEvent.copy(phase = PointerPhase.CANCEL, cancelled = true))
                                        break
                                    }
                                    if (!c.pressed) {
                                        lifecycle.finish(pointerEvent.copy(phase = PointerPhase.UP))
                                        lastUp = c.uptimeMillis
                                        break
                                    }
                                    lifecycle.send(pointerEvent.copy(phase = PointerPhase.MOVE))
                                    c.consume()
                                }
                            } finally {
                                // awaitPointerEvent can be canceled by focus loss, pointerInput restart,
                                // or leaving composition; the tool still needs to close its session.
                                lifecycle.cancel()
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        // Only two actual touch contacts may pan/zoom; a stylus plus palm must not move the canvas.
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if (down.type != PointerType.Touch) return@awaitEachGesture
                            val feedback = ViewportGestureFeedback(actionCompleted)
                            feedback.begin("Pan and zoom canvas view")
                            var lastCentroid: androidx.compose.ui.geometry.Offset? = null
                            var lastSpread = 0f
                            var released = false
                            var cancelled = false
                            while (true) {
                                val ev = awaitPointerEvent()
                                val motion = ev.motionEvent
                                val hasStylus = ev.changes.any { it.type == PointerType.Stylus } ||
                                    motion?.let { raw ->
                                        (0 until raw.pointerCount).any { index ->
                                            val kind = MotionEvents.kind(raw, index)
                                            kind == PointerKind.STYLUS || kind == PointerKind.ERASER
                                        }
                                    } == true
                                if (hasStylus) {
                                    cancelled = true
                                    break
                                }
                                val pressed = ev.changes.filter { it.pressed }
                                if (pressed.isEmpty()) {
                                    released = true
                                    break
                                }
                                if (pressed.any { it.type != PointerType.Touch } || pressed.size < 2) {
                                    lastCentroid = null
                                    continue
                                }
                                val centroid = pressed.fold(androidx.compose.ui.geometry.Offset.Zero) { a, c -> a + c.position } /
                                    pressed.size.toFloat()
                                val spread = pressed.map { (it.position - centroid).getDistance() }.average().toFloat()
                                val lc = lastCentroid
                                if (lc != null && lastSpread > 0f) {
                                    val d = centroid - lc
                                    val zoom = spread / lastSpread
                                    feedback.update(d.x != 0f || d.y != 0f || zoom != 1f)
                                    change(current.panBy(d.x, d.y).zoomAt(ViewPoint(centroid.x, centroid.y), zoom))
                                }
                                lastCentroid = centroid
                                lastSpread = spread
                                pressed.forEach { it.consume() }
                            }
                            feedback.finish(cancelled = cancelled || !released)
                        }
                    },
            )
        }
    }
}
