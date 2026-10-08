package app.croptop.core.model

import kotlinx.serialization.Serializable

/** Groups follow the Photoshop blend-mode menu separators. */
enum class BlendGroup { NORMAL, DARKEN, LIGHTEN, CONTRAST, INVERSION, COMPONENT }

/**
 * The 27 Photoshop blend modes plus group-only Pass Through.
 *
 * @property psdKey the 4-char key used in PSD layer records (for the PSD reader/writer).
 * @property shortcutKey the letter used with Shift+Alt in the Photoshop keyboard map; null when unbound.
 */
@Serializable
enum class BlendMode(
    val displayName: String,
    val group: BlendGroup,
    val psdKey: String,
    val shortcutKey: Char?,
) {
    PASS_THROUGH("Pass Through", BlendGroup.NORMAL, "pass", null),
    NORMAL("Normal", BlendGroup.NORMAL, "norm", 'N'),
    DISSOLVE("Dissolve", BlendGroup.NORMAL, "diss", 'I'),

    DARKEN("Darken", BlendGroup.DARKEN, "dark", 'K'),
    MULTIPLY("Multiply", BlendGroup.DARKEN, "mul ", 'M'),
    COLOR_BURN("Color Burn", BlendGroup.DARKEN, "idiv", 'B'),
    LINEAR_BURN("Linear Burn", BlendGroup.DARKEN, "lbrn", 'A'),
    DARKER_COLOR("Darker Color", BlendGroup.DARKEN, "dkCl", null),

    LIGHTEN("Lighten", BlendGroup.LIGHTEN, "lite", 'G'),
    SCREEN("Screen", BlendGroup.LIGHTEN, "scrn", 'S'),
    COLOR_DODGE("Color Dodge", BlendGroup.LIGHTEN, "div ", 'D'),
    LINEAR_DODGE("Linear Dodge (Add)", BlendGroup.LIGHTEN, "lddg", 'W'),
    LIGHTER_COLOR("Lighter Color", BlendGroup.LIGHTEN, "lgCl", null),

    OVERLAY("Overlay", BlendGroup.CONTRAST, "over", 'O'),
    SOFT_LIGHT("Soft Light", BlendGroup.CONTRAST, "sLit", 'F'),
    HARD_LIGHT("Hard Light", BlendGroup.CONTRAST, "hLit", 'H'),
    VIVID_LIGHT("Vivid Light", BlendGroup.CONTRAST, "vLit", 'V'),
    LINEAR_LIGHT("Linear Light", BlendGroup.CONTRAST, "lLit", 'J'),
    PIN_LIGHT("Pin Light", BlendGroup.CONTRAST, "pLit", 'Z'),
    HARD_MIX("Hard Mix", BlendGroup.CONTRAST, "hMix", 'L'),

    DIFFERENCE("Difference", BlendGroup.INVERSION, "diff", 'E'),
    EXCLUSION("Exclusion", BlendGroup.INVERSION, "smud", 'X'),
    SUBTRACT("Subtract", BlendGroup.INVERSION, "fsub", null),
    DIVIDE("Divide", BlendGroup.INVERSION, "fdiv", null),

    HUE("Hue", BlendGroup.COMPONENT, "hue ", 'U'),
    SATURATION("Saturation", BlendGroup.COMPONENT, "sat ", 'T'),
    COLOR("Color", BlendGroup.COMPONENT, "colr", 'C'),
    LUMINOSITY("Luminosity", BlendGroup.COMPONENT, "lum ", 'Y'),
    ;

    /** Pass Through only applies to groups. */
    val groupOnly: Boolean get() = this == PASS_THROUGH

    companion object {
        /** Modes offered for a layer of [kind], in menu order. */
        fun availableFor(kind: LayerKind): List<BlendMode> =
            if (kind == LayerKind.GROUP) entries else entries.filterNot { it.groupOnly }

        fun fromPsdKey(key: String): BlendMode? = entries.firstOrNull { it.psdKey == key }
    }
}
