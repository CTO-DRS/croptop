package app.croptop.core.model

import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Hue 0–360, saturation/value 0–1 (Photoshop "HSB"). */
data class Hsv(val h: Float, val s: Float, val v: Float)

/** CIE L*a*b* (D65), L 0–100. */
data class Lab(val l: Float, val a: Float, val b: Float)

/** Color conversions for pickers and readouts (sRGB-encoded UI colors; document color is engine-managed). */
object ColorMath {
    fun toHsv(c: RgbaColor): Hsv {
        val mx = max(c.r, max(c.g, c.b))
        val mn = min(c.r, min(c.g, c.b))
        val d = mx - mn
        val h = when {
            d == 0f -> 0f
            mx == c.r -> 60f * (((c.g - c.b) / d) % 6f)
            mx == c.g -> 60f * (((c.b - c.r) / d) + 2f)
            else -> 60f * (((c.r - c.g) / d) + 4f)
        }.let { if (it < 0f) it + 360f else it }
        return Hsv(h, if (mx == 0f) 0f else d / mx, mx)
    }

    fun fromHsv(hsv: Hsv, alpha: Float = 1f): RgbaColor {
        val h = ((hsv.h % 360f) + 360f) % 360f
        val c = hsv.v * hsv.s
        val x = c * (1 - abs((h / 60f) % 2f - 1))
        val m = hsv.v - c
        val (r, g, b) = when {
            h < 60 -> Triple(c, x, 0f)
            h < 120 -> Triple(x, c, 0f)
            h < 180 -> Triple(0f, c, x)
            h < 240 -> Triple(0f, x, c)
            h < 300 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return RgbaColor(r + m, g + m, b + m, alpha)
    }

    private fun linear(c: Float) = if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()

    private fun f(t: Float) = if (t > 0.008856f) cbrt(t) else (7.787f * t + 16f / 116f)

    fun toLab(c: RgbaColor): Lab {
        val r = linear(c.r)
        val g = linear(c.g)
        val b = linear(c.b)
        // sRGB → XYZ (D65), normalized by the white point.
        val x = (0.4124f * r + 0.3576f * g + 0.1805f * b) / 0.95047f
        val y = 0.2126f * r + 0.7152f * g + 0.0722f * b
        val z = (0.0193f * r + 0.1192f * g + 0.9505f * b) / 1.08883f
        val fy = f(y)
        return Lab(116f * fy - 16f, 500f * (f(x) - fy), 200f * (fy - f(z)))
    }

    private fun gamma(c: Float) = (if (c <= 0.0031308f) 12.92f * c else 1.055f * c.toDouble().pow(1 / 2.4).toFloat() - 0.055f).coerceIn(0f, 1f)

    private fun fInv(t: Float) = if (t * t * t > 0.008856f) t * t * t else (t - 16f / 116f) / 7.787f

    /** CIE Lab (D65) → sRGB, clipped to gamut. */
    fun fromLab(lab: Lab, alpha: Float = 1f): RgbaColor {
        val fy = (lab.l + 16f) / 116f
        val x = fInv(fy + lab.a / 500f) * 0.95047f
        val y = fInv(fy)
        val z = fInv(fy - lab.b / 200f) * 1.08883f
        val r = 3.2406f * x - 1.5372f * y - 0.4986f * z
        val g = -0.9689f * x + 1.8758f * y + 0.0415f * z
        val b = 0.0557f * x - 0.2040f * y + 1.0570f * z
        return RgbaColor(gamma(r), gamma(g), gamma(b), alpha)
    }

    enum class Harmony(val label: String, val offsets: List<Float>) {
        COMPLEMENTARY("Complementary", listOf(180f)),
        ANALOGOUS("Analogous", listOf(-30f, 30f)),
        TRIADIC("Triadic", listOf(120f, 240f)),
        SPLIT("Split complementary", listOf(150f, 210f)),
    }

    fun harmony(base: RgbaColor, harmony: Harmony): List<RgbaColor> {
        val hsv = toHsv(base)
        return harmony.offsets.map { fromHsv(hsv.copy(h = hsv.h + it), base.a) }
    }
}
