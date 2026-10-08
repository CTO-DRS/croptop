package app.croptop.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.theme.Ae
import kotlin.math.roundToLong

/** Formats without trailing zeros: 12.0 → "12", 0.25 → "0.25". */
fun formatNumber(v: Float, decimals: Int): String {
    if (decimals == 0) return v.roundToLong().toString()
    return "%.${decimals}f".format(v).trimEnd('0').trimEnd('.', ',')
}

/**
 * Numeric input: ↑/↓ ±[step], Shift ±10×step, Enter commits,
 * and math expressions such as `120*2` are evaluated on commit.
 */
@Composable
fun AeNumberField(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    range: ClosedFloatingPointRange<Float> = -Float.MAX_VALUE..Float.MAX_VALUE,
    step: Float = 1f,
    decimals: Int = 0,
    unit: String? = null,
    width: Dp = 56.dp,
    enabled: Boolean = true,
    contentDescription: String? = null,
    onCommit: () -> Unit = {},
    minimumHeight: Dp? = null,
) {
    var text by remember { mutableStateOf(formatNumber(value, decimals)) }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    LaunchedEffect(value, focused) { if (!focused) text = formatNumber(value, decimals) }

    var hadFocus by remember { mutableStateOf(false) }

    fun commit(raw: String = text) {
        val v = evaluateExpression(raw)?.toFloat()?.coerceIn(range)
        if (v != null) {
            onValueChange(v)
            onCommit()
        }
        text = formatNumber(v ?: value, decimals)
    }

    fun stepBy(multiplier: Float) {
        val base = evaluateExpression(text)?.toFloat()?.coerceIn(range) ?: value
        val next = (base + step * multiplier).coerceIn(range)
        onValueChange(next)
        onCommit()
        text = formatNumber(next, decimals)
    }

    val c = Ae.colors
    val fieldHeight = maxOf(Ae.density.controlHeight, minimumHeight ?: Ae.density.controlHeight)
    // Reserve enough horizontal room for the editable value after the unit suffix and padding.
    // Without this, Compose exposes a narrow text-field hit target even though the outer control
    // is wide enough.
    val fieldWidth = maxOf(width, if (unit != null) 70.dp else width)
    Row(
        modifier = Modifier
            // Text grows with the system font scale; so must the field, or values get clipped.
            .width(fieldWidth * androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceAtLeast(1f))
            .height(fieldHeight)
            .then(modifier)
            .background(c.surface3, Ae.shapes.control)
            .border(1.dp, if (focused) c.accent else c.lineHairline, Ae.shapes.control)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(Modifier.weight(1f).fillMaxHeight()) {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                enabled = enabled,
                singleLine = true,
                interactionSource = interaction,
                textStyle = Ae.type.body.copy(color = c.textPrimary, textAlign = TextAlign.End, fontFeatureSettings = "tnum, cv11"),
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { commit() }),
                // The whole control is the tap target (not just the glyphs): full height, text vertically centred.
                decorationBox = { inner -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) { inner() } },
                modifier = Modifier.fillMaxSize().onFocusChanged {
                    // Leaving the field (Tab, click elsewhere, a dialog's Enter) keeps what was typed.
                    if (hadFocus && !it.isFocused) commit()
                    hadFocus = it.isFocused
                }.onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val mult = if (e.isShiftPressed) 10f else 1f
                    when (e.key) {
                        Key.DirectionUp -> {
                            stepBy(mult)
                            true
                        }

                        Key.DirectionDown -> {
                            stepBy(-mult)
                            true
                        }

                        Key.Enter, Key.NumPadEnter -> {
                            commit()
                            true
                        }

                        Key.Escape -> {
                            text = formatNumber(value, decimals)
                            true
                        }

                        else -> false
                    }
                }.let { m -> if (contentDescription != null) m.then(Modifier.semanticsLabel(contentDescription)) else m },
            )
        }
        if (unit != null) AeText(unit, style = Ae.type.caption, color = c.textTertiary)
    }
}
