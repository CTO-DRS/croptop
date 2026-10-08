package app.croptop.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import app.croptop.core.designsystem.theme.Ae
import kotlinx.coroutines.delay

const val TOOLTIP_DELAY_MILLIS = 500L

/** Shows [tooltip] (+ [shortcut] chip) after hovering [content] for 500ms. Hidden on press. */
@Composable
fun AeTooltipArea(
    tooltip: String,
    modifier: Modifier = Modifier,
    shortcut: String? = null,
    content: @Composable () -> Unit,
) {
    var hovering by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(hovering) {
        if (hovering) {
            delay(TOOLTIP_DELAY_MILLIS)
            visible = true
        } else {
            visible = false
        }
    }
    Box(
        modifier = modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    when (awaitPointerEvent().type) {
                        PointerEventType.Enter -> hovering = true
                        PointerEventType.Exit, PointerEventType.Press -> hovering = false
                    }
                }
            }
        },
    ) {
        content()
        if (visible) {
            Popup(popupPositionProvider = BelowAnchor, properties = PopupProperties(focusable = false)) {
                AeTooltipBubble(tooltip, shortcut)
            }
        }
    }
}

@Composable
fun AeTooltipBubble(text: String, shortcut: String?) {
    val c = Ae.colors
    Row(
        modifier = Modifier
            .background(c.surface4, Ae.shapes.chip)
            .border(1.dp, c.lineHairline, Ae.shapes.chip)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AeText(text, style = Ae.type.caption)
        if (shortcut != null) AeKbd(shortcut)
    }
}

private object BelowAnchor : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val gap = 6
        var x = anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2
        var y = anchorBounds.bottom + gap
        if (y + popupContentSize.height > windowSize.height) y = anchorBounds.top - gap - popupContentSize.height
        x = x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        return IntOffset(x, y)
    }
}
