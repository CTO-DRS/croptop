package app.croptop.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.icon.AeIcons
import app.croptop.core.designsystem.theme.Ae

/** Opens the command palette; provided by the app shell. Null hides the button. */
val LocalOpenCommandPalette = staticCompositionLocalOf<(() -> Unit)?> { null }

/** Search-field-looking button: "Search commands  Ctrl K" (discoverable entry to the palette). */
@Composable
fun SearchCommandsButton(modifier: Modifier = Modifier, shortcut: String = "Ctrl+K", compact: Boolean = false) {
    val open = LocalOpenCommandPalette.current ?: return
    // Phones: an icon; the 240dp field would push the title out of the header.
    if (compact) {
        AeIconButton(AeIcons.Search, "Search commands", open, modifier.captionInteractive(), shortcut = shortcut)
        return
    }
    val c = Ae.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier
            .captionInteractive()
            .height(28.dp)
            .width(240.dp)
            .background(if (hovered) c.surface3 else c.surface2, Ae.shapes.control)
            .border(1.dp, c.lineHairline, Ae.shapes.control)
            .hoverable(interaction)
            .clickable(interaction, indication = null, role = Role.Button, onClickLabel = "Search commands", onClick = open)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(AeIcons.Search, contentDescription = null, tint = c.textTertiary, modifier = Modifier.size(14.dp))
        AeText("Search commands", style = Ae.type.caption, color = c.textTertiary, modifier = Modifier.weight(1f))
        AeKbd(shortcut)
    }
}
