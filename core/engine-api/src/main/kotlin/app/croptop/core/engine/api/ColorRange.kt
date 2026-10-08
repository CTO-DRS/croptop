package app.croptop.core.engine.api

import app.croptop.core.model.RgbaColor

/** What Select ▸ Color Range matches. */
enum class ColorRangeTarget(val label: String) {
    SAMPLED("Sampled Colors"),
    REDS("Reds"),
    YELLOWS("Yellows"),
    GREENS("Greens"),
    CYANS("Cyans"),
    BLUES("Blues"),
    MAGENTAS("Magentas"),
    HIGHLIGHTS("Highlights"),
    MIDTONES("Midtones"),
    SHADOWS("Shadows"),
    SKIN_TONES("Skin Tones"),
}

/**
 * Color Range settings. For [ColorRangeTarget.SAMPLED], pixels within [fuzziness] (0–200, like
 * Photoshop) of any [samples] color are selected; other targets ignore [samples].
 */
data class ColorRangeSpec(
    val target: ColorRangeTarget = ColorRangeTarget.SAMPLED,
    val samples: List<RgbaColor> = emptyList(),
    val fuzziness: Int = 40,
    val invert: Boolean = false,
) {
    init {
        require(fuzziness in 0..MAX_FUZZINESS) { "Fuzziness must be 0–$MAX_FUZZINESS" }
    }

    companion object {
        const val MAX_FUZZINESS = 200
    }
}

/**
 * A live Color Range: each [update] previews on [DocumentHandle.selection] (combined with the selection
 * from before the session using the session's op) without a history step. [commit] adds one
 * "Color Range" step; [cancel] restores the previous selection.
 */
interface ColorRangeSession {
    fun update(spec: ColorRangeSpec)

    fun commit(): CommandResult

    fun cancel()
}

/** Default for engines without Color Range: [commit] reports why. */
class UnsupportedColorRangeSession : ColorRangeSession {
    override fun update(spec: ColorRangeSpec) = Unit

    override fun commit(): CommandResult = unsupported("Color Range")

    override fun cancel() = Unit
}
