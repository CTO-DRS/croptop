package app.croptop.core.input

import android.view.InputDevice
import android.view.MotionEvent
import app.croptop.core.model.StylusBarrelAction
import app.croptop.core.model.WheelBehavior
import kotlin.math.pow

/** What produced a pointer event. */
enum class PointerKind { MOUSE, TOUCHPAD, STYLUS, ERASER, FINGER, UNKNOWN }

enum class CanvasPointerAction { ACTIVE_TOOL, ERASER, EYEDROPPER, PAN }

fun canvasPointerAction(kind: PointerKind, barrelPressed: Boolean, barrelAction: StylusBarrelAction): CanvasPointerAction = when {
    kind == PointerKind.ERASER -> CanvasPointerAction.ERASER
    kind == PointerKind.STYLUS && barrelPressed && barrelAction == StylusBarrelAction.EYEDROPPER -> CanvasPointerAction.EYEDROPPER
    kind == PointerKind.STYLUS && barrelPressed && barrelAction == StylusBarrelAction.PAN -> CanvasPointerAction.PAN
    else -> CanvasPointerAction.ACTIVE_TOOL
}

data class Modifiers(val ctrl: Boolean = false, val shift: Boolean = false, val alt: Boolean = false, val meta: Boolean = false) {
    companion object {
        val None = Modifiers()

        fun of(metaState: Int) = Modifiers(
            ctrl = metaState and android.view.KeyEvent.META_CTRL_ON != 0,
            shift = metaState and android.view.KeyEvent.META_SHIFT_ON != 0,
            alt = metaState and android.view.KeyEvent.META_ALT_ON != 0,
            meta = metaState and android.view.KeyEvent.META_META_ON != 0,
        )
    }
}

/** One stylus/mouse sample in view pixels. Tilt/orientation in radians (Android conventions). */
data class PointerSample(
    val x: Float,
    val y: Float,
    val pressure: Float,
    val tilt: Float,
    val orientation: Float,
    val timeMillis: Long,
)

/** Wheel / trackpad input as reported by the platform, flattened for pure classification. */
data class RawScroll(
    val x: Float,
    val y: Float,
    /** AXIS_VSCROLL / AXIS_HSCROLL (wheel notches; smooth on touchpads). +v = away from user. */
    val vScroll: Float = 0f,
    val hScroll: Float = 0f,
    /** Touchpad two-finger swipe distance in px (CLASSIFICATION_TWO_FINGER_SWIPE). */
    val swipeDx: Float = 0f,
    val swipeDy: Float = 0f,
    /** Touchpad pinch scale factor for this event (CLASSIFICATION_PINCH), 1 = none. */
    val pinchScale: Float = 1f,
    val kind: PointerKind = PointerKind.MOUSE,
    val modifiers: Modifiers = Modifiers.None,
)

/** What the canvas should do with a scroll-type input. */
sealed interface CanvasGesture {
    data class Pan(val dx: Float, val dy: Float) : CanvasGesture

    data class Zoom(val factor: Float, val anchorX: Float, val anchorY: Float) : CanvasGesture

    /** Alt+wheel: change brush size by [steps] notches. */
    data class BrushSize(val steps: Float) : CanvasGesture
}

object ScrollClassifier {
    /** Pixels panned per wheel notch. */
    const val PX_PER_NOTCH = 64f

    /** Zoom factor per wheel notch (Ctrl+wheel). */
    const val ZOOM_PER_NOTCH = 1.2f

    fun classify(e: RawScroll, wheel: WheelBehavior): CanvasGesture? {
        if (e.pinchScale != 1f && e.pinchScale > 0f) return CanvasGesture.Zoom(e.pinchScale, e.x, e.y)
        if (e.swipeDx != 0f || e.swipeDy != 0f) {
            // Two-finger swipe pans (content follows fingers); Ctrl+swipe zooms like Ctrl+wheel.
            return if (e.modifiers.ctrl) zoom(-e.swipeDy / PX_PER_NOTCH, e) else CanvasGesture.Pan(-e.swipeDx, -e.swipeDy)
        }
        if (e.vScroll == 0f && e.hScroll == 0f) return null
        val m = e.modifiers
        return when {
            m.alt -> CanvasGesture.BrushSize(e.vScroll)
            m.ctrl -> zoom(e.vScroll, e)
            m.shift -> CanvasGesture.Pan(e.vScroll * PX_PER_NOTCH, 0f)
            wheel == WheelBehavior.ZOOM && e.kind == PointerKind.MOUSE -> zoom(e.vScroll, e)
            else -> CanvasGesture.Pan(0f - e.hScroll * PX_PER_NOTCH, e.vScroll * PX_PER_NOTCH)
        }
    }

    private fun zoom(notches: Float, e: RawScroll) = CanvasGesture.Zoom(ZOOM_PER_NOTCH.pow(notches), e.x, e.y)
}

/** Suppresses accidental finger contacts shortly after a pen enters the canvas. */
class PalmRejectionGate(private val staleAfterMillis: Long = 750L) {
    private var lastStylusAtMillis: Long? = null

    fun observe(kind: PointerKind, eventTimeMillis: Long) {
        if (kind == PointerKind.STYLUS || kind == PointerKind.ERASER) lastStylusAtMillis = eventTimeMillis
    }

    fun observeStylusExit(eventTimeMillis: Long) {
        val last = lastStylusAtMillis ?: return
        if (eventTimeMillis >= last) lastStylusAtMillis = null
    }

    fun rejects(kind: PointerKind, eventTimeMillis: Long): Boolean {
        if (kind != PointerKind.FINGER) return false
        val last = lastStylusAtMillis ?: return false
        return eventTimeMillis >= last && eventTimeMillis - last <= staleAfterMillis
    }
}

fun rejectsPalmContact(primaryKind: PointerKind, contactKind: PointerKind, gate: PalmRejectionGate, eventTimeMillis: Long): Boolean =
    (primaryKind == PointerKind.STYLUS || primaryKind == PointerKind.ERASER) && gate.rejects(contactKind, eventTimeMillis)

/** Android MotionEvent → normalized input. Kept thin; logic lives in the pure helpers above. */
object MotionEvents {
    fun kind(e: MotionEvent, pointerIndex: Int = 0): PointerKind = when (e.getToolType(pointerIndex)) {
        MotionEvent.TOOL_TYPE_STYLUS -> PointerKind.STYLUS

        MotionEvent.TOOL_TYPE_ERASER -> PointerKind.ERASER

        MotionEvent.TOOL_TYPE_MOUSE -> PointerKind.MOUSE

        MotionEvent.TOOL_TYPE_FINGER ->
            if (e.isFromSource(InputDevice.SOURCE_TOUCHPAD) || e.isFromSource(InputDevice.SOURCE_MOUSE)) PointerKind.TOUCHPAD else PointerKind.FINGER

        else -> PointerKind.UNKNOWN
    }

    fun scroll(e: MotionEvent): RawScroll? {
        val base = RawScroll(x = e.x, y = e.y, kind = kind(e), modifiers = Modifiers.of(e.metaState))
        return when {
            // Touchpad gesture axes exist from Android 14; earlier versions never report these classifications.
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE && e.classification == MotionEvent.CLASSIFICATION_PINCH ->
                base.copy(kind = PointerKind.TOUCHPAD, pinchScale = e.getAxisValue(MotionEvent.AXIS_GESTURE_PINCH_SCALE_FACTOR))

            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                e.classification == MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE && e.actionMasked == MotionEvent.ACTION_MOVE ->
                base.copy(
                    kind = PointerKind.TOUCHPAD,
                    swipeDx = e.getAxisValue(MotionEvent.AXIS_GESTURE_SCROLL_X_DISTANCE),
                    swipeDy = e.getAxisValue(MotionEvent.AXIS_GESTURE_SCROLL_Y_DISTANCE),
                )

            e.actionMasked == MotionEvent.ACTION_SCROLL ->
                base.copy(vScroll = e.getAxisValue(MotionEvent.AXIS_VSCROLL), hScroll = e.getAxisValue(MotionEvent.AXIS_HSCROLL))

            else -> null
        }
    }

    /** All samples in [e] for pointer 0, oldest first, including batched historical samples. */
    fun samples(e: MotionEvent, pointerIndex: Int = 0): List<PointerSample> {
        require(pointerIndex in 0 until e.pointerCount) { "Pointer index is outside this MotionEvent" }
        val n = e.historySize
        return buildList(n + 1) {
            for (h in 0 until n) {
                add(
                    PointerSample(
                        e.getHistoricalX(pointerIndex, h),
                        e.getHistoricalY(pointerIndex, h),
                        e.getHistoricalPressure(pointerIndex, h),
                        e.getHistoricalAxisValue(MotionEvent.AXIS_TILT, pointerIndex, h),
                        e.getHistoricalAxisValue(MotionEvent.AXIS_ORIENTATION, pointerIndex, h),
                        e.getHistoricalEventTime(h),
                    ),
                )
            }
            add(
                PointerSample(
                    e.getX(pointerIndex),
                    e.getY(pointerIndex),
                    e.getPressure(pointerIndex),
                    e.getAxisValue(MotionEvent.AXIS_TILT, pointerIndex),
                    e.getAxisValue(MotionEvent.AXIS_ORIENTATION, pointerIndex),
                    e.eventTime,
                ),
            )
        }
    }

    fun stylusBarrelPressed(e: MotionEvent): Boolean =
        e.buttonState and (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY) != 0

    fun cancelled(e: MotionEvent, pointerIndex: Int = 0): Boolean =
        isCanceledPointer(e.actionMasked, e.flags, e.actionIndex, pointerIndex)
}

/** [MotionEvent.FLAG_CANCELED] (Android 13+). Older versions never set the bit, so testing it there is harmless. */
private const val FLAG_CANCELED = 0x20

/** Android marks the canceled contact on POINTER_UP; ACTION_CANCEL still ends the whole gesture. */
internal fun isCanceledPointer(actionMasked: Int, flags: Int, actionIndex: Int, pointerIndex: Int): Boolean =
    actionMasked == MotionEvent.ACTION_CANCEL ||
        (
            flags and FLAG_CANCELED != 0 &&
                (actionMasked == MotionEvent.ACTION_UP || actionMasked == MotionEvent.ACTION_POINTER_UP) &&
                actionIndex == pointerIndex
            )
