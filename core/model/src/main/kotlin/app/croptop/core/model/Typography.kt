package app.croptop.core.model

import kotlinx.serialization.Serializable

/**
 * Character and paragraph styles for text layers. Units follow Photoshop so
 * PSD text maps cleanly: sizes and leading in points, tracking in 1/1000 em, scales in percent.
 * The text engine renders these; until it lands the editor keeps them as tool settings.
 */
@Serializable
data class CharacterStyle(
    val family: String = "Roboto",
    /** Style name within the family, e.g. "Regular", "Bold Italic". */
    val style: String = "Regular",
    val sizePt: Float = 24f,
    /** Null = Auto (120% of the size). */
    val leadingPt: Float? = null,
    val tracking: Int = 0,
    val kerning: Kerning = Kerning.METRICS,
    val baselineShiftPt: Float = 0f,
    val horizontalScale: Float = 100f,
    val verticalScale: Float = 100f,
    val caps: Caps = Caps.NORMAL,
    val position: GlyphPosition = GlyphPosition.NORMAL,
    val underline: Boolean = false,
    val strikethrough: Boolean = false,
    /** OpenType feature tags switched on beyond the defaults (liga/kern are on unless listed in [disabledFeatures]). */
    val features: Set<String> = emptySet(),
    val disabledFeatures: Set<String> = emptySet(),
    /** Variable font axis values by tag ("wght", "wdth", …); missing axes use the font default. */
    val axes: Map<String, Float> = emptyMap(),
    val color: RgbaColor = RgbaColor.Black,
) {
    val effectiveLeadingPt: Float get() = leadingPt ?: (sizePt * AUTO_LEADING)

    /** Clamps every field to the ranges the panels allow (Photoshop's limits). */
    fun normalized(): CharacterStyle = copy(
        sizePt = sizePt.coerceIn(MIN_SIZE, MAX_SIZE),
        leadingPt = leadingPt?.coerceIn(MIN_SIZE, MAX_SIZE),
        tracking = tracking.coerceIn(-MAX_TRACKING, MAX_TRACKING),
        baselineShiftPt = baselineShiftPt.coerceIn(-MAX_SIZE, MAX_SIZE),
        horizontalScale = horizontalScale.coerceIn(MIN_SCALE, MAX_SCALE),
        verticalScale = verticalScale.coerceIn(MIN_SCALE, MAX_SCALE),
    )

    companion object {
        const val AUTO_LEADING = 1.2f
        const val MIN_SIZE = 0.01f
        const val MAX_SIZE = 1296f
        const val MAX_TRACKING = 1000
        const val MIN_SCALE = 0f
        const val MAX_SCALE = 1000f

        /** Common sizes offered in the size field's menu (Photoshop's list). */
        val PresetSizes = listOf(6f, 7f, 8f, 9f, 10f, 11f, 12f, 14f, 18f, 24f, 30f, 36f, 48f, 60f, 72f)
    }
}

@Serializable
enum class Kerning(val label: String) {
    METRICS("Metrics"),
    OPTICAL("Optical"),
    NONE("0"),
}

@Serializable
enum class Caps { NORMAL, ALL_CAPS, SMALL_CAPS }

@Serializable
enum class GlyphPosition { NORMAL, SUPERSCRIPT, SUBSCRIPT }

/** OpenType features the Character panel exposes as toggles. */
enum class OpenTypeFeature(val tag: String, val label: String, val shortLabel: String, val onByDefault: Boolean = false) {
    LIGATURES("liga", "Standard Ligatures", "Ligatures", onByDefault = true),
    DISCRETIONARY("dlig", "Discretionary Ligatures", "Discretionary"),
    CONTEXTUAL("calt", "Contextual Alternates", "Contextual", onByDefault = true),
    SWASH("swsh", "Swash", "Swash"),
    STYLISTIC("salt", "Stylistic Alternates", "Stylistic"),
    ORDINALS("ordn", "Ordinals", "Ordinals"),
    FRACTIONS("frac", "Fractions", "Fractions"),
    TABULAR("tnum", "Tabular Figures", "Tabular"),
    OLDSTYLE("onum", "Oldstyle Figures", "Oldstyle"),
    SLASHED_ZERO("zero", "Slashed Zero", "Slashed zero"),
    ;

    fun isOn(style: CharacterStyle) = if (onByDefault) tag !in style.disabledFeatures else tag in style.features

    fun toggle(style: CharacterStyle): CharacterStyle = when {
        onByDefault && isOn(style) -> style.copy(disabledFeatures = style.disabledFeatures + tag)
        onByDefault -> style.copy(disabledFeatures = style.disabledFeatures - tag)
        isOn(style) -> style.copy(features = style.features - tag)
        else -> style.copy(features = style.features + tag)
    }
}

@Serializable
enum class TextAlign(val label: String) {
    LEFT("Left"),
    CENTER("Center"),
    RIGHT("Right"),
    JUSTIFY_LEFT("Justify last left"),
    JUSTIFY_CENTER("Justify last centered"),
    JUSTIFY_RIGHT("Justify last right"),
    JUSTIFY_ALL("Justify all"),
    ;

    /** Justified alignments need a paragraph (box) text; point text ignores them. */
    val isJustified: Boolean get() = name.startsWith("JUSTIFY")
}

@Serializable
data class ParagraphStyle(
    val align: TextAlign = TextAlign.LEFT,
    val indentLeftPt: Float = 0f,
    val indentRightPt: Float = 0f,
    val indentFirstLinePt: Float = 0f,
    val spaceBeforePt: Float = 0f,
    val spaceAfterPt: Float = 0f,
    val hyphenate: Boolean = false,
) {
    fun normalized(): ParagraphStyle = copy(
        indentLeftPt = indentLeftPt.coerceIn(-MAX_INDENT, MAX_INDENT),
        indentRightPt = indentRightPt.coerceIn(-MAX_INDENT, MAX_INDENT),
        indentFirstLinePt = indentFirstLinePt.coerceIn(-MAX_INDENT, MAX_INDENT),
        spaceBeforePt = spaceBeforePt.coerceIn(0f, MAX_INDENT),
        spaceAfterPt = spaceAfterPt.coerceIn(0f, MAX_INDENT),
    )

    companion object {
        const val MAX_INDENT = 1296f
    }
}

/**
 * An editable text layer's content: the text, its styles, and where it sits. [origin] is the
 * first baseline's start for point text and the box's top-left for paragraph text; [boxWidth] wraps
 * paragraph text (document pixels). The rendered pixels are a rebuildable cache.
 */
@Serializable
data class TextLayerInfo(
    val text: String,
    val character: CharacterStyle = CharacterStyle(),
    val paragraph: ParagraphStyle = ParagraphStyle(),
    val kind: TextKind = TextKind.POINT,
    val origin: DocPoint = DocPoint(0f, 0f),
    val boxWidth: Float = 0f,
    /** Rotation about [origin] in degrees, clockwise on screen (Free Transform / canvas rotation). */
    val rotation: Float = 0f,
)

/** Point text (click) grows from the baseline; paragraph text (drag) wraps inside a box. */
@Serializable
enum class TextKind { POINT, PARAGRAPH }

/** One installed or downloaded font face, as the font picker lists it. */
data class FontFace(
    val family: String,
    val style: String,
    /** Path to the file (system font or downloaded). */
    val path: String,
    /** Index inside a .ttc collection. */
    val index: Int = 0,
    val weight: Int = 400,
    val italic: Boolean = false,
    val axes: List<FontAxis> = emptyList(),
) {
    val isVariable: Boolean get() = axes.isNotEmpty()
}

data class FontAxis(val tag: String, val name: String, val min: Float, val default: Float, val max: Float)

/** A family with its faces, regular first. */
data class FontFamily(val name: String, val faces: List<FontFace>) {
    val isVariable: Boolean get() = faces.any { it.isVariable }

    /** The face for [style], else the closest regular one. */
    fun face(style: String): FontFace = faces.firstOrNull { it.style.equals(style, ignoreCase = true) } ?: faces.first()

    companion object {
        /** Groups faces into families sorted by name; within a family, weight then upright before italic. */
        fun group(faces: List<FontFace>): List<FontFamily> = faces
            .groupBy { it.family }
            .map { (name, f) ->
                FontFamily(name, f.distinctBy { it.style }.sortedWith(compareBy({ it.italic }, { kotlin.math.abs(it.weight - 400) }, { it.weight })))
            }
            .sortedBy { it.name.lowercase() }
    }
}

/** Filters families for the picker: favorites, then name matches (prefix before substring). */
fun List<FontFamily>.search(query: String, favorites: Set<String> = emptySet(), favoritesOnly: Boolean = false): List<FontFamily> {
    val q = query.trim().lowercase()
    return asSequence()
        .filter { !favoritesOnly || it.name in favorites }
        .filter { q.isEmpty() || q in it.name.lowercase() }
        .sortedWith(compareBy({ q.isNotEmpty() && !it.name.lowercase().startsWith(q) }, { it.name !in favorites }, { it.name.lowercase() }))
        .toList()
}
