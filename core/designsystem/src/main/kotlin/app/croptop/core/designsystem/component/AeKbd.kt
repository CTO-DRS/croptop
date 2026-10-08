package app.croptop.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.theme.Ae

/** Shortcut chip: `Ctrl+Shift+Z` renders as three keycaps. */
@Composable
fun AeKbd(shortcut: String, modifier: Modifier = Modifier) {
    val c = Ae.colors
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        splitShortcut(shortcut).forEach { key ->
            AeText(
                key,
                modifier = Modifier
                    .background(c.surface2, Ae.shapes.chip)
                    .border(1.dp, c.lineStrong, Ae.shapes.chip)
                    .padding(horizontal = 4.dp, vertical = 1.dp),
                style = Ae.type.micro,
                color = c.textSecondary,
            )
        }
    }
}

/** "Ctrl+Shift++" → [Ctrl, Shift, +]. */
internal fun splitShortcut(shortcut: String): List<String> {
    val parts = mutableListOf<String>()
    var rest = shortcut
    while (rest.isNotEmpty()) {
        val i = rest.indexOf('+', startIndex = 1)
        if (i < 0) {
            parts += rest
            break
        }
        parts += rest.substring(0, i)
        rest = rest.substring(i + 1)
    }
    return parts
}
