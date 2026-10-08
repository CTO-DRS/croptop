package app.croptop.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.theme.Ae

/**
 * A value bar (the control creative apps converge on): the label and the number sit on one rounded bar whose fill shows where the
 * value is in its range. Drag along the bar to scrub (Shift = fine, Alt = coarse; a finger drags the full width in one sweep), tap
 * to type an exact number (math such as `120*2` works), double-tap to reset to [defaultValue], arrow keys step. Ranges that cross zero
 * fill from the zero point. Screen readers get a slider with an "adjust" action and the typed-value action.
 */
@Composable
fun AeBarSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    range: ClosedFloatingPointRange<Float> = 0f..100f,
    defaultValue: Float = range.start,
    step: Float = 1f,
    decimals: Int = 0,
    unit: String? = null,
    enabled: Boolean = true,
    width: Dp? = null,
    /** Above 1 gives the small end of the range more of the bar (brush sizes: fine near 1 px, still reaching 5000); 1 is linear. */
    curve: Float = 1f,
    /** A colour track (Cyan→Red, a hue spectrum, temperature): drawn as a strip along the bottom of the bar with a marker, instead of a fill. */
    trackBrush: Brush? = null,
    contentDescription: String = label,
    onValueChangeFinished: () -> Unit = {},
) {
    if (LocalAeCompactSliders.current) {
        AeCompactSlider(
            label, value, onValueChange, modifier, range, defaultValue, step, decimals, unit, enabled, curve, trackBrush, contentDescription,
            onValueChangeFinished,
        )
        return
    }
    val c = Ae.colors
    val span = range.endInclusive - range.start
    val currentValue by rememberUpdatedState(value)
    val currentChange by rememberUpdatedState(onValueChange)
    val currentFinished by rememberUpdatedState(onValueChangeFinished)
    var editing by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    require(curve >= 1f) { "curve is 1 (linear) or more" }
    fun set(v: Float) = currentChange(v.coerceIn(range))
    // Position on the bar (0–1) of a value, and the value at a position: the curve bends only the bar, never the numbers. A range
    // that crosses zero bends each side around the zero point, so the middle of the bar stays fine.
    val crossesZero = range.start < 0f && range.endInclusive > 0f
    val zeroAt = if (crossesZero) -range.start / span else 0f
    fun bend(x: Double, p: Double) = Math.pow(x.coerceIn(0.0, 1.0), p)
    fun toFraction(v: Float): Float {
        if (span == 0f) return 0f
        if (!crossesZero) return bend(((v - range.start) / span).toDouble(), 1.0 / curve).toFloat()
        return if (v >= 0f) (zeroAt + (1f - zeroAt) * bend((v / range.endInclusive).toDouble(), 1.0 / curve)).toFloat()
        else (zeroAt - zeroAt * bend((v / range.start).toDouble(), 1.0 / curve)).toFloat()
    }
    fun fromFraction(f: Float): Float {
        val x = f.coerceIn(0f, 1f)
        if (!crossesZero) return range.start + span * bend(x.toDouble(), curve.toDouble()).toFloat()
        return if (x >= zeroAt) range.endInclusive * bend(((x - zeroAt) / (1f - zeroAt)).toDouble(), curve.toDouble()).toFloat()
        else range.start * bend(((zeroAt - x) / zeroAt).toDouble(), curve.toDouble()).toFloat()
    }
    fun shown(v: Float) = formatNumber(v, decimals) + (unit?.let { " $it" } ?: "")

    val base = if (width != null) modifier.widthIn(min = width, max = width) else modifier.fillMaxWidth()
    AeTooltipArea(if (enabled) "Drag to adjust. Tap to type. Double-tap to reset." else "$label is unavailable", modifier = base) {
        Box(
            Modifier.fillMaxWidth()
                .height(Ae.density.controlHeight + 14.dp)
                .then(if (editing || focused) Modifier.border(1.dp, c.accent, Ae.shapes.control) else Modifier)
                // While typing, the text field keeps its own semantics node so it can be focused and read.
                .semantics(mergeDescendants = !editing) {
                    this.contentDescription = contentDescription
                    if (!enabled) disabled()
                    customActions = if (enabled) listOf(
                        CustomAccessibilityAction("Reset to ${shown(defaultValue.coerceIn(range))}") {
                            set(defaultValue)
                            currentFinished()
                            true
                        },
                    ) else emptyList()
                    stateDescription = shown(value)
                    progressBarRangeInfo = ProgressBarRangeInfo(value, range)
                    setProgress {
                        if (!enabled) return@setProgress false
                        set(it)
                        currentFinished()
                        true
                    }
                    onClick("Type a value") {
                        if (enabled) editing = true
                        enabled
                    }
                }
                .then(
                    if (!enabled) {
                        Modifier
                    } else {
                        Modifier
                            .focusable(interactionSource = interaction)
                            .onPreviewKeyEvent { e ->
                                if (editing || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                val d = step * if (e.isShiftPressed) 10f else 1f
                                when (e.key) {
                                    Key.DirectionRight, Key.DirectionUp -> {
                                        set(currentValue + d)
                                        currentFinished()
                                        true
                                    }

                                    Key.DirectionLeft, Key.DirectionDown -> {
                                        set(currentValue - d)
                                        currentFinished()
                                        true
                                    }

                                    Key.Enter, Key.NumPadEnter -> {
                                        editing = true
                                        true
                                    }

                                    else -> false
                                }
                            }
                            .pointerHoverIcon(PointerIcon(android.view.PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW))
                            .pointerInput(range, defaultValue) {
                                detectTapGestures(onDoubleTap = {
                                    set(defaultValue)
                                    currentFinished()
                                }, onTap = { editing = true })
                            }
                            .pointerInput(range, step, curve) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    var overSlop = 0f
                                    val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                                        overSlop = over
                                        change.consume()
                                    } ?: return@awaitEachGesture
                                    var position = toFraction(currentValue)
                                    var last = drag.position.x
                                    // The movement that crossed the touch slop counts too, so a quick flick is not lost.
                                    if (size.width > 0 && overSlop != 0f) {
                                        val mods = currentEvent.keyboardModifiers
                                        position = (position + overSlop / size.width * scrubMultiplier(mods.isShiftPressed, mods.isAltPressed)).coerceIn(0f, 1f)
                                        val v = fromFraction(position)
                                        set(if (step > 0f && step <= span / 50f) Math.round(v / step) * step else v)
                                    }
                                    // Dragging the whole bar sweeps the whole range; modifiers scale that.
                                    horizontalDrag(drag.id) { change ->
                                        val mods = currentEvent.keyboardModifiers
                                        val dx = if (size.width > 0) (change.position.x - last) / size.width else 0f
                                        position = (position + dx * scrubMultiplier(mods.isShiftPressed, mods.isAltPressed)).coerceIn(0f, 1f)
                                        last = change.position.x
                                        val v = fromFraction(position)
                                        // Snap to the step only when the step is finer than the bar can show; coarse steps (a default of 1 on a 0–1 range) would make it jump.
                                        set(if (step > 0f && step <= span / 50f) Math.round(v / step) * step else v)
                                        change.consume()
                                    }
                                    currentFinished()
                                }
                            }
                    },
                ),
            contentAlignment = Alignment.CenterStart,
        ) {
            val fraction = toFraction(value)
            val origin = if (crossesZero) toFraction(0f) else 0f
            Canvas(Modifier.fillMaxSize()) {
                // A classic adjuster: label and number on top, below them a real track with a round handle. The span between the
                // bar's origin and the value is lit; a colour track (hue, temperature…) replaces the plain one.
                val inset = 8.dp.toPx()
                val thick = (if (trackBrush != null) 6.dp else 3.dp).toPx()
                val cy = size.height - 9.dp.toPx()
                val left = inset
                val span = size.width - 2 * inset
                val top = cy - thick / 2
                if (trackBrush != null) {
                    drawRect(trackBrush, Offset(left, top), Size(span, thick))
                } else {
                    drawRect(c.lineStrong, Offset(left, top), Size(span, thick))
                    val x0 = left + span * minOf(origin, fraction)
                    val x1 = left + span * maxOf(origin, fraction)
                    if (x1 - x0 > 0.5f) drawRect(c.accent.copy(alpha = if (enabled) 1f else 0.4f), Offset(x0, top), Size(x1 - x0, thick))
                    if (crossesZero) drawRect(c.textTertiary, Offset(left + span * origin - 0.5.dp.toPx(), top - 2.dp.toPx()), Size(1.dp.toPx(), thick + 4.dp.toPx()))
                }
                if (enabled) {
                    drawFaderThumb(left + span * fraction, cy, c, highlighted = focused || editing)
                }
            }
            Row(
                Modifier.fillMaxSize().padding(horizontal = 8.dp).padding(bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AeText(label, style = Ae.type.label, color = if (enabled) c.textPrimary else c.textTertiary, modifier = Modifier.weight(1f))
                if (editing) {
                    BarEditor(
                        initial = formatNumber(value, decimals),
                        unit = unit,
                        onCommit = { v ->
                            editing = false
                            if (v != null) {
                                set(v)
                                currentFinished()
                            }
                        },
                    )
                } else {
                    AeText(shown(value), style = Ae.type.label, color = if (enabled) c.textPrimary else c.textTertiary)
                }
            }
        }
    }
}

/** The inline editor: selects nothing fancy, evaluates math on Done/Enter, Escape or blur without a valid number keeps the old value. */
@Composable
private fun BarEditor(initial: String, unit: String?, onCommit: (Float?) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    var hadFocus by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { focus.requestFocus() }
    fun finish(commit: Boolean) {
        if (done) return
        done = true
        onCommit(if (commit) evaluateExpression(text)?.toFloat() else null)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            text,
            { text = it },
            singleLine = true,
            textStyle = Ae.type.label.copy(color = Ae.colors.textPrimary, textAlign = TextAlign.End),
            cursorBrush = SolidColor(Ae.colors.accent),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { finish(true) }),
            modifier = Modifier
                .widthIn(min = 40.dp, max = 96.dp)
                .focusRequester(focus)
                .onFocusChanged {
                    if (it.isFocused) hadFocus = true else if (hadFocus) finish(true)
                }
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.Escape -> {
                            finish(false)
                            true
                        }

                        Key.Enter, Key.NumPadEnter -> {
                            finish(true)
                            true
                        }

                        else -> false
                    }
                },
        )
        unit?.let { AeText(" $it", style = Ae.type.label, color = Ae.colors.textSecondary) }
    }
}
