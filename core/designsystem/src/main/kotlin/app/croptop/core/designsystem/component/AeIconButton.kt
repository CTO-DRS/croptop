package app.croptop.core.designsystem.component

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.theme.Ae

/**
 * Icon-only button. Always pass [contentDescription] (TalkBack) and, when bound, the [shortcut]
 * so the tooltip can show it. Controls with shortcuts also show them in the tooltip.
 */
@Composable
fun AeIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shortcut: String? = null,
    enabled: Boolean = true,
    tint: Color = Ae.colors.textSecondary,
    minimumTouchTargetSize: Dp? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val c = Ae.colors
    val targetSize = maxOf(Ae.density.iconButton, minimumTouchTargetSize ?: Ae.density.iconButton)
    AeTooltipArea(tooltip = contentDescription, shortcut = shortcut) {
        Box(
            modifier = modifier
                .captionInteractive()
                .size(targetSize)
                .alpha(if (enabled) 1f else DISABLED_ALPHA)
                .clip(Ae.shapes.control)
                .background(
                    if (!enabled) {
                        Color.Transparent
                    } else if (pressed) {
                        c.surface4
                    } else if (hovered) {
                        c.surface3
                    } else {
                        Color.Transparent
                    },
                )
                .hoverable(interaction, enabled)
                .clickable(
                    interaction,
                    LocalIndication.current,
                    enabled = enabled,
                    role = Role.Button,
                    onClickLabel = contentDescription,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = contentDescription,
                tint = if (hovered) c.textPrimary else tint,
                modifier = Modifier.size(Ae.density.iconSize),
            )
        }
    }
}

/** Tool/toggle button: accent background + primary glyph when [checked]. */
@Composable
fun AeToggleIconButton(
    icon: ImageVector,
    contentDescription: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    shortcut: String? = null,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val c = Ae.colors
    val bg = when {
        checked -> c.accent
        hovered -> c.surface3
        else -> Color.Transparent
    }
    AeTooltipArea(tooltip = contentDescription, shortcut = shortcut) {
        Box(
            modifier = modifier
                .captionInteractive()
                .size(Ae.density.iconButton)
                .alpha(if (enabled) 1f else DISABLED_ALPHA)
                .clip(Ae.shapes.control)
                .background(bg)
                .hoverable(interaction, enabled)
                .toggleable(checked, interaction, LocalIndication.current, enabled, Role.Checkbox, onCheckedChange),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = contentDescription,
                tint = if (checked) {
                    c.onAccent
                } else if (hovered) {
                    c.textPrimary
                } else {
                    c.textSecondary
                },
                modifier = Modifier.size(Ae.density.iconSize),
            )
        }
    }
}
