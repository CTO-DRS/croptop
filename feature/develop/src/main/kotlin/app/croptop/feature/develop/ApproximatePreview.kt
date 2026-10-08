package app.croptop.feature.develop

import app.croptop.core.model.DevelopProfile
import app.croptop.core.model.DevelopSettings
import app.croptop.core.model.WbPreset
import kotlin.math.pow

/**
 * Approximate develop preview as a 4×5 color matrix (row-major, Android ColorMatrix layout) so the
 * sliders give instant visual feedback before the engine's RAW pipeline is wired in.
 * Covers profile (B&W), white balance, exposure, contrast, whites/blacks, vibrance/saturation.
 * Local operators (highlights/shadows, clarity, dehaze, curves, HSL) need the real pipeline.
 */
object ApproximatePreview {
    private const val SHADOW_WARNING = 0x994A9CFF.toInt()
    private const val HIGHLIGHT_WARNING = 0x99FF3B30.toInt()

    /** Bound live clipping analysis while preserving the preview aspect ratio. */
    fun clippingAnalysisSize(width: Int, height: Int, maxDimension: Int = 512): Pair<Int, Int> {
        require(width > 0 && height > 0 && maxDimension > 0)
        val factor = minOf(1f, maxDimension.toFloat() / maxOf(width, height))
        return (width * factor).toInt().coerceAtLeast(1) to (height * factor).toInt().coerceAtLeast(1)
    }
    private fun identity() = floatArrayOf(
        1f, 0f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f, 0f,
        0f, 0f, 1f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )

    /** a·b for 4×5 matrices (treating them as 5×5 affine with an implicit [0 0 0 0 1] row). */
    fun concat(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(20)
        for (r in 0 until 4) {
            for (c in 0 until 5) {
                var v = if (c == 4) a[r * 5 + 4] else 0f
                for (k in 0 until 4) v += a[r * 5 + k] * b[k * 5 + c]
                out[r * 5 + c] = v
            }
        }
        return out
    }

    private fun scale(r: Float, g: Float, b: Float) = floatArrayOf(r, 0f, 0f, 0f, 0f, 0f, g, 0f, 0f, 0f, 0f, 0f, b, 0f, 0f, 0f, 0f, 0f, 1f, 0f)

    /** Contrast/levels around mid-grey in 0–255 space: out = (in - pivot)·k + pivot + offset. */
    private fun linearAll(k: Float, offset: Float): FloatArray {
        val t = 127.5f * (1 - k) + offset
        return floatArrayOf(k, 0f, 0f, 0f, t, 0f, k, 0f, 0f, t, 0f, 0f, k, 0f, t, 0f, 0f, 0f, 1f, 0f)
    }

    private fun saturation(s: Float): FloatArray {
        val lr = 0.2126f
        val lg = 0.7152f
        val lb = 0.0722f
        val i = 1 - s
        return floatArrayOf(
            lr * i + s, lg * i, lb * i, 0f, 0f,
            lr * i, lg * i + s, lb * i, 0f, 0f,
            lr * i, lg * i, lb * i + s, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    /** Warm/cool and tint as channel gains relative to 5500 K daylight. */
    private fun whiteBalance(settings: DevelopSettings): FloatArray {
        val wb = settings.whiteBalance
        val kelvin = when (wb.preset) {
            WbPreset.AS_SHOT, WbPreset.AUTO -> return identity()
            WbPreset.DAYLIGHT -> 5500f
            WbPreset.CLOUDY -> 6500f
            WbPreset.SHADE -> 7500f
            WbPreset.TUNGSTEN -> 2850f
            WbPreset.FLUORESCENT -> 3800f
            WbPreset.FLASH -> 5500f
            WbPreset.CUSTOM -> wb.temperature
        }
        // Higher K setting = warmer rendering (compensating a bluer light), like Lightroom's slider.
        val warm = ((kelvin - 5500f) / 5500f).coerceIn(-0.6f, 1f) * 0.35f
        val tint = (wb.tint / 150f) * 0.15f
        return scale(1f + warm, 1f - tint, 1f - warm)
    }

    fun matrix(s: DevelopSettings): FloatArray {
        val b = s.basic
        var m = identity()
        m = concat(whiteBalance(s), m)
        val exposure = 2f.pow(b.exposure)
        m = concat(scale(exposure, exposure, exposure), m)
        // Whites/blacks shift the end points; contrast steepens around the midpoint.
        val k = 1f + b.contrast / 200f + (b.whites - b.blacks) / 400f
        m = concat(linearAll(k, (b.whites + b.blacks) * 0.25f), m)
        val sat = when (s.profile) {
            DevelopProfile.MONOCHROME -> 0f
            DevelopProfile.VIVID -> 1.15f
            else -> 1f
        } * (1f + b.saturation / 100f + b.vibrance / 200f)
        m = concat(saturation(sat.coerceIn(0f, 3f)), m)
        return m
    }

    /** Applies [m] to an ARGB pixel (used for the histogram so it matches what's on screen). */
    fun apply(m: FloatArray, argb: Int): Int {
        val r = (argb shr 16 and 0xFF).toFloat()
        val g = (argb shr 8 and 0xFF).toFloat()
        val bl = (argb and 0xFF).toFloat()
        fun ch(row: Int) = (m[row * 5] * r + m[row * 5 + 1] * g + m[row * 5 + 2] * bl + m[row * 5 + 4]).toInt().coerceIn(0, 255)
        return (argb and 0xFF000000.toInt()) or (ch(0) shl 16) or (ch(1) shl 8) or ch(2)
    }

    /** Semi-transparent blue for crushed shadows and red for clipped highlights after the preview matrix. */
    fun clippingWarningColor(m: FloatArray, argb: Int): Int? {
        val adjusted = apply(m, argb)
        val r = adjusted shr 16 and 0xFF
        val g = adjusted shr 8 and 0xFF
        val b = adjusted and 0xFF
        return when {
            maxOf(r, g, b) >= 250 -> HIGHLIGHT_WARNING
            minOf(r, g, b) <= 5 -> SHADOW_WARNING
            else -> null
        }
    }
}
