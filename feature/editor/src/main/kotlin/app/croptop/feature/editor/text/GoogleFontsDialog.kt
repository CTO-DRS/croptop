package app.croptop.feature.editor.text

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.croptop.core.data.GoogleFontListing
import app.croptop.core.designsystem.component.AeButton
import app.croptop.core.designsystem.component.AeButtonStyle
import app.croptop.core.designsystem.component.AeDialog
import app.croptop.core.designsystem.component.AeProgressBar
import app.croptop.core.designsystem.component.AeText
import app.croptop.core.designsystem.theme.Ae

/** Most rows the list shows at once; searching narrows it, so the list stays quick over 1,900 families. */
private const val MAX_ROWS = 60

/** Pure filter for the dialog: name match (prefix first), popular first. Installed families are not hidden. */
internal fun List<GoogleFontListing>.matching(query: String): List<GoogleFontListing> {
    val q = query.trim().lowercase()
    return asSequence()
        .filter { q.isEmpty() || q in it.family.lowercase() || q in it.category.lowercase() }
        .sortedWith(compareBy({ q.isNotEmpty() && !it.family.lowercase().startsWith(q) }, { it.popularity }))
        .take(MAX_ROWS)
        .toList()
}

/** "Get more fonts": the Google Fonts catalog with a Download / Remove button per family (SIL OFL and Apache licensed). */
@Composable
fun GoogleFontsDialog(ui: GoogleFontsUi, onDismiss: () -> Unit) {
    LaunchedEffect(Unit) { ui.onOpen() }
    var query by remember { mutableStateOf("") }
    val c = Ae.colors
    val rows = remember(ui.catalog, query) { ui.catalog.orEmpty().matching(query) }
    AeDialog(
        title = "Get more fonts",
        body = "Fonts from Google Fonts, free to use in any project. They are saved on this device and then work offline.",
        onDismiss = onDismiss,
        actions = { AeButton("Done", onDismiss, style = AeButtonStyle.Primary) },
        width = 520.dp,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.fillMaxWidth().height(Ae.density.controlHeight).background(c.surface3, Ae.shapes.control)
                    .border(1.dp, c.lineHairline, Ae.shapes.control).padding(horizontal = 8.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (query.isEmpty()) AeText("Search ${ui.catalog?.size ?: ""} families", style = Ae.type.body, color = c.textTertiary)
                BasicTextField(
                    query,
                    { query = it },
                    singleLine = true,
                    textStyle = Ae.type.body.copy(color = c.textPrimary),
                    cursorBrush = SolidColor(c.accent),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search Google Fonts" },
                )
            }
            ui.error?.let {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AeText(it, color = c.danger, modifier = Modifier.weight(1f))
                    if (ui.catalog == null) AeButton("Retry", ui.onOpen, style = AeButtonStyle.Secondary)
                }
            }
            when {
                ui.catalog == null && ui.error == null -> AeText("Loading the font list…", color = c.textSecondary)
                ui.catalog != null && rows.isEmpty() -> AeText("No fonts match “$query”.", color = c.textSecondary)
                else -> LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(rows, key = { it.family }) { listing -> GoogleFontRow(listing, ui) }
                }
            }
        }
    }
}

@Composable
private fun GoogleFontRow(listing: GoogleFontListing, ui: GoogleFontsUi) {
    val c = Ae.colors
    val progress = ui.downloading[listing.family]
    val installed = listing.family in ui.installed
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            AeText(listing.family, style = Ae.type.label, color = c.textPrimary)
            AeText("${listing.category} · ${listing.styles.size} ${if (listing.styles.size == 1) "style" else "styles"}", style = Ae.type.caption, color = c.textSecondary)
            if (progress != null) AeProgressBar(progress, "Downloading ${listing.family}")
        }
        when {
            progress != null -> AeText("${(progress * 100).toInt()}%", style = Ae.type.caption, color = c.textSecondary)
            installed -> AeButton("Remove", { ui.onRemove(listing.family) }, style = AeButtonStyle.Secondary)
            else -> AeButton("Download", { ui.onInstall(listing) }, style = AeButtonStyle.Primary)
        }
    }
}
