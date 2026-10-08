package app.croptop.feature.editor.text

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily as ComposeFontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.croptop.core.designsystem.component.AeBarSlider
import app.croptop.core.designsystem.component.AeCheckbox
import app.croptop.core.designsystem.component.AeColorPicker
import app.croptop.core.designsystem.component.AeColorWell
import app.croptop.core.designsystem.component.AeDropdown
import app.croptop.core.designsystem.component.AeIconButton
import app.croptop.core.designsystem.component.AeNumberField
import app.croptop.core.designsystem.component.AeSlider
import app.croptop.core.designsystem.component.AeText
import app.croptop.core.designsystem.component.AeToggleIconButton
import app.croptop.core.designsystem.icon.AeIcons
import app.croptop.core.designsystem.theme.Ae
import app.croptop.core.model.Caps
import app.croptop.core.model.CharacterStyle
import app.croptop.core.model.FontFace
import app.croptop.core.model.FontFamily
import app.croptop.core.model.GlyphPosition
import app.croptop.core.model.Kerning
import app.croptop.core.model.OpenTypeFeature
import app.croptop.core.model.ParagraphStyle
import app.croptop.core.model.TextAlign
import app.croptop.core.model.search

/** Compose font for a face (falls back to the default font when the file can't be loaded). */
@Composable
internal fun rememberFontFamily(face: FontFace?): ComposeFontFamily = remember(face?.path, face?.index) {
    face?.let { f ->
        runCatching {
            val tf = android.graphics.Typeface.Builder(java.io.File(f.path)).setTtcIndex(f.index).setWeight(f.weight).setItalic(f.italic).build()
            ComposeFontFamily(androidx.compose.ui.text.font.Typeface(tf))
        }.getOrNull()
    } ?: ComposeFontFamily.Default
}

/** Font family field: shows the family in its own font; opens a searchable list with favorites. */
@Composable
fun FontFamilyField(ui: TypeUi, modifier: Modifier = Modifier, onActionCompleted: (String) -> Unit = {}) {
    var open by remember { mutableStateOf(false) }
    var getMore by remember { mutableStateOf(false) }
    if (getMore && ui.google != null) {
        GoogleFontsDialog(ui.google, onDismiss = {
            getMore = false
            onActionCompleted("Close Google Fonts")
        })
    }
    val c = Ae.colors
    Box(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(Ae.density.controlHeight)
                .widthIn(min = 160.dp)
                .background(c.surface3, Ae.shapes.control)
                .border(1.dp, if (open) c.accent else c.lineHairline, Ae.shapes.control)
                .clickable {
                    open = true
                    onActionCompleted("Open font picker")
                }
                .semantics { contentDescription = "Font family, ${ui.character.family}" }
                .padding(start = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AeText(
                ui.character.family,
                style = Ae.type.body.copy(fontFamily = rememberFontFamily(ui.family?.face(ui.character.style))),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(4.dp))
            androidx.compose.material3.Icon(AeIcons.ChevronDown, null, tint = c.textSecondary, modifier = Modifier.size(16.dp))
        }
        if (open) {
            FontPickerPopup(
                ui,
                onDismiss = { open = false },
                onDismissRequest = {
                    open = false
                    onActionCompleted("Dismiss font picker")
                },
                onActionCompleted = onActionCompleted,
                onGetMore = {
                    open = false
                    getMore = true
                    onActionCompleted("Open Google Fonts")
                },
            )
        }
    }
}

@Composable
private fun FontPickerPopup(
    ui: TypeUi,
    onDismiss: () -> Unit,
    onDismissRequest: () -> Unit,
    onActionCompleted: (String) -> Unit = {},
    onGetMore: () -> Unit = {},
) {
    val c = Ae.colors
    var query by remember { mutableStateOf("") }
    var favoritesOnly by remember { mutableStateOf(false) }
    val families = ui.families
    val results = remember(families, query, ui.favorites, favoritesOnly) { families.orEmpty().search(query, ui.favorites, favoritesOnly) }
    // Recently used families lead the list when not searching (Photoshop's top section).
    val recent = if (query.isBlank() && !favoritesOnly) ui.recent.mapNotNull { r -> families?.firstOrNull { it.name == r } }.take(5) else emptyList()
    var highlight by remember(results) { mutableIntStateOf(results.indexOfFirst { it.name == ui.character.family }.coerceAtLeast(0)) }
    val list = rememberLazyListState()
    LaunchedEffect(highlight, results) {
        // Keep the highlighted row in view, with two rows of context above it.
        val row = highlight + recent.size.let { if (it > 0) it + 1 else 0 }
        if (highlight in results.indices) list.scrollToItem((row - 2).coerceAtLeast(0))
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    fun choose(f: FontFamily) {
        ui.chooseFamily(f)
        onActionCompleted("Set font family to ${f.name}")
        onDismiss()
    }
    Popup(offset = IntOffset(0, 40), onDismissRequest = onDismissRequest, properties = PopupProperties(focusable = true)) {
        Column(
            Modifier
                .width(320.dp)
                .background(c.surface2, Ae.shapes.popover)
                .border(1.dp, c.lineStrong, Ae.shapes.popover)
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(
                    Modifier.weight(1f).height(Ae.density.controlHeight).background(c.surface3, Ae.shapes.control)
                        .border(1.dp, c.lineHairline, Ae.shapes.control).padding(horizontal = 8.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (query.isEmpty()) AeText("Search fonts", style = Ae.type.body, color = c.textTertiary)
                    BasicTextField(
                        query,
                        { query = it },
                        singleLine = true,
                        textStyle = Ae.type.body.copy(color = c.textPrimary),
                        cursorBrush = SolidColor(c.accent),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = "Search fonts" }
                            .onPreviewKeyEvent { e ->
                                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when (e.key) {
                                    Key.DirectionDown -> {
                                        highlight = (highlight + 1).coerceAtMost(results.lastIndex)
                                        true
                                    }

                                    Key.DirectionUp -> {
                                        highlight = (highlight - 1).coerceAtLeast(0)
                                        true
                                    }

                                    Key.Enter, Key.NumPadEnter -> {
                                        results.getOrNull(highlight)?.let(::choose)
                                        true
                                    }

                                    Key.Escape -> {
                                        onDismissRequest()
                                        true
                                    }

                                    else -> false
                                }
                            },
                    )
                }
                AeToggleIconButton(AeIcons.StarFilled, "Show favorites only", favoritesOnly, {
                    favoritesOnly = it
                    onActionCompleted(if (it) "Show favorite fonts only" else "Show all fonts")
                })
            }
            when {
                families == null -> AeText("Loading fonts…", style = Ae.type.caption, color = c.textSecondary, modifier = Modifier.padding(8.dp))

                results.isEmpty() -> AeText(
                    if (favoritesOnly) "No favorites yet. Star a font to add it." else "No fonts match “$query”.",
                    style = Ae.type.caption,
                    color = c.textSecondary,
                    modifier = Modifier.padding(8.dp),
                )

                else -> LazyColumn(Modifier.heightIn(max = 360.dp), state = list) {
                    if (recent.isNotEmpty()) {
                        items(recent, key = { "r:" + it.name }) { f ->
                            FontRow(f, ui, highlighted = false, onChoose = ::choose, onActionCompleted = onActionCompleted)
                        }
                        item(key = "divider") { Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(c.lineHairline)) }
                    }
                    items(results.size, key = {
                        results[it].name
                    }) { i ->
                        FontRow(results[i], ui, highlighted = i == highlight, onChoose = ::choose, onActionCompleted = onActionCompleted)
                    }
                }
            }
            if (ui.google != null) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(c.lineHairline))
                app.croptop.core.designsystem.component.AeButton(
                    "Get more fonts…",
                    onGetMore,
                    style = app.croptop.core.designsystem.component.AeButtonStyle.Secondary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun FontRow(
    f: FontFamily,
    ui: TypeUi,
    highlighted: Boolean,
    onChoose: (FontFamily) -> Unit,
    onActionCompleted: (String) -> Unit,
) {
    val c = Ae.colors
    val current = f.name == ui.character.family
    Row(
        Modifier
            .fillMaxWidth()
            .height(36.dp)
            .background(if (highlighted) c.surface4 else androidx.compose.ui.graphics.Color.Transparent, Ae.shapes.control)
            .clickable { onChoose(f) }
            .semantics { selected = current }
            .padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            AeText(f.name, style = Ae.type.label, color = if (current) c.accent else c.textPrimary)
            AeText(
                "Sphinx of black quartz",
                style = TextStyle(fontFamily = rememberFontFamily(f.faces.first()), fontSize = 13.sp),
                color = c.textSecondary,
            )
        }
        if (f.isVariable) AeText("Variable", style = Ae.type.micro, color = c.textTertiary)
        val fav = f.name in ui.favorites
        AeIconButton(
            if (fav) AeIcons.StarFilled else AeIcons.Star,
            if (fav) "Remove ${f.name} from favorites" else "Add ${f.name} to favorites",
            {
                ui.onToggleFavorite(f.name)
                onActionCompleted(if (fav) "Remove ${f.name} from favorites" else "Add ${f.name} to favorites")
            },
            tint = if (fav) c.warning else c.textTertiary,
        )
    }
}

@Composable
private fun Field(label: String, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AeText(label, style = Ae.type.caption, color = Ae.colors.textSecondary, modifier = Modifier.width(64.dp))
        content()
    }
}

/** Character panel: family, style, size, leading, kerning, tracking, scales, caps, features, axes. */
@Composable
fun CharacterPanelContent(ui: TypeUi, onActionCompleted: (String) -> Unit = {}) {
    val s = ui.character
    val set = ui.onCharacter
    val family = ui.family
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FontFamilyField(ui, Modifier.fillMaxWidth(), onActionCompleted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val styles = family?.faces?.map { it.style } ?: listOf(s.style)
            AeDropdown(styles, s.style, {
                set(s.copy(style = it))
                onActionCompleted("Set text style to ${it.lowercase()}")
            }, { it })
            app.croptop.core.designsystem.component.AeBarSlider(
                "Size",
                s.sizePt,
                { set(s.copy(sizePt = it)) },
                range = CharacterStyle.MIN_SIZE..CharacterStyle.MAX_SIZE,
                defaultValue = 24f,
                decimals = 1,
                unit = "pt",
                width = 150.dp,
                curve = 2f,
                contentDescription = "Font size",
                onValueChangeFinished = { onActionCompleted("Set text size") },
            )
        }
        Field("Leading") {
            AeDropdown(listOf(true, false), s.leadingPt == null, { auto ->
                set(s.copy(leadingPt = if (auto) null else s.effectiveLeadingPt))
                onActionCompleted(if (auto) "Set text leading to auto" else "Set fixed text leading")
            }, { if (it) "Auto" else "Fixed" })
            s.leadingPt?.let { lead ->
                AeNumberField(
                    lead,
                    { set(s.copy(leadingPt = it)) },
                    decimals = 1,
                    unit = "pt",
                    width = 72.dp,
                    contentDescription = "Leading",
                    onCommit = { onActionCompleted("Set text leading") },
                )
            }
        }
        Field("Kerning") {
            AeDropdown(Kerning.entries, s.kerning, {
                set(s.copy(kerning = it))
                onActionCompleted("Set text kerning to ${it.label.lowercase()}")
            }, { it.label })
        }
        AeBarSlider(
            "Tracking", s.tracking.toFloat(), { set(s.copy(tracking = it.toInt())) },
            range = -CharacterStyle.MAX_TRACKING.toFloat()..CharacterStyle.MAX_TRACKING.toFloat(), defaultValue = 0f, step = 10f, curve = 1f,
            onValueChangeFinished = { onActionCompleted("Set text tracking") },
        )
        AeBarSlider(
            "Vertical scale", s.verticalScale, { set(s.copy(verticalScale = it)) },
            range = CharacterStyle.MIN_SCALE..CharacterStyle.MAX_SCALE, defaultValue = 100f, unit = "%", curve = 2f,
            onValueChangeFinished = { onActionCompleted("Set vertical text scale") },
        )
        AeBarSlider(
            "Horizontal scale", s.horizontalScale, { set(s.copy(horizontalScale = it)) },
            range = CharacterStyle.MIN_SCALE..CharacterStyle.MAX_SCALE, defaultValue = 100f, unit = "%", curve = 2f,
            onValueChangeFinished = { onActionCompleted("Set horizontal text scale") },
        )
        AeBarSlider(
            "Baseline shift", s.baselineShiftPt, { set(s.copy(baselineShiftPt = it)) },
            range = -CharacterStyle.MAX_SIZE..CharacterStyle.MAX_SIZE, defaultValue = 0f, step = 0.1f, decimals = 1, unit = "pt",
            onValueChangeFinished = { onActionCompleted("Set text baseline shift") },
        )
        Field("Color") {
            TextColorWell(
                s,
                set,
                { onActionCompleted("Open text color picker") },
                { onActionCompleted("Set text color") },
                { onActionCompleted("Dismiss text color picker") },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AeDropdown(Caps.entries, s.caps, {
                set(s.copy(caps = it))
                onActionCompleted("Set text case to ${it.name.lowercase().replace('_', ' ')}")
            }, {
                when (it) {
                    Caps.NORMAL -> "Normal case"
                    Caps.ALL_CAPS -> "All caps"
                    Caps.SMALL_CAPS -> "Small caps"
                }
            })
            AeDropdown(GlyphPosition.entries, s.position, {
                set(s.copy(position = it))
                onActionCompleted("Set text position to ${it.name.lowercase()}")
            }, {
                when (it) {
                    GlyphPosition.NORMAL -> "Normal position"
                    GlyphPosition.SUPERSCRIPT -> "Superscript"
                    GlyphPosition.SUBSCRIPT -> "Subscript"
                }
            })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AeCheckbox(s.underline, {
                set(s.copy(underline = it))
                onActionCompleted(if (it) "Underline text" else "Remove text underline")
            }, "Underline")
            AeCheckbox(s.strikethrough, {
                set(s.copy(strikethrough = it))
                onActionCompleted(if (it) "Strikethrough text" else "Remove text strikethrough")
            }, "Strikethrough")
        }
        AeText("OPENTYPE", style = Ae.type.panelHeader, color = Ae.colors.textTertiary)
        OpenTypeFeature.entries.chunked(2).forEach { pair ->
            Row {
                pair.forEach { f ->
                    AeCheckbox(f.isOn(s), {
                        set(f.toggle(s))
                        onActionCompleted("Toggle OpenType ${f.shortLabel}")
                    }, f.shortLabel, Modifier.weight(1f))
                }
            }
        }
        val axes = family?.face(s.style)?.axes.orEmpty()
        if (axes.isNotEmpty()) {
            AeText("VARIABLE AXES", style = Ae.type.panelHeader, color = Ae.colors.textTertiary)
            axes.forEach { a ->
                AeSlider(
                    a.name,
                    s.axes[a.tag] ?: a.default,
                    { set(s.copy(axes = s.axes + (a.tag to it))) },
                    range = a.min..a.max,
                    defaultValue = a.default,
                    decimals = if (a.max - a.min <= 10f) 1 else 0,
                    onValueChangeFinished = { onActionCompleted("Set ${a.name.lowercase()} font axis") },
                )
            }
        }
    }
}

@Composable
private fun TextColorWell(
    s: CharacterStyle,
    set: (CharacterStyle) -> Unit,
    onOpen: () -> Unit = {},
    onEditComplete: () -> Unit = {},
    onDismiss: () -> Unit = {},
) {
    var open by remember { mutableStateOf(false) }
    Box {
        AeColorWell(s.color, "Text color", onClick = {
            open = true
            onOpen()
        })
        if (open) {
            Popup(offset = IntOffset(0, 36), onDismissRequest = {
                open = false
                onDismiss()
            }, properties = PopupProperties(focusable = true)) {
                Box(
                    Modifier.background(Ae.colors.surface2, Ae.shapes.popover).border(1.dp, Ae.colors.lineStrong, Ae.shapes.popover).padding(12.dp)
                        .focusable(),
                ) {
                    AeColorPicker(
                        s.color,
                        { set(s.copy(color = it)) },
                        previous = s.color,
                        onEditComplete = onEditComplete,
                    )
                }
            }
        }
    }
}

val TextAlign.icon
    get() = when (this) {
        TextAlign.LEFT -> AeIcons.AlignLeft
        TextAlign.CENTER -> AeIcons.AlignCenter
        TextAlign.RIGHT -> AeIcons.AlignRight
        else -> AeIcons.AlignJustify
    }

/** Point-text alignments shown as buttons in the options bar; the Paragraph panel has all seven. */
val PointAligns = listOf(TextAlign.LEFT, TextAlign.CENTER, TextAlign.RIGHT)

@Composable
fun AlignButtons(ui: TypeUi, aligns: List<TextAlign> = PointAligns, onActionCompleted: (String) -> Unit = {}) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        aligns.forEach { a ->
            AeToggleIconButton(a.icon, "Align ${a.label.lowercase()}", ui.paragraph.align == a, {
                ui.onParagraph(ui.paragraph.copy(align = a))
                onActionCompleted("Align text ${a.label.lowercase()}")
            })
        }
    }
}

/** Paragraph panel: alignment/justification, indents, spacing, hyphenation. */
@Composable
fun ParagraphPanelContent(ui: TypeUi, onActionCompleted: (String) -> Unit = {}) {
    val p = ui.paragraph
    val set = ui.onParagraph
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AlignButtons(ui, onActionCompleted = onActionCompleted)
        AeDropdown(TextAlign.entries, p.align, {
            set(p.copy(align = it))
            onActionCompleted("Set paragraph alignment to ${it.label.lowercase()}")
        }, { it.label }, Modifier.fillMaxWidth())
        if (p.align.isJustified) {
            AeText(
                "Justification applies to paragraph text (drag a box with the Text tool).",
                style = Ae.type.caption,
                color = Ae.colors.textTertiary,
                maxLines = 2,
            )
        }
        AeBarSlider(
            "Indent left", p.indentLeftPt, { set(p.copy(indentLeftPt = it)) },
            range = -ParagraphStyle.MAX_INDENT..ParagraphStyle.MAX_INDENT, defaultValue = 0f, step = 0.5f, decimals = 1, unit = "pt", curve = 1f,
            onValueChangeFinished = { onActionCompleted("Set left paragraph indent") },
        )
        AeBarSlider(
            "Indent right", p.indentRightPt, { set(p.copy(indentRightPt = it)) },
            range = -ParagraphStyle.MAX_INDENT..ParagraphStyle.MAX_INDENT, defaultValue = 0f, step = 0.5f, decimals = 1, unit = "pt", curve = 1f,
            onValueChangeFinished = { onActionCompleted("Set right paragraph indent") },
        )
        AeBarSlider(
            "First line indent", p.indentFirstLinePt, { set(p.copy(indentFirstLinePt = it)) },
            range = -ParagraphStyle.MAX_INDENT..ParagraphStyle.MAX_INDENT, defaultValue = 0f, step = 0.5f, decimals = 1, unit = "pt", curve = 1f,
            onValueChangeFinished = { onActionCompleted("Set first-line paragraph indent") },
        )
        AeBarSlider(
            "Space before", p.spaceBeforePt, { set(p.copy(spaceBeforePt = it)) },
            range = 0f..ParagraphStyle.MAX_INDENT, defaultValue = 0f, step = 0.5f, decimals = 1, unit = "pt", curve = 1f,
            onValueChangeFinished = { onActionCompleted("Set paragraph space before") },
        )
        AeBarSlider(
            "Space after", p.spaceAfterPt, { set(p.copy(spaceAfterPt = it)) },
            range = 0f..ParagraphStyle.MAX_INDENT, defaultValue = 0f, step = 0.5f, decimals = 1, unit = "pt", curve = 1f,
            onValueChangeFinished = { onActionCompleted("Set paragraph space after") },
        )
        AeCheckbox(p.hyphenate, {
            set(p.copy(hyphenate = it))
            onActionCompleted(if (it) "Enable paragraph hyphenation" else "Disable paragraph hyphenation")
        }, "Hyphenate")
    }
}

/** Text tool options bar: family, style, size, alignment, color. */
@Composable
fun TextOptions(ui: TypeUi, onActionCompleted: (String) -> Unit = {}) {
    val s = ui.character
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FontFamilyField(ui, Modifier.width(200.dp), onActionCompleted)
        AeDropdown(ui.family?.faces?.map { it.style } ?: listOf(s.style), s.style, {
            ui.onCharacter(s.copy(style = it))
            onActionCompleted("Set text style to ${it.lowercase()}")
        }, { it })
        app.croptop.core.designsystem.component.AeBarSlider(
            "Size",
            s.sizePt,
            { ui.onCharacter(s.copy(sizePt = it)) },
            range = CharacterStyle.MIN_SIZE..CharacterStyle.MAX_SIZE,
            defaultValue = 24f,
            decimals = 1,
            unit = "pt",
            width = 150.dp,
            curve = 2f,
            contentDescription = "Font size",
            onValueChangeFinished = { onActionCompleted("Set text size") },
        )
        AlignButtons(ui, onActionCompleted = onActionCompleted)
        TextColorWell(
            s,
            ui.onCharacter,
            { onActionCompleted("Open text color picker") },
            { onActionCompleted("Set text color") },
            { onActionCompleted("Dismiss text color picker") },
        )
    }
}
