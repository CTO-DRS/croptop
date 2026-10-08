package app.croptop.feature.library

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.component.AeEmptyState
import app.croptop.core.designsystem.component.AeIconButton
import app.croptop.core.designsystem.component.AeText
import app.croptop.core.designsystem.icon.AeIcons
import app.croptop.core.designsystem.theme.Ae
import app.croptop.core.model.CatalogImage
import app.croptop.core.model.Flag
import kotlin.math.ceil

/** Library views: Grid, Compare (select vs candidate) and Survey (the selection side by side). */
enum class LibraryView(val label: String) { GRID("Grid"), COMPARE("Compare"), SURVEY("Survey") }

private val ViewerBackground = Color(0xFF0B0B0C)

/** One photo frame: fit-to-frame preview, name, stars/flag; accent outline when it's the active one. */
@Composable
private fun Frame(
    img: CatalogImage,
    title: String?,
    active: Boolean,
    onActivate: () -> Unit,
    modifier: Modifier = Modifier,
    onRemove: (() -> Unit)? = null,
) {
    val c = Ae.colors
    val preview = rememberThumbnail(img.uri, 1024)
    Column(
        modifier
            .border(if (active) 2.dp else 1.dp, if (active) c.accent else c.lineHairline, Ae.shapes.popover)
            .clickable(onClick = onActivate)
            .semantics { selected = active }
            .padding(8.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
            if (title !=
                null
            ) {
                AeText(
                    title.uppercase(),
                    style = Ae.type.panelHeader,
                    color = if (active) c.accent else c.textTertiary,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            AeText(img.name, style = Ae.type.caption, color = c.textSecondary, modifier = Modifier.weight(1f))
            if (onRemove != null) AeIconButton(AeIcons.Close, "Remove ${img.name} from survey", onRemove)
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (preview != null) {
                Image(preview, img.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            } else {
                AeText("Loading…", style = Ae.type.caption, color = c.textTertiary)
            }
        }
        Row(
            Modifier.fillMaxWidth().height(28.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AeText(
                if (img.rating >
                    0
                ) {
                    "★".repeat(img.rating)
                } else {
                    "No rating"
                },
                style = Ae.type.caption,
                color = if (img.rating > 0) c.textPrimary else c.textTertiary,
            )
            when (img.flag) {
                Flag.PICK -> AeText("Picked", style = Ae.type.caption, color = c.success)
                Flag.REJECT -> AeText("Rejected", style = Ae.type.caption, color = c.danger)
                Flag.NONE -> Unit
            }
        }
    }
}

/**
 * Compare: the select (left) stays put while ←/→ step the candidate (right) through the filtered photos;
 * ↑ makes the candidate the new select. Marks (P/X/0–5) go to whichever frame is active.
 */
@Composable
fun CompareView(
    select: CatalogImage,
    candidate: CatalogImage?,
    activeId: Long,
    onActivate: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(ViewerBackground).padding(12.dp)) {
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Frame(select, "Select", activeId == select.id, { onActivate(select.id) }, Modifier.weight(1f))
            if (candidate != null) {
                Frame(candidate, "Candidate", activeId == candidate.id, { onActivate(candidate.id) }, Modifier.weight(1f))
            } else {
                AeEmptyState("No other photos", body = "Add photos or clear the filter to compare.", modifier = Modifier.weight(1f))
            }
        }
        Hint("← → change candidate · ↑ make candidate the select · P/X/0–5 mark the active photo · G back to grid")
    }
}

/** Survey: every selected photo at once, laid out to fill the space; ✕ drops one from the selection. */
@Composable
fun SurveyView(
    photos: List<CatalogImage>,
    activeId: Long?,
    onActivate: (Long) -> Unit,
    onRemove: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(ViewerBackground).padding(12.dp)) {
        if (photos.size < 2) {
            AeEmptyState(
                "Select photos to survey",
                body = "Ctrl-click or Shift-click two or more photos in the grid, then press N.",
                icon = AeIcons.Library,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        } else {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                // Pick the column count that makes frames largest for this aspect ratio.
                val n = photos.size
                val aspect = maxWidth.value / maxHeight.value.coerceAtLeast(1f)
                val cols = (1..n).maxBy { col -> minOf(aspect / col, 1f / ceil(n / col.toFloat())) }
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    photos.chunked(cols).forEach { row ->
                        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.forEach { img ->
                                Frame(img, null, img.id == activeId, { onActivate(img.id) }, Modifier.weight(1f), onRemove = { onRemove(img.id) })
                            }
                            repeat(cols - row.size) { Box(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
        Hint("← → move between photos · P/X/0–5 mark the active photo · G back to grid")
    }
}

@Composable
private fun Hint(text: String) {
    AeText(text, style = Ae.type.caption, color = Ae.colors.textTertiary, modifier = Modifier.padding(top = 8.dp))
}
