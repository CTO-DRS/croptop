package app.croptop.core.model

import kotlinx.serialization.Serializable

/** The device a soft proof simulates. [PRESS_CMYK] is the built-in generic process press (a stand-in until a real profile can be chosen). */
@Serializable
enum class ProofTarget(val label: String) {
    PRESS_CMYK("Generic CMYK press"),
    SRGB("sRGB"),
    ADOBE_RGB("Adobe RGB (1998)"),
    DISPLAY_P3("Display P3"),
}

@Serializable
enum class ProofIntent(val label: String) {
    PERCEPTUAL("Perceptual"),
    RELATIVE_COLORIMETRIC("Relative colorimetric"),
    SATURATION("Saturation"),
    ABSOLUTE_COLORIMETRIC("Absolute colorimetric"),
}

/**
 * View ▸ Proof Colors (Ctrl+Y) and Gamut Warning (Ctrl+Shift+Y): the viewport shows the picture as [target] would reproduce it, and
 * with [gamutWarning] paints colours it cannot reproduce in grey. Only the view changes; the document and its exports do not.
 */
@Serializable
data class SoftProofSettings(
    val target: ProofTarget = ProofTarget.PRESS_CMYK,
    /** Total ink limit of the generic press, 200–400 %. */
    val inkLimitPercent: Float = 300f,
    val intent: ProofIntent = ProofIntent.RELATIVE_COLORIMETRIC,
    val blackPointCompensation: Boolean = true,
    val gamutWarning: Boolean = false,
) {
    init {
        require(inkLimitPercent.isFinite() && inkLimitPercent in INK_MIN..INK_MAX) { "ink limit is $INK_MIN–$INK_MAX %" }
    }

    companion object {
        const val INK_MIN = 200f
        const val INK_MAX = 400f
    }
}
