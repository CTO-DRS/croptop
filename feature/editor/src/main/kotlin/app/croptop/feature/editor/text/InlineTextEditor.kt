package app.croptop.feature.editor.text

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign as ComposeTextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import app.croptop.core.designsystem.component.AeButton
import app.croptop.core.designsystem.component.AeButtonStyle
import app.croptop.core.designsystem.theme.Ae
import app.croptop.core.model.CharacterStyle
import app.croptop.core.model.DocPoint
import app.croptop.core.model.ParagraphStyle
import app.croptop.core.model.TextAlign
import app.croptop.core.model.ViewportState
import kotlin.math.roundToInt

/** Screen-space geometry of the on-canvas text editor (pure, so it can be tested). */
object InlineTextGeometry {
    /** Fraction of the font size between the top of the field and the first baseline. */
    const val ASCENT = 0.92f

    /** A font size in points as document pixels at [ppi]; Photoshop's 72 points per inch. */
    fun docPx(sizePt: Float, ppi: Float) = sizePt * (ppi / 72f)

    /** Font size on screen at the viewport's zoom. */
    fun viewFontPx(sizePt: Float, ppi: Float, zoom: Float) = (docPx(sizePt, ppi) * zoom).coerceAtLeast(MIN_VIEW_PX)

    /** Top-left of the field so the first line's baseline sits on [origin]: the first baseline for point text, the box top for paragraph text. */
    fun fieldTopLeft(viewport: ViewportState, origin: DocPoint, paragraph: Boolean, viewFontPx: Float): Pair<Float, Float> {
        val p = viewport.docToView(origin)
        return p.x to (if (paragraph) p.y else p.y - viewFontPx * ASCENT)
    }

    /** Width of a paragraph field on screen, or null (grow with the text) for point text. */
    fun fieldWidthPx(boxWidthDoc: Float?, zoom: Float) = boxWidthDoc?.takeIf { it > 0f }?.let { it * zoom }

    const val MIN_VIEW_PX = 8f
}

private fun TextAlign.compose() = when (this) {
    TextAlign.LEFT, TextAlign.JUSTIFY_LEFT -> ComposeTextAlign.Left
    TextAlign.CENTER, TextAlign.JUSTIFY_CENTER -> ComposeTextAlign.Center
    TextAlign.RIGHT, TextAlign.JUSTIFY_RIGHT -> ComposeTextAlign.Right
    TextAlign.JUSTIFY_ALL -> ComposeTextAlign.Justify
}

/**
 * The Type tool's on-canvas editor: a text field placed where the text will go, sized to the current zoom and styled with the
 * Character and Paragraph panels, so typing, IME composition, selection, caret and copy/paste are the platform's own. Ctrl+Enter
 * (or the check button) commits, Esc (or the cross) cancels. Pointer events inside it never reach the canvas tool.
 */
@Composable
fun InlineTextEditor(
    text: String,
    onTextChange: (String) -> Unit,
    viewport: ViewportState,
    origin: DocPoint,
    boxWidth: Float?,
    ppi: Float,
    character: CharacterStyle,
    paragraph: ParagraphStyle,
    editing: Boolean,
    onCommit: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    /** Called with true while the field is open, so the host can suppress single-key shortcuts. */
    onTyping: (Boolean) -> Unit = {},
    /** The editor's rectangle in the canvas' view space (null once it leaves), so the canvas tool can ignore presses on it. */
    onBounds: (Rect?) -> Unit = {},
    /** The picked face's font file, so the field previews in the real face (and variable-font axes); null uses the platform default. */
    fontFile: java.io.File? = null,
) {
    // The host owns the text (so a click elsewhere can commit it); the field keeps its own selection.
    var selection by remember { mutableStateOf(TextRange(text.length)) }
    val value = TextFieldValue(text, TextRange(selection.start.coerceIn(0, text.length), selection.end.coerceIn(0, text.length)))
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    // Single-key tool shortcuts must not fire while typing.
    val typing by rememberUpdatedState(onTyping)
    val reportBounds by rememberUpdatedState(onBounds)
    DisposableEffect(Unit) {
        typing(true)
        onDispose {
            typing(false)
            reportBounds(null)
        }
    }
    val density = LocalDensity.current
    val fontPx = InlineTextGeometry.viewFontPx(character.sizePt, ppi, viewport.zoom)
    val (left, top) = InlineTextGeometry.fieldTopLeft(viewport, origin, boxWidth != null, fontPx)
    val widthPx = InlineTextGeometry.fieldWidthPx(boxWidth, viewport.zoom)
    val c = Ae.colors
    val valid = text.isNotBlank()
    fun commit() {
        if (valid) onCommit(text) else onCancel()
    }
    val ink = Color(character.color.r, character.color.g, character.color.b, character.color.a)
    val family = remember(fontFile, character.axes, character.style) { previewFamily(fontFile, character) }
    val style = TextStyle(
        fontFamily = family,
        color = ink,
        fontSize = with(density) { fontPx.toSp() },
        fontWeight = if (character.style.contains("Bold", ignoreCase = true)) FontWeight.Bold else FontWeight.Normal,
        fontStyle = if (character.style.contains("Italic", ignoreCase = true)) FontStyle.Italic else FontStyle.Normal,
        letterSpacing = (character.tracking / 1000f).em,
        textAlign = paragraph.align.compose(),
        lineHeight = with(density) { (InlineTextGeometry.docPx(character.effectiveLeadingPt, ppi) * viewport.zoom).toSp() },
    )
    // Point text grows with what is typed: the widest line, a caret's room and the field's padding.
    val measurer = rememberTextMeasurer()
    val pointWidthPx = remember(text, style) {
        val widest = text.split('\n').maxOf { line -> measurer.measure(line.ifEmpty { " " }, style).size.width }
        (widest + with(density) { 12.dp.toPx() }).coerceAtLeast(with(density) { 48.dp.toPx() })
    }
    Column(
        modifier
            .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
            .onSizeChanged { size -> onBounds(Rect(left, top, left + size.width, top + size.height)) },
    ) {
        BasicTextField(
            value = value,
            onValueChange = { next ->
                selection = next.selection
                onTextChange(next.text.take(MAX_TEXT_LENGTH))
            },
            textStyle = style,
            cursorBrush = SolidColor(c.accent),
            modifier = Modifier.width(with(density) { (widthPx ?: pointWidthPx).toDp() })
                .background(Color.White.copy(alpha = 0.18f))
                .border(1.dp, c.accent)
                .padding(horizontal = 2.dp)
                .semantics { contentDescription = "Text content" }
                .focusRequester(focus)
                .onPreviewKeyEvent { e ->
                    when {
                        e.type == KeyEventType.KeyDown && e.key == Key.Escape -> {
                            onCancel()
                            true
                        }
                        e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter) && (e.isCtrlPressed || e.isMetaPressed) -> {
                            commit()
                            true
                        }
                        else -> false
                    }
                },
        )
        Row(Modifier.padding(top = 4.dp)) {
            AeButton("Cancel", onCancel, style = AeButtonStyle.Secondary)
            Spacer(Modifier.width(4.dp))
            AeButton(if (editing) "Update" else "Place", ::commit, style = AeButtonStyle.Primary, enabled = valid)
        }
    }
}

/**
 * A Compose family for the picked font file at the Character panel's variable-font axis values; null when the file cannot be
 * loaded (the field then falls back to the platform default).
 */
private fun previewFamily(file: java.io.File?, character: CharacterStyle): FontFamily? {
    if (file == null || !file.isFile) return null
    return runCatching {
        val settings = FontVariation.Settings(*character.axes.map { (tag, value) -> FontVariation.Setting(tag, value) }.toTypedArray())
        FontFamily(
            Font(
                file,
                weight = if (character.style.contains("Bold", ignoreCase = true)) FontWeight.Bold else FontWeight.Normal,
                style = if (character.style.contains("Italic", ignoreCase = true)) FontStyle.Italic else FontStyle.Normal,
                variationSettings = settings,
            ),
        )
    }.getOrNull()
}

private const val MAX_TEXT_LENGTH = 10_000
