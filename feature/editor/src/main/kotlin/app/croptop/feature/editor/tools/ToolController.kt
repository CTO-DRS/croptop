package app.croptop.feature.editor.tools

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.pointer.PointerIcon
import app.croptop.core.input.Modifiers
import app.croptop.core.input.PointerKind
import app.croptop.core.input.PointerSample
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocSize
import app.croptop.core.model.ToolId
import app.croptop.core.model.ViewPoint
import app.croptop.core.model.ViewportState

enum class PointerPhase { DOWN, MOVE, UP, CANCEL }

/** A primary-button / pen / finger pointer event on the canvas, already in both coordinate spaces. */
data class ToolPointerEvent(
    val phase: PointerPhase,
    val view: ViewPoint,
    val doc: DocPoint,
    val pressure: Float,
    val modifiers: Modifiers,
    /** Position where this gesture started (view px). */
    val downAt: ViewPoint,
    val clickCount: Int = 1,
    val pointerKind: PointerKind = PointerKind.UNKNOWN,
    val samples: List<PointerSample> = emptyList(),
    /** Temporary future samples for the live overlay only; never pass these to a StrokeSession. */
    val predictedSamples: List<PointerSample> = emptyList(),
    val stylusBarrelPressed: Boolean = false,
    val cancelled: Boolean = false,
    val eventTimeMillis: Long = 0L,
)

/** What a tool may read/change while handling input. */
interface ToolContext {
    val viewport: ViewportState
    val document: DocSize

    /** Screen density (px per dp) for dp-sized hit targets; 1 in tests and tools that do not need it. */
    val density: Float get() = 1f

    fun updateViewport(state: ViewportState)
}

/**
 * One editor tool's interaction logic: turns canvas pointer events into
 * viewport changes or engine commands, and provides its options-bar content and cursor.
 * Stateful per gesture; one instance per tool per editor.
 */
interface ToolController {
    val tool: ToolId

    fun cursor(modifiers: Modifiers): PointerIcon = PointerIcon.Crosshair

    /** Cursor for the pointer hovering at [point]; null falls back to [cursor] (tools with direction-aware handles override it). */
    fun cursorAt(point: ViewPoint, modifiers: Modifiers, viewport: ViewportState): PointerIcon? = null

    /** Returns true when the event was used. */
    fun onPointer(event: ToolPointerEvent, ctx: ToolContext): Boolean

    /** Releases temporary key modifiers whose KeyUp may be lost when the canvas loses focus. */
    fun onFocusLost() = Unit

    /** Options bar content for this tool. */
    @Composable
    fun Options() = Unit

    /** On-canvas feedback while the tool works (rubber bands, previews, brush cursor), in view space. */
    @Composable
    fun Overlay(viewport: ViewportState, hover: ViewPoint?) = Unit
}

/**
 * `[` / `]` brush size steps, like Photoshop: finer when small, coarser when large. Clamped to 1–5000 px.
 */
fun stepBrushSize(size: Float, up: Boolean): Float {
    val s = size.coerceIn(1f, 5000f)
    val step = when {
        s < 10f || (!up && s <= 10f) -> 1f
        s < 100f || (!up && s <= 100f) -> 10f
        s < 200f || (!up && s <= 200f) -> 25f
        s < 300f || (!up && s <= 300f) -> 50f
        else -> 100f
    }
    return (if (up) s + step else s - step).coerceIn(1f, 5000f)
}

/**
 * Number keys set opacity like Photoshop: 1–9 → 10–90%, 0 → 100%, and two digits typed quickly
 * (within [windowMs]) → that exact percentage, e.g. 4 then 5 → 45%, 0 then 5 → 5%.
 */
class PercentKeys(private val windowMs: Long = 600) {
    private var pending: Int? = null
    private var at = 0L

    /** [digit] 0–9 pressed at [nowMs]; returns the percentage to apply (1–100). */
    fun press(digit: Int, nowMs: Long): Int {
        val first = pending
        return if (first != null && nowMs - at <= windowMs) {
            pending = null
            (first * 10 + digit).coerceIn(1, 100)
        } else {
            pending = digit
            at = nowMs
            if (digit == 0) 100 else digit * 10
        }
    }
}

/**
 * Hit radius of a canvas handle in view pixels: a pointer reaches a handle within 8 dp, a finger within 24 dp (so the touch
 * target is at least 48 dp across, hit-target rules).
 */
fun handleHitRadiusPx(density: Float, kind: PointerKind): Float {
    val dp = if (kind == PointerKind.FINGER) TOUCH_HANDLE_RADIUS_DP else POINTER_HANDLE_RADIUS_DP
    return dp * density.coerceAtLeast(0.5f)
}

const val POINTER_HANDLE_RADIUS_DP = 8f
const val TOUCH_HANDLE_RADIUS_DP = 24f
