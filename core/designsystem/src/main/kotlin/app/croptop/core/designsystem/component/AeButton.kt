package app.croptop.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.theme.Ae

enum class AeButtonStyle { Primary, Secondary, Ghost, Danger }

@Composable
fun AeButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: AeButtonStyle = AeButtonStyle.Secondary,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    minimumTouchTargetSize: Dp? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val c = Ae.colors
    val (base, content, border) = when (style) {
        AeButtonStyle.Primary -> Triple(c.accent, c.onAccent, Color.Transparent)
        AeButtonStyle.Secondary -> Triple(c.surface3, c.textPrimary, c.lineHairline)
        AeButtonStyle.Ghost -> Triple(Color.Transparent, c.textPrimary, Color.Transparent)
        AeButtonStyle.Danger -> Triple(c.danger, c.onAccent, Color.Transparent)
    }
    val bg = when {
        !enabled -> base

        style == AeButtonStyle.Primary || style == AeButtonStyle.Danger ->
            when {
                pressed -> Color.Black.copy(alpha = 0.16f).compositeOver(base)
                hovered -> Color.White.copy(alpha = 0.08f).compositeOver(base)
                else -> base
            }

        style == AeButtonStyle.Ghost -> when {
            pressed -> c.surface4
            hovered -> c.surface3
            else -> base
        }

        else -> when {
            pressed -> c.pressed(base)
            hovered -> c.hover(base)
            else -> base
        }
    }
    val shape = Ae.shapes.control
    Row(
        modifier = modifier
            .heightIn(min = maxOf(Ae.density.controlHeight, minimumTouchTargetSize ?: Ae.density.controlHeight))
            .captionInteractive()
            .defaultMinSize(minHeight = Ae.density.controlHeight)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clip(shape)
            .background(bg, shape)
            .border(1.dp, border, shape)
            .hoverable(interaction, enabled)
            .clickable(
                interaction,
                indication = androidx.compose.foundation.LocalIndication.current,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(16.dp))
        AeText(text, style = Ae.type.label, color = content)
    }
}
