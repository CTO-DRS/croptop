package app.croptop.feature.home

import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.croptop.core.designsystem.R as DesignR
import app.croptop.core.designsystem.component.AeAppHeader
import app.croptop.core.designsystem.component.AeButton
import app.croptop.core.designsystem.component.AeButtonStyle
import app.croptop.core.designsystem.component.AeContextMenuArea
import app.croptop.core.designsystem.component.AeDivider
import app.croptop.core.designsystem.component.AeIconButton
import app.croptop.core.designsystem.component.AeKbd
import app.croptop.core.designsystem.component.AeMenuEntry
import app.croptop.core.designsystem.component.AeText
import app.croptop.core.designsystem.component.SearchCommandsButton
import app.croptop.core.designsystem.icon.AeIcons
import app.croptop.core.designsystem.theme.Ae
import app.croptop.core.designsystem.theme.LocalCaptionBar
import app.croptop.core.input.ActionScope
import app.croptop.core.input.ShortcutScope
import app.croptop.core.input.shortcutLabel
import app.croptop.core.model.ColorProfileRef
import app.croptop.core.model.DocSize
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.RecentDocument
import app.croptop.core.model.WorkingSpace

/** Quick canvas sizes. */
data class DocumentPreset(val label: String, val detail: String, val spec: NewDocumentSpec)

val DefaultPresets = listOf(
    DocumentPreset("Photo 4:3", "4000 × 3000", NewDocumentSpec(name = "Photo", size = DocSize(4000, 3000))),
    DocumentPreset("Portrait post", "1080 × 1350", NewDocumentSpec(name = "Post", size = DocSize(1080, 1350), resolutionPpi = 72f)),
    DocumentPreset("A4 print", "2480 × 3508", NewDocumentSpec(name = "A4", size = DocSize(2480, 3508))),
    DocumentPreset("4K wallpaper", "3840 × 2160", NewDocumentSpec(name = "4K", size = DocSize(3840, 2160), resolutionPpi = 72f)),
)

private val ContentMaxWidth = 880.dp

/**
 * Start screen — zero-confusion first screen:
 * one primary action (Open image), one secondary (New canvas), quick sizes, recent work.
 */
@Composable
fun HomeScreen(
    onNewDocument: (NewDocumentSpec) -> Unit,
    onOpen: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenLibrary: (() -> Unit)? = null,
    recents: List<RecentDocument>? = emptyList(),
    onOpenRecent: (String) -> Unit = {},
    onOpenRecentInNewWindow: (String) -> Unit = {},
    onRemoveRecent: (String) -> Unit = {},
    onTogglePin: (RecentDocument) -> Unit = {},
    recentActionFeedback: HomeActionFeedback? = null,
    onPaste: (() -> Unit)? = null,
    savedPresets: List<NewDocumentSpec> = emptyList(),
    onSavePreset: ((NewDocumentSpec) -> Unit)? = null,
    defaultSpace: WorkingSpace = WorkingSpace.DISPLAY_P3,
) {
    var showNew by rememberSaveable { mutableStateOf(false) }
    ActionScope(ShortcutScope.EDITOR) {
        on("app.open", onOpen)
        if (onPaste != null) on("edit.paste", onPaste)
        on("app.new") { showNew = true }
    }
    if (showNew) {
        NewDocumentDialog(
            onCreate = { spec ->
                showNew = false
                onNewDocument(spec)
            },
            onCancel = { showNew = false },
            savedPresets = savedPresets,
            onSavePreset = onSavePreset,
            initial = DefaultPresets.first().spec.copy(profile = ColorProfileRef.BuiltIn(defaultSpace)),
        )
    }
    val c = Ae.colors
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxSize().background(c.app)) {
        // Phones and narrow windows: one column, no keyboard hints (LS-D2).
        val compact = maxWidth < CompactWidth
        val largeText = LocalDensity.current.fontScale >= 1.5f
        val stackedActions = compact || largeText
        val quickSizeColumns = if (stackedActions) 2 else DefaultPresets.size
        Column(Modifier.fillMaxSize()) {
            TopBar(onOpenSettings, onOpenLibrary, compact)
            AeDivider()
            Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth()
                        .padding(horizontal = if (compact) 16.dp else 32.dp, vertical = if (compact) 24.dp else 48.dp),
                    verticalArrangement = Arrangement.spacedBy(if (compact) 28.dp else 40.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            AeText("Start editing", style = Ae.type.display)
                            AeText("Open a photo to edit, or start from a blank canvas.", color = c.textSecondary)
                        }
                        val open = @Composable { m: Modifier ->
                            ActionCard(
                                icon = AeIcons.Open,
                                title = "Open image…",
                                body = "JPEG, PNG, HEIC, WebP, RAW or PSD",
                                shortcut = shortcutLabel("app.open").takeUnless { stackedActions },
                                primary = true,
                                onClick = onOpen,
                                modifier = m,
                            )
                        }
                        val new = @Composable { m: Modifier ->
                            ActionCard(
                                icon = AeIcons.Add,
                                title = "New canvas…",
                                body = "Choose size, resolution and color",
                                shortcut = shortcutLabel("app.new").takeUnless { stackedActions },
                                primary = false,
                                onClick = { showNew = true },
                                modifier = m,
                            )
                        }
                        if (stackedActions) {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                open(Modifier.fillMaxWidth())
                                new(Modifier.fillMaxWidth())
                            }
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                open(Modifier.weight(1f))
                                new(Modifier.weight(1f))
                            }
                        }
                        if (onPaste != null && !stackedActions) Hint()
                    }

                    Section("Quick sizes") {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            DefaultPresets.chunked(quickSizeColumns).forEach { row ->
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    row.forEach { p -> PresetCard(p, Modifier.weight(1f)) { onNewDocument(p.spec) } }
                                }
                            }
                        }
                    }

                    Section("Recent") {
                        recentActionFeedback?.let { feedback ->
                            AeText(
                                feedback.detail,
                                style = Ae.type.caption,
                                color = if (feedback.phase == HomeActionPhase.FAILED) c.warning else c.textSecondary,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            )
                        }
                        when {
                            recents == null -> Spacer(Modifier.height(120.dp))

                            // loading: reserve space, no flicker
                            recents.isEmpty() -> Box(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 120.dp)
                                    .border(1.dp, c.lineHairline, Ae.shapes.popover),
                                contentAlignment = Alignment.Center,
                            ) {
                                AeText("Files you open or save will appear here.", color = c.textTertiary)
                            }

                            else -> RecentGrid(
                                recents,
                                onOpenRecent,
                                onOpenRecentInNewWindow,
                                onRemoveRecent,
                                onTogglePin,
                                columns = if (compact) 2 else RECENT_COLUMNS,
                            )
                        }
                    }
                }
            }
        }
    }
}

private val CompactWidth = 600.dp

@Composable
private fun TopBar(onOpenSettings: () -> Unit, onOpenLibrary: (() -> Unit)?, compact: Boolean) {
    AeAppHeader {
        // In desktop windowing the system app chip already shows our icon; don't repeat it.
        if (LocalCaptionBar.current.start == 0.dp) {
            Image(painterResource(DesignR.drawable.croptop_mark), contentDescription = null, modifier = Modifier.height(20.dp))
        }
        AeText("CropTop", style = Ae.type.title, modifier = Modifier.weight(1f))
        SearchCommandsButton(compact = compact)
        if (onOpenLibrary != null) {
            if (compact) {
                AeIconButton(AeIcons.Library, "Library", onOpenLibrary)
            } else {
                AeButton("Library", onOpenLibrary, style = AeButtonStyle.Ghost, icon = AeIcons.Library)
            }
        }
        AeIconButton(AeIcons.Settings, "Settings", onOpenSettings, shortcut = shortcutLabel("app.settings"))
    }
}

@Composable
private fun Hint() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AeText("You can also drop images anywhere in this window, or paste with", style = Ae.type.caption, color = Ae.colors.textTertiary)
        AeKbd("Ctrl+V")
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AeText(title.uppercase(), style = Ae.type.panelHeader, color = Ae.colors.textSecondary)
        content()
    }
}

@Composable
private fun ActionCard(
    icon: ImageVector,
    title: String,
    body: String,
    shortcut: String?,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Ae.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg = when {
        primary && hovered -> c.accent.copy(alpha = 0.22f)
        primary -> c.accentMuted
        hovered -> c.surface2
        else -> c.surface1
    }
    Row(
        modifier
            .heightIn(min = 88.dp)
            .background(bg, Ae.shapes.popover)
            .border(
                1.dp,
                if (primary) {
                    c.accent
                } else if (hovered) {
                    c.lineStrong
                } else {
                    c.lineHairline
                },
                Ae.shapes.popover,
            )
            .hoverable(interaction)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            Modifier.size(40.dp).background(if (primary) c.accent else c.surface3, Ae.shapes.control),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = if (primary) c.onAccent else c.textPrimary, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AeText(title, style = Ae.type.title, maxLines = 2)
            AeText(body, style = Ae.type.caption, color = c.textSecondary, maxLines = 2)
        }
        if (shortcut != null) AeKbd(shortcut)
    }
}

@Composable
private fun PresetCard(preset: DocumentPreset, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Ae.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val ratio = preset.spec.size.width.toFloat() / preset.spec.size.height
    Column(
        modifier
            .background(if (hovered) c.surface2 else c.surface1, Ae.shapes.control)
            .border(1.dp, if (hovered) c.lineStrong else c.lineHairline, Ae.shapes.control)
            .hoverable(interaction)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Aspect thumbnail so sizes are recognizable at a glance.
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            val w = if (ratio >= 1f) 40f else 40f * ratio
            val h = if (ratio >= 1f) 40f / ratio else 40f
            Box(Modifier.size(w.dp, h.dp).border(1.5.dp, c.textTertiary, Ae.shapes.chip))
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            AeText(preset.label, style = Ae.type.label)
            AeText("${preset.detail} px", style = Ae.type.caption, color = c.textSecondary, maxLines = 2)
        }
    }
}

private const val RECENT_COLUMNS = 4
private const val THUMB_PX = 320

@Composable
private fun RecentGrid(
    recents: List<RecentDocument>,
    onOpen: (String) -> Unit,
    onOpenInNewWindow: (String) -> Unit,
    onRemove: (String) -> Unit,
    onTogglePin: (RecentDocument) -> Unit,
    columns: Int = RECENT_COLUMNS,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        recents.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { doc ->
                    AeContextMenuArea(
                        modifier = Modifier.weight(1f),
                        entries = {
                            listOf(
                                AeMenuEntry.Item("Open", { onOpen(doc.uri) }, icon = AeIcons.Open),
                                AeMenuEntry.Item("Open in New Window", { onOpenInNewWindow(doc.uri) }),
                                AeMenuEntry.Divider,
                                AeMenuEntry.Item(if (doc.pinned) "Unpin" else "Pin to Top", { onTogglePin(doc) }),
                                AeMenuEntry.Item("Remove from Recent", { onRemove(doc.uri) }, icon = AeIcons.Close),
                            )
                        },
                    ) {
                        RecentCard(doc) { onOpen(doc.uri) }
                    }
                }
                // Keep card widths constant on the last row.
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun RecentCard(doc: RecentDocument, onClick: () -> Unit) {
    val c = Ae.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (hovered) c.surface2 else c.surface1, Ae.shapes.control)
            .border(1.dp, if (hovered) c.lineStrong else c.lineHairline, Ae.shapes.control)
            .hoverable(interaction)
            .clickable(interaction, indication = null, role = Role.Button, onClickLabel = "Open ${doc.name}", onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand),
    ) {
        Box(
            Modifier.fillMaxWidth().height(112.dp).background(c.canvas, Ae.shapes.control),
            contentAlignment = Alignment.Center,
        ) {
            // The provider's thumbnail (photos, PNG/JPEG exports); .croptop files keep the placeholder icon.
            val context = androidx.compose.ui.platform.LocalContext.current
            val thumb by androidx.compose.runtime.produceState(
                app.croptop.core.data.thumbnails.ThumbnailLoader.cached(doc.uri, THUMB_PX),
                doc.uri,
            ) { if (value == null) value = app.croptop.core.data.thumbnails.ThumbnailLoader.load(context, doc.uri, THUMB_PX) }
            val image = thumb
            if (image != null) {
                androidx.compose.foundation.Image(
                    image.asImageBitmap(),
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(Ae.shapes.control),
                )
            } else {
                Icon(AeIcons.Image, contentDescription = null, tint = c.textTertiary, modifier = Modifier.size(28.dp))
            }
            if (doc.pinned) {
                AeText(
                    "PINNED",
                    style = Ae.type.micro,
                    color = c.textSecondary,
                    modifier = Modifier.align(
                        Alignment.TopEnd,
                    ).padding(8.dp).background(c.surface2, Ae.shapes.chip).padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            AeText(doc.name, style = Ae.type.label)
            val detail = listOfNotNull(doc.size?.let { "${it.width} × ${it.height}" }, relativeTime(doc.lastOpenedAt)).joinToString(" · ")
            AeText(detail, style = Ae.type.caption, color = c.textSecondary)
        }
    }
}

private fun relativeTime(millis: Long): String =
    DateUtils.getRelativeTimeSpanString(millis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
