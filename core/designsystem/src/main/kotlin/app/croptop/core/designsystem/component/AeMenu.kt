package app.croptop.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import app.croptop.core.designsystem.icon.AeIcons
import app.croptop.core.designsystem.theme.Ae

/** Menu model shared by menu bar, dropdowns and context menus. */
sealed interface AeMenuEntry {
    data class Item(
        val label: String,
        val onClick: () -> Unit,
        val shortcut: String? = null,
        val icon: ImageVector? = null,
        val enabled: Boolean = true,
        val checked: Boolean = false,
    ) : AeMenuEntry

    data object Divider : AeMenuEntry

    data class Header(val label: String) : AeMenuEntry
}

/**
 * Popup menu anchored to its parent (below) or at [offset] (context menu).
 * Keyboard: ↑/↓ move, Enter activates, Esc closes.
 */
@Composable
fun AeMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    entries: List<AeMenuEntry>,
    offset: IntOffset? = null,
) {
    if (!expanded) return
    val items = entries.filterIsInstance<AeMenuEntry.Item>()
    var highlighted by remember { mutableIntStateOf(-1) }
    var hasRequestedFocus by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val scrollState = rememberScrollState()
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    val maxMenuHeight = (windowHeight - 32.dp).coerceAtLeast(Ae.density.rowHeight * 2)
    Popup(
        popupPositionProvider = remember(offset) { MenuPosition(offset) },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        val c = Ae.colors
        Column(
            modifier = Modifier
                .width(IntrinsicSize.Max)
                .widthIn(min = 200.dp, max = 360.dp)
                .heightIn(max = maxMenuHeight)
                .verticalScroll(scrollState)
                .testTag("ae-menu-scroll-container")
                .background(c.surface2, Ae.shapes.popover)
                .border(1.dp, c.lineStrong, Ae.shapes.popover)
                .padding(4.dp)
                .focusRequester(focus)
                .focusable()
                .onGloballyPositioned {
                    if (!hasRequestedFocus) {
                        hasRequestedFocus = true
                        focus.requestFocus()
                    }
                }
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val enabled = items.withIndex().filter { it.value.enabled }.map { it.index }
                    when (e.key) {
                        Key.DirectionDown -> {
                            highlighted = enabled.firstOrNull { it > highlighted } ?: enabled.firstOrNull() ?: -1
                            true
                        }

                        Key.DirectionUp -> {
                            highlighted = enabled.lastOrNull { it < highlighted } ?: enabled.lastOrNull() ?: -1
                            true
                        }

                        Key.Enter, Key.NumPadEnter, Key.Spacebar -> {
                            items.getOrNull(highlighted)?.takeIf { it.enabled }?.let {
                                onDismiss()
                                it.onClick()
                            }
                            true
                        }

                        Key.Escape -> {
                            onDismiss()
                            true
                        }

                        else -> false
                    }
                },
        ) {
            entries.forEach { entry ->
                when (entry) {
                    is AeMenuEntry.Divider -> Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(c.lineHairline))

                    is AeMenuEntry.Header -> AeText(
                        entry.label.uppercase(),
                        style = Ae.type.panelHeader,
                        color = c.textTertiary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    )

                    is AeMenuEntry.Item -> {
                        val index = items.indexOf(entry)
                        val bringIntoView = remember(index) { BringIntoViewRequester() }
                        LaunchedEffect(index == highlighted) {
                            if (index == highlighted) bringIntoView.bringIntoView()
                        }
                        MenuRow(
                            entry,
                            highlighted = index == highlighted,
                            onHover = { highlighted = index },
                            modifier = Modifier.bringIntoViewRequester(bringIntoView),
                        ) {
                            onDismiss()
                            entry.onClick()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuRow(
    item: AeMenuEntry.Item,
    highlighted: Boolean,
    onHover: () -> Unit,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    LaunchedEffect(hovered) { if (hovered) onHover() }
    val c = Ae.colors
    val active = highlighted && item.enabled
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(Ae.density.rowHeight)
            .alpha(if (item.enabled) 1f else DISABLED_ALPHA)
            .background(if (active) c.accent else Color.Transparent, Ae.shapes.chip)
            .hoverable(interaction, item.enabled)
            .clickable(interaction, indication = null, enabled = item.enabled, onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val fg = if (active) c.onAccent else c.textPrimary
        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
            when {
                item.checked -> Icon(AeIcons.Check, null, tint = fg, modifier = Modifier.size(16.dp))
                item.icon != null -> Icon(item.icon, null, tint = if (active) fg else c.textSecondary, modifier = Modifier.size(16.dp))
            }
        }
        AeText(item.label, color = fg, modifier = Modifier.weight(1f))
        if (item.shortcut != null) AeText(item.shortcut, style = Ae.type.caption, color = if (active) fg else c.textTertiary)
        Spacer(Modifier.width(2.dp))
    }
}

private class MenuPosition(private val at: IntOffset?) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val desired = if (at !=
            null
        ) {
            IntOffset(anchorBounds.left + at.x, anchorBounds.top + at.y)
        } else {
            IntOffset(anchorBounds.left, anchorBounds.bottom + 2)
        }
        var x = desired.x
        var y = desired.y
        if (x + popupContentSize.width > windowSize.width) x = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        if (y + popupContentSize.height > windowSize.height) {
            y =
                if (at !=
                    null
                ) {
                    (desired.y - popupContentSize.height).coerceAtLeast(0)
                } else {
                    (anchorBounds.top - popupContentSize.height - 2).coerceAtLeast(0)
                }
        }
        return IntOffset(x, y)
    }
}

/**
 * Right-click (mouse secondary button) or long-press opens a context menu at the pointer.
 */
@Composable
fun AeContextMenuArea(
    entries: () -> List<AeMenuEntry>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    var at by remember { mutableStateOf<Offset?>(null) }
    var areaSize by remember { mutableStateOf(IntSize.Zero) }
    Box(
        modifier = modifier
            .onSizeChanged { areaSize = it }
            .semantics(mergeDescendants = true) {
                if (enabled) {
                    onLongClick(label = "Open context menu") {
                        at = Offset(areaSize.width / 2f, areaSize.height / 2f)
                        true
                    }
                }
            }
            .pointerInput(enabled) {
                if (enabled) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val longPress = awaitLongPressOrCancellation(down.id)
                        if (longPress != null) {
                            at = longPress.position
                            longPress.consume()
                        }
                    }
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitPointerEventScope {
                    while (true) {
                        val ev = awaitPointerEvent()
                        if (ev.type == PointerEventType.Press && ev.buttons.isSecondaryPressed) {
                            at = ev.changes.first().position
                            ev.changes.forEach { it.consume() }
                        }
                    }
                }
            },
    ) {
        content()
        val p = at
        AeMenu(
            expanded = p != null,
            onDismiss = { at = null },
            entries = if (p != null) entries() else emptyList(),
            offset = p?.let { IntOffset(it.x.toInt(), it.y.toInt()) },
        )
    }
}
