package app.croptop.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class FillKind { SOLID, GRADIENT, PATTERN }

/**
 * Layer ▸ New Fill Layer (Solid Color, Gradient): settings re-rendered across the whole canvas, so the fill
 * follows canvas size changes. [angle] is in degrees (counter-clockwise, 90 = bottom to top), [scale] 0.1..1.5.
 */
@Serializable
data class FillLayerInfo(
    val kind: FillKind = FillKind.SOLID,
    val color: RgbaColor = RgbaColor.Black,
    val gradient: ColorGradient = ColorGradient(listOf(GradientStop(0f, RgbaColor.Black), GradientStop(1f, RgbaColor.White))),
    val shape: GradientShape = GradientShape.LINEAR,
    val angle: Float = 90f,
    val scale: Float = 1f,
    val reverse: Boolean = false,
    /** [FillKind.PATTERN]: the library pattern ([scale] applies to it too). */
    val patternId: String? = null,
)
