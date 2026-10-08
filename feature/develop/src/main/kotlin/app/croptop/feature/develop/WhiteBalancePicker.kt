package app.croptop.feature.develop

import app.croptop.core.model.WbPreset
import app.croptop.core.model.WhiteBalance
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * White-balance eyedropper: finds the temperature and tint that make a clicked pixel neutral. Mirrors the native
 * `whiteBalanceGains` (develop_pipeline.cpp) so the solved values reproduce the same gains the engine applies.
 */
object WhiteBalancePicker {
    private const val MIN_K = 2000.0
    private const val MAX_K = 12000.0

    private fun linearRgb(x: Double, y: Double): DoubleArray {
        val bigX = x / y
        val bigZ = (1.0 - x - y) / y
        return doubleArrayOf(3.2406 * bigX - 1.5372 - 0.4986 * bigZ, -0.9689 * bigX + 1.8758 + 0.0415 * bigZ, 0.0557 * bigX - 0.2040 + 1.0570 * bigZ)
    }

    /** Linear-light channel gains (red, green, blue) the engine applies for [kelvin] and [tint]. */
    fun gains(kelvin: Double, tint: Double): DoubleArray {
        val t = kelvin.coerceIn(2000.0, 50000.0)
        val x = if (t <= 4000.0) {
            -0.2661239e9 / (t * t * t) - 0.2343580e6 / (t * t) + 0.8776956e3 / t + 0.179910
        } else {
            -3.0258469e9 / (t * t * t) + 2.1070379e6 / (t * t) + 0.2226347e3 / t + 0.240390
        }
        val y = when {
            t <= 2222.0 -> -1.1063814 * x * x * x - 1.34811020 * x * x + 2.18555832 * x - 0.20219683
            t <= 4000.0 -> -0.9549476 * x * x * x - 1.37418593 * x * x + 2.09137015 * x - 0.16748867
            else -> 3.0817580 * x * x * x - 5.87338670 * x * x + 3.75112997 * x - 0.37001483
        }
        val dst = linearRgb(x, y)
        val src = linearRgb(0.3324, 0.3474)
        val g = max(1e-6, dst[1] / src[1])
        return doubleArrayOf(
            ((max(0.02, dst[0]) / src[0]) / g).coerceIn(0.125, 8.0),
            2.0.pow(-tint / 300.0).coerceIn(0.5, 2.0),
            ((max(0.02, dst[2]) / src[2]) / g).coerceIn(0.125, 8.0),
        )
    }

    private fun toLinear(c: Int): Double {
        val v = c / 255.0
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun currentKelvin(wb: WhiteBalance) = when (wb.preset) {
        WbPreset.CUSTOM -> wb.temperature.toDouble()
        WbPreset.DAYLIGHT -> 5500.0
        WbPreset.CLOUDY -> 6500.0
        WbPreset.SHADE -> 7500.0
        WbPreset.TUNGSTEN -> 2850.0
        WbPreset.FLUORESCENT -> 4000.0
        WbPreset.FLASH -> 6000.0
        else -> 5500.0
    }

    /**
     * The white balance that neutralizes [argb], a pixel of the preview rendered with [current]. Returns null for a pixel too dark
     * or too saturated to be a neutral reference.
     */
    fun solve(argb: Int, current: WhiteBalance): WhiteBalance? {
        val r = toLinear((argb shr 16) and 0xff)
        val g = toLinear((argb shr 8) and 0xff)
        val b = toLinear(argb and 0xff)
        if (min(r, min(g, b)) < 0.004 || max(r, max(g, b)) > 0.98) return null
        val k0 = currentKelvin(current)
        val tint0 = current.tint.toDouble()
        // The engine applies no balance at all for the as-shot/auto presets at 5500 K with zero tint.
        val inactive = (current.preset == WbPreset.AS_SHOT || current.preset == WbPreset.AUTO || k0 == 5500.0) && tint0 == 0.0
        val g0 = if (inactive) doubleArrayOf(1.0, 1.0, 1.0) else gains(k0, tint0)
        // Undo the balance already in the preview, then find the gains that equalize the three channels.
        val rs = r / g0[0]
        val gs = g / g0[1]
        val bs = b / g0[2]
        // Neutral needs rs * gr == bs * gb: solve the red/blue gain ratio over temperature (it falls as kelvin rises).
        val wanted = bs / rs
        var lo = MIN_K
        var hi = MAX_K
        repeat(40) {
            val mid = (lo + hi) / 2
            val ratio = gains(mid, 0.0).let { it[0] / it[2] }
            if (ratio > wanted) lo = mid else hi = mid
        }
        val kelvin = ((lo + hi) / 2).coerceIn(MIN_K, MAX_K)
        val k = gains(kelvin, 0.0)
        val greenGain = rs * k[0] / gs
        val tint = (-300.0 * ln(greenGain) / ln(2.0)).coerceIn(-150.0, 150.0)
        if (abs(tint) > 150.0) return null
        return WhiteBalance(WbPreset.CUSTOM, kelvin.toFloat(), tint.toFloat())
    }
}
