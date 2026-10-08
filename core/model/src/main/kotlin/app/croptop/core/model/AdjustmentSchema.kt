package app.croptop.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Self-describing adjustment/filter parameters. The engine
 * publishes one [AdjustmentSpec] per adjustment; the Properties panel is generated from it, so a
 * new adjustment needs no UI code. [AdjustmentCatalog] is the v1 set the engine implements.
 */
@Serializable
sealed interface ParamSpec {
    val key: String
    val label: String

    /** Shown only while another parameter has a given value (e.g. Tint color when Tint is on). */
    val showIf: ShowIf?
}

@Serializable
data class ShowIf(val key: String, val equals: ParamValue)

/** Color track behind a number slider. */
@Serializable
enum class Track { PLAIN, HUE, CYAN_RED, MAGENTA_GREEN, YELLOW_BLUE, TEMPERATURE }

@Serializable
@SerialName("number")
data class NumberParam(
    override val key: String,
    override val label: String,
    val min: Float,
    val max: Float,
    val default: Float,
    val step: Float = 1f,
    val decimals: Int = 0,
    val unit: String? = null,
    val track: Track = Track.PLAIN,
    override val showIf: ShowIf? = null,
) : ParamSpec

@Serializable
@SerialName("choice")
data class ChoiceParam(
    override val key: String,
    override val label: String,
    val options: List<Option>,
    val default: String,
    override val showIf: ShowIf? = null,
    /** Where the choices come from: the fixed [options], or those plus the document's pixel layers (`layer:<id>`). */
    val source: Source = Source.STATIC,
) : ParamSpec {
    @Serializable
    data class Option(val value: String, val label: String)

    @Serializable
    enum class Source { STATIC, PIXEL_LAYERS }

    companion object {
        const val LAYER_PREFIX = "layer:"
    }
}

@Serializable
@SerialName("toggle")
data class ToggleParam(
    override val key: String,
    override val label: String,
    val default: Boolean = false,
    override val showIf: ShowIf? = null,
) : ParamSpec

@Serializable
@SerialName("color")
data class ColorParam(override val key: String, override val label: String, val default: RgbaColor, override val showIf: ShowIf? = null) : ParamSpec

/** Parameters with a dedicated editor (curves, levels, hue/sat ranges, color balance, gradient). */
@Serializable
@SerialName("editor")
data class EditorParam(
    override val key: String,
    override val label: String,
    val editor: Editor,
    override val showIf: ShowIf? = null,
) : ParamSpec {
    @Serializable
    enum class Editor { CURVE, LEVELS, HUE_SATURATION, COLOR_BALANCE, GRADIENT }
}

@Serializable
sealed interface ParamValue {
    @Serializable
    @SerialName("num")
    data class Num(val v: Float) : ParamValue

    @Serializable
    @SerialName("choice")
    data class Choice(val v: String) : ParamValue

    @Serializable
    @SerialName("bool")
    data class Bool(val v: Boolean) : ParamValue

    @Serializable
    @SerialName("color")
    data class Color(val v: RgbaColor) : ParamValue

    @Serializable
    @SerialName("curve")
    data class CurveValue(val v: Curve) : ParamValue

    /** New independent channel curves. Legacy `curve` values remain decodable via [CurveValue]. */
    @Serializable
    @SerialName("curveChannels")
    data class CurveChannelsValue(val v: CurveChannels) : ParamValue

    @Serializable
    @SerialName("levels")
    data class LevelsValue(val v: Levels) : ParamValue

    @Serializable
    @SerialName("hueSat")
    data class HueSatValue(val v: HueSaturation) : ParamValue

    @Serializable
    @SerialName("balance")
    data class BalanceValue(val v: ColorBalance) : ParamValue

    @Serializable
    @SerialName("gradient")
    data class GradientValue(val v: ColorGradient) : ParamValue
}

@Serializable
data class AdjustmentSpec(
    val id: String,
    val name: String,
    val params: List<ParamSpec>,
    /** Named starting points ("Increase Contrast"); values not listed keep their defaults. */
    val presets: List<AdjustmentPreset> = emptyList(),
) {
    fun defaults(): Map<String, ParamValue> = params.associate { it.key to it.defaultValue() }

    /** Fills missing keys with defaults, drops unknown keys and clamps numbers into range. */
    fun normalize(values: Map<String, ParamValue>): Map<String, ParamValue> = params.associate { p ->
        val v = values[p.key]
        p.key to when {
            p is NumberParam && v is ParamValue.Num -> ParamValue.Num(v.v.coerceIn(p.min, p.max))

            p is ChoiceParam -> if (v is ParamValue.Choice && (p.options.any { it.value == v.v } || (p.source == ChoiceParam.Source.PIXEL_LAYERS && v.v.startsWith(ChoiceParam.LAYER_PREFIX)))) v else p.defaultValue()

            p is EditorParam && p.editor == EditorParam.Editor.CURVE && v is ParamValue.CurveValue ->
                ParamValue.CurveChannelsValue(CurveChannels(rgb = v.v))

            p is EditorParam && p.editor == EditorParam.Editor.CURVE && v is ParamValue.CurveChannelsValue -> v

            v != null && v::class == p.defaultValue()::class -> v

            else -> p.defaultValue()
        }
    }

    /** Parameters visible for [values] (their [ShowIf] condition holds). */
    fun visible(values: Map<String, ParamValue>): List<ParamSpec> = params.filter { p -> p.showIf?.let { values[it.key] == it.equals } ?: true }
}

@Serializable
data class AdjustmentPreset(val name: String, val values: Map<String, ParamValue>)

fun ParamSpec.defaultValue(): ParamValue = when (this) {
    is NumberParam -> ParamValue.Num(default)

    is ChoiceParam -> ParamValue.Choice(default)

    is ToggleParam -> ParamValue.Bool(default)

    is ColorParam -> ParamValue.Color(default)

    is EditorParam -> when (editor) {
        EditorParam.Editor.CURVE -> ParamValue.CurveChannelsValue(CurveChannels())
        EditorParam.Editor.LEVELS -> ParamValue.LevelsValue(Levels())
        EditorParam.Editor.HUE_SATURATION -> ParamValue.HueSatValue(HueSaturation())
        EditorParam.Editor.COLOR_BALANCE -> ParamValue.BalanceValue(ColorBalance())
        EditorParam.Editor.GRADIENT -> ParamValue.GradientValue(ColorGradient.BlackToWhite)
    }
}

/** The v1 adjustments in [AdjustmentSpec] form. */
object AdjustmentCatalog {
    private fun n(
        key: String,
        label: String,
        min: Float,
        max: Float,
        default: Float = 0f,
        unit: String? = null,
        decimals: Int = 0,
        step: Float = 1f,
        track: Track = Track.PLAIN,
    ) =
        NumberParam(key, label, min, max, default, step, decimals, unit, track)

    private fun ch(key: String, label: String, vararg options: Pair<String, String>) =
        ChoiceParam(key, label, options.map { ChoiceParam.Option(it.first, it.second) }, options.first().first)

    val All: List<AdjustmentSpec> = listOf(
        AdjustmentSpec(
            "brightnessContrast",
            "Brightness/Contrast",
            listOf(n("brightness", "Brightness", -150f, 150f), n("contrast", "Contrast", -50f, 100f), ToggleParam("legacy", "Use Legacy")),
            listOf(AdjustmentPreset("Increase Contrast", mapOf("contrast" to ParamValue.Num(30f)))),
        ),
        AdjustmentSpec("levels", "Levels", listOf(EditorParam("levels", "Levels", EditorParam.Editor.LEVELS))),
        AdjustmentSpec("curves", "Curves", listOf(EditorParam("curve", "Curve", EditorParam.Editor.CURVE))),
        AdjustmentSpec(
            "exposure",
            "Exposure",
            listOf(
                n("exposure", "Exposure", -20f, 20f, unit = "EV", decimals = 2, step = 0.01f),
                n("offset", "Offset", -0.5f, 0.5f, decimals = 4, step = 0.0001f),
                n("gamma", "Gamma Correction", 0.01f, 9.99f, 1f, decimals = 2, step = 0.01f),
            ),
        ),
        AdjustmentSpec("vibrance", "Vibrance", listOf(n("vibrance", "Vibrance", -100f, 100f), n("saturation", "Saturation", -100f, 100f))),
        AdjustmentSpec(
            "hueSaturation",
            "Hue/Saturation",
            listOf(EditorParam("hueSat", "Hue/Saturation", EditorParam.Editor.HUE_SATURATION)),
        ),
        AdjustmentSpec("colorBalance", "Color Balance", listOf(EditorParam("balance", "Color Balance", EditorParam.Editor.COLOR_BALANCE))),
        AdjustmentSpec(
            "blackWhite",
            "Black & White",
            listOf(
                n("reds", "Reds", -200f, 300f, 40f, unit = "%"),
                n("yellows", "Yellows", -200f, 300f, 60f, unit = "%"),
                n("greens", "Greens", -200f, 300f, 40f, unit = "%"),
                n("cyans", "Cyans", -200f, 300f, 60f, unit = "%"),
                n("blues", "Blues", -200f, 300f, 20f, unit = "%"),
                n("magentas", "Magentas", -200f, 300f, 80f, unit = "%"),
                ToggleParam("tint", "Tint"),
                ColorParam("tintColor", "Tint color", RgbaColor(0.88f, 0.76f, 0.58f), showIf = ShowIf("tint", ParamValue.Bool(true))),
            ),
            listOf(
                AdjustmentPreset(
                    "High Contrast Red Filter",
                    mapOf(
                        "reds" to ParamValue.Num(120f),
                        "yellows" to ParamValue.Num(120f),
                        "blues" to ParamValue.Num(-10f),
                    ),
                ),
            ),
        ),
        AdjustmentSpec(
            "photoFilter",
            "Photo Filter",
            listOf(
                ch(
                    "filter",
                    "Filter",
                    "warming85" to "Warming Filter (85)",
                    "cooling80" to "Cooling Filter (80)",
                    "sepia" to "Sepia",
                    "custom" to "Custom color",
                ),
                ColorParam("color", "Color", RgbaColor(0.93f, 0.54f, 0.1f), showIf = ShowIf("filter", ParamValue.Choice("custom"))),
                n("density", "Density", 1f, 100f, 25f, unit = "%"),
                ToggleParam("preserveLuminosity", "Preserve Luminosity", default = true),
            ),
        ),
        AdjustmentSpec("invert", "Invert", emptyList()),
        AdjustmentSpec("posterize", "Posterize", listOf(n("levels", "Levels", 2f, 255f, 4f))),
        AdjustmentSpec("threshold", "Threshold", listOf(n("level", "Threshold Level", 1f, 255f, 128f))),
        AdjustmentSpec(
            "channelMixer",
            "Channel Mixer",
            listOf(
                ch("output", "Output", "red" to "Red", "green" to "Green", "blue" to "Blue"),
                n("red", "Red", -200f, 200f, 100f, unit = "%", track = Track.CYAN_RED),
                n("green", "Green", -200f, 200f, 0f, unit = "%", track = Track.MAGENTA_GREEN),
                n("blue", "Blue", -200f, 200f, 0f, unit = "%", track = Track.YELLOW_BLUE),
                n("constant", "Constant", -200f, 200f, 0f, unit = "%"),
                ToggleParam("monochrome", "Monochrome"),
            ),
        ),
        AdjustmentSpec(
            "gradientMap",
            "Gradient Map",
            listOf(
                EditorParam("gradient", "Gradient", EditorParam.Editor.GRADIENT),
                ToggleParam("reverse", "Reverse"),
                ToggleParam("dither", "Dither"),
            ),
        ),
        AdjustmentSpec(
            "selectiveColor",
            "Selective Color",
            listOf(
                ch(
                    "colors", "Colors", "reds" to "Reds", "yellows" to "Yellows", "greens" to "Greens", "cyans" to "Cyans", "blues" to "Blues",
                    "magentas" to "Magentas", "whites" to "Whites", "neutrals" to "Neutrals", "blacks" to "Blacks",
                ),
                n("cyan", "Cyan", -100f, 100f, unit = "%"),
                n("magenta", "Magenta", -100f, 100f, unit = "%"),
                n("yellow", "Yellow", -100f, 100f, unit = "%"),
                n("black", "Black", -100f, 100f, unit = "%"),
                ch("method", "Method", "relative" to "Relative", "absolute" to "Absolute"),
            ),
        ),
        AdjustmentSpec(
            "shadowsHighlights",
            "Shadows/Highlights",
            listOf(n("shadows", "Shadows", 0f, 100f, 35f, unit = "%"), n("highlights", "Highlights", 0f, 100f, 0f, unit = "%")),
        ),
    )

    operator fun get(id: String): AdjustmentSpec? = All.firstOrNull { it.id == id }
}
