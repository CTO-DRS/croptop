package app.croptop.core.model

import kotlinx.serialization.Serializable

/**
 * Layer ▸ Layer Style (fx): Photoshop's effects with its defaults. Sizes and distances are in document pixels,
 * angles in degrees, opacities 0..1. A disabled effect keeps its settings.
 */
@Serializable
data class LayerStyles(
    val dropShadow: StyleShadow = StyleShadow(),
    val innerShadow: StyleShadow = StyleShadow(),
    val outerGlow: StyleGlow = StyleGlow(),
    val innerGlow: StyleGlow = StyleGlow(),
    val bevel: StyleBevel = StyleBevel(),
    val satin: StyleSatin = StyleSatin(),
    val colorOverlay: StyleColorOverlay = StyleColorOverlay(),
    val gradientOverlay: StyleGradientOverlay = StyleGradientOverlay(),
    val stroke: StyleStroke = StyleStroke(),
    val patternOverlay: StylePatternOverlay = StylePatternOverlay(),
) {
    val anyEnabled: Boolean
        get() = dropShadow.enabled || innerShadow.enabled || outerGlow.enabled || innerGlow.enabled || bevel.enabled ||
            satin.enabled || colorOverlay.enabled || gradientOverlay.enabled || stroke.enabled || patternOverlay.enabled
}

/** Pattern Overlay: [patternId] from the pattern library (Edit ▸ Define Pattern); [scale] 0.01..10 (1 = 100 %). */
@Serializable
data class StylePatternOverlay(
    val enabled: Boolean = false,
    val patternId: String? = null,
    val blendMode: BlendMode = BlendMode.NORMAL,
    val opacity: Float = 1f,
    val scale: Float = 1f,
)

@Serializable
data class StyleShadow(
    val enabled: Boolean = false,
    val color: RgbaColor = RgbaColor(0f, 0f, 0f),
    val blendMode: BlendMode = BlendMode.MULTIPLY,
    val opacity: Float = 0.75f,
    val angle: Float = 120f,
    val distance: Float = 5f,
    /** Drop shadow Spread / inner shadow Choke, 0..1. */
    val spread: Float = 0f,
    val size: Float = 5f,
)

@Serializable
data class StyleGlow(
    val enabled: Boolean = false,
    val color: RgbaColor = RgbaColor(1f, 1f, 190f / 255f),
    val blendMode: BlendMode = BlendMode.SCREEN,
    val opacity: Float = 0.75f,
    /** Outer glow Spread / inner glow Choke, 0..1. */
    val spread: Float = 0f,
    val size: Float = 5f,
    /** Inner glow source: Center instead of Edge. */
    val fromCenter: Boolean = false,
)

@Serializable
enum class BevelStyle { INNER_BEVEL, OUTER_BEVEL, EMBOSS, PILLOW_EMBOSS }

@Serializable
data class StyleBevel(
    val enabled: Boolean = false,
    val style: BevelStyle = BevelStyle.INNER_BEVEL,
    /** Technique: Chisel Hard instead of Smooth. */
    val chiselHard: Boolean = false,
    /** Depth, 0.01..10 (1 = 100 %). */
    val depth: Float = 1f,
    val up: Boolean = true,
    val size: Float = 5f,
    val soften: Float = 0f,
    val angle: Float = 120f,
    val altitude: Float = 30f,
    val highlightColor: RgbaColor = RgbaColor(1f, 1f, 1f),
    val highlightMode: BlendMode = BlendMode.SCREEN,
    val highlightOpacity: Float = 0.75f,
    val shadowColor: RgbaColor = RgbaColor(0f, 0f, 0f),
    val shadowMode: BlendMode = BlendMode.MULTIPLY,
    val shadowOpacity: Float = 0.75f,
)

@Serializable
data class StyleSatin(
    val enabled: Boolean = false,
    val color: RgbaColor = RgbaColor(0f, 0f, 0f),
    val blendMode: BlendMode = BlendMode.MULTIPLY,
    val opacity: Float = 0.5f,
    val angle: Float = 19f,
    val distance: Float = 11f,
    val size: Float = 14f,
    val invert: Boolean = true,
)

@Serializable
data class StyleColorOverlay(
    val enabled: Boolean = false,
    val color: RgbaColor = RgbaColor(1f, 0f, 0f),
    val blendMode: BlendMode = BlendMode.NORMAL,
    val opacity: Float = 1f,
)

@Serializable
data class StyleGradientStop(val position: Float, val color: RgbaColor)

@Serializable
data class StyleGradientOverlay(
    val enabled: Boolean = false,
    val stops: List<StyleGradientStop> = listOf(StyleGradientStop(0f, RgbaColor(0f, 0f, 0f)), StyleGradientStop(1f, RgbaColor(1f, 1f, 1f))),
    val blendMode: BlendMode = BlendMode.NORMAL,
    val opacity: Float = 1f,
    val angle: Float = 90f,
    val scale: Float = 1f,
    val reverse: Boolean = false,
)

@Serializable
enum class StrokePosition { OUTSIDE, INSIDE, CENTER }

@Serializable
data class StyleStroke(
    val enabled: Boolean = false,
    val color: RgbaColor = RgbaColor(1f, 0f, 0f),
    val blendMode: BlendMode = BlendMode.NORMAL,
    val opacity: Float = 1f,
    val size: Float = 3f,
    val position: StrokePosition = StrokePosition.OUTSIDE,
)
