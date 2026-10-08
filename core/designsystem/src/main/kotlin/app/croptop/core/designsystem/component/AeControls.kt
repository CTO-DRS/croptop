package app.croptop.core.designsystem.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.icon.AeIcons
import app.croptop.core.designsystem.theme.Ae
import app.croptop.core.designsystem.theme.AeDensity

/** Dropdown / select. Shows the current [selectedLabel]; menu lists [options]. */
@Composable
fun <T> AeDropdown(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    groupOf: ((T) -> Any)? = null,
) {
    var open by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val c = Ae.colors
    Box(modifier) {
        Row(
            modifier = Modifier
                .height(Ae.density.controlHeight)
                .widthIn(min = 96.dp)
                .alpha(if (enabled) 1f else DISABLED_ALPHA)
                .background(if (hovered) c.surface4 else c.surface3, Ae.shapes.control)
                .border(1.dp, if (open) c.accent else c.lineHairline, Ae.shapes.control)
                .hoverable(interaction, enabled)
                .clickable(interaction, LocalIndication.current, enabled = enabled, role = Role.DropdownList) { open = true }
                .padding(start = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            AeText(label(selected), modifier = Modifier.weight(1f, fill = false))
            Icon(AeIcons.ChevronDown, null, tint = c.textSecondary, modifier = Modifier.size(16.dp))
        }
        val entries = buildList {
            options.forEachIndexed { i, o ->
                if (groupOf != null && i > 0 && groupOf(o) != groupOf(options[i - 1])) add(AeMenuEntry.Divider)
                add(AeMenuEntry.Item(label(o), onClick = { onSelect(o) }, checked = o == selected))
            }
        }
        AeMenu(expanded = open, onDismiss = { open = false }, entries = entries)
    }
}

/** Segmented control (e.g. Pointer | Touch, Grid | List). */
@Composable
fun <T> AeSegmented(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
) {
    val c = Ae.colors
    Row(
        modifier = modifier
            // The 2 dp inset on every edge is visual spacing; keep the actual touch target at
            // the design-system control height after that inset is applied.
            .height(Ae.density.controlHeight + if (Ae.density == AeDensity.Touch) 4.dp else 0.dp)
            .background(c.surface3, Ae.shapes.control)
            .border(1.dp, c.lineHairline, Ae.shapes.control)
            .padding(2.dp)
            .selectableGroup(),
    ) {
        options.forEach { o ->
            val isSel = o == selected
            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            Box(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .background(
                        if (isSel) {
                            c.surface1
                        } else if (hovered) {
                            c.surface4
                        } else {
                            Color.Transparent
                        },
                        Ae.shapes.chip,
                    )
                    .hoverable(interaction)
                    .selectable(isSel, interaction, LocalIndication.current, role = Role.Tab) { onSelect(o) }
                    .padding(horizontal = 10.dp)
                    .height(Ae.density.controlHeight),
                contentAlignment = Alignment.Center,
            ) {
                AeText(label(o), style = Ae.type.label, color = if (isSel) c.textPrimary else c.textSecondary)
            }
        }
    }
}

@Composable
fun AeCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = Ae.colors
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .height(Ae.density.rowHeight)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .toggleable(checked, interaction, LocalIndication.current, enabled, Role.Checkbox, onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(16.dp)
                .background(if (checked) c.accent else c.surface3, Ae.shapes.chip)
                .border(1.dp, if (checked) c.accent else c.lineStrong, Ae.shapes.chip),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Icon(AeIcons.Check, null, tint = c.onAccent, modifier = Modifier.size(14.dp))
        }
        AeText(label)
    }
}

@Composable
fun AeSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String? = null,
) {
    val c = Ae.colors
    val t by animateFloatAsState(if (checked) 1f else 0f, tween(Ae.motion.fastMillis, easing = Ae.motion.standard), label = "switch")
    val track = if (checked) c.accent else c.surface4
    val knob = c.textPrimary.takeIf { !checked } ?: c.onAccent
    Canvas(
        modifier
            .size(width = 30.dp, height = 18.dp)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .toggleable(checked, remember { MutableInteractionSource() }, LocalIndication.current, enabled, Role.Switch, onCheckedChange)
            .let { if (contentDescription != null) it.semanticsLabel(contentDescription) else it },
    ) {
        drawRoundRect(track, cornerRadius = CornerRadius(size.height / 2))
        val r = size.height / 2 - 3.dp.toPx()
        val x = size.height / 2 + (size.width - size.height) * t
        drawCircle(knob, radius = r, center = Offset(x, size.height / 2))
    }
}
