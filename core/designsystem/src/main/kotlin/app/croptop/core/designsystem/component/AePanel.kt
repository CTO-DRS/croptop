package app.croptop.core.designsystem.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.icon.AeIcons
import app.croptop.core.designsystem.theme.Ae

/** 1dp hairline separator. */
@Composable
fun AeDivider(modifier: Modifier = Modifier, vertical: Boolean = false) {
    val c = Ae.colors.lineHairline
    Box(if (vertical) modifier.width(1.dp).background(c) else modifier.fillMaxWidth().height(1.dp).background(c))
}

/**
 * Panel with an uppercase header, optional collapse and header actions.
 * Docking/floating arrives with AeDock in.
 */
@Composable
fun AePanel(
    title: String,
    modifier: Modifier = Modifier,
    expanded: Boolean = true,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Ae.colors
    Column(modifier.background(c.surface1)) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(Ae.density.rowHeight)
                .background(c.surface2)
                .let { m ->
                    if (onExpandedChange != null) {
                        m.clickable(
                            role = Role.Button,
                            onClickLabel = if (expanded) "Collapse $title" else "Expand $title",
                        ) { onExpandedChange(!expanded) }
                            .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                    } else {
                        m
                    }
                }
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (onExpandedChange != null) {
                Icon(
                    AeIcons.ChevronRight,
                    contentDescription = null,
                    tint = c.textTertiary,
                    modifier = Modifier.size(14.dp).rotate(if (expanded) 90f else 0f),
                )
            }
            AeText(
                title.uppercase(),
                style = Ae.type.panelHeader,
                color = c.textSecondary,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            actions()
        }
        AeDivider()
        val motion = Ae.motion
        AnimatedVisibility(
            visible = expanded,
            enter = if (motion.baseMillis == 0) {
                EnterTransition.None
            } else {
                expandVertically(animationSpec = tween(motion.baseMillis, easing = motion.standard)) +
                    fadeIn(animationSpec = tween(motion.baseMillis, easing = motion.standard))
            },
            exit = if (motion.baseMillis == 0) {
                ExitTransition.None
            } else {
                shrinkVertically(animationSpec = tween(motion.baseMillis, easing = motion.standard)) +
                    fadeOut(animationSpec = tween(motion.baseMillis, easing = motion.standard))
            },
        ) {
            Column(content = content)
        }
    }
}

/** Centered placeholder for empty lists and not-yet-built areas. */
@Composable
fun AeEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val c = Ae.colors
    Column(
        modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        if (icon != null) Icon(icon, null, tint = c.textTertiary, modifier = Modifier.size(32.dp))
        AeText(title, style = Ae.type.title)
        if (body != null) AeText(body, color = c.textSecondary, maxLines = 3)
        action?.invoke()
    }
}
