package app.croptop.core.model

import kotlin.math.pow
import kotlin.math.sqrt
import kotlinx.serialization.Serializable

/** A curve control point; both axes 0–1. */
@Serializable
data class CurvePoint(val x: Float, val y: Float)

/**
 * Tone curve (Curves adjustment, develop tone curve). Points are kept sorted by x with unique x;
 * evaluation uses monotone cubic (Fritsch–Carlson) interpolation so curves never overshoot.
 */
@Serializable
data class Curve(val points: List<CurvePoint> = listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f))) {
    init {
        require(points.size >= 2) { "A curve needs at least two points" }
    }

    val isIdentity: Boolean get() = points.size == 2 && points[0] == CurvePoint(0f, 0f) && points[1] == CurvePoint(1f, 1f)

    fun evaluate(x: Float): Float {
        val p = points
        if (x <= p.first().x) return p.first().y
        if (x >= p.last().x) return p.last().y
        val n = p.size
        val dx = FloatArray(n - 1) { p[it + 1].x - p[it].x }
        val slope = FloatArray(n - 1) { (p[it + 1].y - p[it].y) / dx[it] }
        val m = FloatArray(n)
        m[0] = slope[0]
        m[n - 1] = slope[n - 2]
        for (i in 1 until n - 1) m[i] = if (slope[i - 1] * slope[i] <= 0f) 0f else (slope[i - 1] + slope[i]) / 2f
        for (i in 0 until n - 1) {
            if (slope[i] == 0f) {
                m[i] = 0f
                m[i + 1] = 0f
            } else {
                val a = m[i] / slope[i]
                val b = m[i + 1] / slope[i]
                val h = a * a + b * b
                if (h > 9f) {
                    val t = 3f / sqrt(h)
                    m[i] = t * a * slope[i]
                    m[i + 1] = t * b * slope[i]
                }
            }
        }
        val i = (0 until n - 1).first { x < p[it + 1].x }
        val t = (x - p[i].x) / dx[i]
        val t2 = t * t
        val t3 = t2 * t
        val y = (2 * t3 - 3 * t2 + 1) * p[i].y + (t3 - 2 * t2 + t) * dx[i] * m[i] + (-2 * t3 + 3 * t2) * p[i + 1].y + (t3 - t2) * dx[i] * m[i + 1]
        return y.coerceIn(0f, 1f)
    }

    /** Adds a point (replacing one at nearly the same x). Returns the new curve and the point's index. */
    fun withPoint(pt: CurvePoint): Pair<Curve, Int> {
        val c = CurvePoint(pt.x.coerceIn(0f, 1f), pt.y.coerceIn(0f, 1f))
        val kept = points.filterNot { kotlin.math.abs(it.x - c.x) < MIN_GAP }
        val next = (kept + c).sortedBy { it.x }
        return Curve(next) to next.indexOf(c)
    }

    /** Moves point [index]; x is clamped between its neighbours so order is preserved. */
    fun moved(index: Int, to: CurvePoint): Curve {
        val lo = if (index == 0) 0f else points[index - 1].x + MIN_GAP
        val hi = if (index == points.lastIndex) 1f else points[index + 1].x - MIN_GAP
        val x = to.x.coerceIn(lo, hi)
        return Curve(points.toMutableList().apply { set(index, CurvePoint(x, to.y.coerceIn(0f, 1f))) })
    }

    /** Endpoints can't be removed. */
    fun without(index: Int): Curve = if (index <= 0 || index >= points.lastIndex) this else Curve(points.filterIndexed { i, _ -> i != index })

    companion object {
        const val MIN_GAP = 0.01f
        val Identity = Curve()
    }
}

/** Independent composite and RGB component curves persisted by a Curves adjustment. */
@Serializable
data class CurveChannels(
    val rgb: Curve = Curve.Identity,
    val red: Curve = Curve.Identity,
    val green: Curve = Curve.Identity,
    val blue: Curve = Curve.Identity,
)

/** Levels (input black/white/gamma → output black/white); values 0–1, gamma 0.1–9.99. */
@Serializable
data class Levels(
    val inBlack: Float = 0f,
    val inWhite: Float = 1f,
    val gamma: Float = 1f,
    val outBlack: Float = 0f,
    val outWhite: Float = 1f,
) {
    fun apply(v: Float): Float {
        val range = (inWhite - inBlack).coerceAtLeast(1e-4f)
        val n = ((v - inBlack) / range).coerceIn(0f, 1f).toDouble().pow(1.0 / gamma).toFloat()
        return outBlack + n * (outWhite - outBlack)
    }

    /** Photoshop places the gray input slider where the midtone maps: position between black and white. */
    val grayPosition: Float get() = inBlack + (inWhite - inBlack) * 0.5f.toDouble().pow(gamma.toDouble()).toFloat()

    fun withGrayPosition(pos: Float): Levels {
        val f = ((pos - inBlack) / (inWhite - inBlack).coerceAtLeast(1e-4f)).coerceIn(0.001f, 0.999f)
        return copy(gamma = (kotlin.math.ln(f) / kotlin.math.ln(0.5f)).coerceIn(0.1f, 9.99f))
    }
}

@Serializable
data class GradientStop(val position: Float, val color: RgbaColor)

/** Color gradient (gradient tool, gradient map, fills). Stops sorted by position 0–1. */
@Serializable
data class ColorGradient(val stops: List<GradientStop>) {
    init {
        require(stops.size >= 2) { "A gradient needs at least two stops" }
    }

    fun colorAt(t: Float): RgbaColor {
        val s = stops.sortedBy { it.position }
        if (t <= s.first().position) return s.first().color
        if (t >= s.last().position) return s.last().color
        val i = s.indexOfLast { it.position <= t }
        val a = s[i]
        val b = s[i + 1]
        val f = if (b.position == a.position) 0f else (t - a.position) / (b.position - a.position)
        return RgbaColor(
            a.color.r + (b.color.r - a.color.r) * f,
            a.color.g + (b.color.g - a.color.g) * f,
            a.color.b + (b.color.b - a.color.b) * f,
            a.color.a + (b.color.a - a.color.a) * f,
        )
    }

    companion object {
        val BlackToWhite = ColorGradient(listOf(GradientStop(0f, RgbaColor.Black), GradientStop(1f, RgbaColor.White)))
    }
}

/** A named gradient preset. Negative IDs are reserved for bundled, read-only presets. */
@Serializable
data class GradientPreset(val id: Long, val name: String, val gradient: ColorGradient) {
    val isBuiltIn: Boolean get() = id < 0
}

/**
 * A hue range with soft edges (Hue/Saturation per-color edits), in degrees 0–360; may wrap past 0.
 * Full effect between [innerStart]–[innerEnd], fading to none at [outerStart]/[outerEnd].
 */
@Serializable
data class HueRange(val outerStart: Float, val innerStart: Float, val innerEnd: Float, val outerEnd: Float) {
    private fun fwd(from: Float, to: Float) = ((to - from) % 360f + 360f) % 360f

    /** 0–1 weight of [hue] in this range. */
    fun weight(hue: Float): Float {
        val h = fwd(outerStart, hue)
        val a = fwd(outerStart, innerStart)
        val b = fwd(outerStart, innerEnd)
        val e = fwd(outerStart, outerEnd)
        return when {
            h > e -> 0f
            h < a -> if (a == 0f) 1f else h / a
            h <= b -> 1f
            else -> if (e == b) 1f else (e - h) / (e - b)
        }
    }

    /** Moves all four points by [degrees]. */
    fun rotate(degrees: Float): HueRange {
        fun n(v: Float) = ((v + degrees) % 360f + 360f) % 360f
        return HueRange(n(outerStart), n(innerStart), n(innerEnd), n(outerEnd))
    }

    val center: Float get() = (innerStart + fwd(innerStart, innerEnd) / 2f) % 360f
}

/** Photoshop's Hue/Saturation edit targets and their default ranges. */
enum class HueChannel(val label: String, val defaultRange: HueRange?) {
    MASTER("Master", null),
    REDS("Reds", HueRange(315f, 345f, 15f, 45f)),
    YELLOWS("Yellows", HueRange(15f, 45f, 75f, 105f)),
    GREENS("Greens", HueRange(75f, 105f, 135f, 165f)),
    CYANS("Cyans", HueRange(135f, 165f, 195f, 225f)),
    BLUES("Blues", HueRange(195f, 225f, 255f, 285f)),
    MAGENTAS("Magentas", HueRange(255f, 285f, 315f, 345f)),
}

@Serializable
data class HueShift(val hue: Float = 0f, val saturation: Float = 0f, val lightness: Float = 0f) {
    val isIdentity: Boolean get() = hue == 0f && saturation == 0f && lightness == 0f
}

@Serializable
data class HueSaturation(
    val master: HueShift = HueShift(),
    val channels: Map<HueChannel, HueShift> = emptyMap(),
    val ranges: Map<HueChannel, HueRange> = emptyMap(),
    val colorize: Boolean = false,
) {
    fun shift(ch: HueChannel): HueShift = if (ch == HueChannel.MASTER) master else channels[ch] ?: HueShift()

    fun range(ch: HueChannel): HueRange? = ranges[ch] ?: ch.defaultRange

    fun withShift(ch: HueChannel, s: HueShift) = if (ch == HueChannel.MASTER) copy(master = s) else copy(channels = channels + (ch to s))

    fun withRange(ch: HueChannel, r: HueRange) = if (ch == HueChannel.MASTER) this else copy(ranges = ranges + (ch to r))
}

enum class ToneRange(val label: String) { SHADOWS("Shadows"), MIDTONES("Midtones"), HIGHLIGHTS("Highlights") }

/** Color Balance: per tone range, −100…100 along Cyan–Red, Magenta–Green, Yellow–Blue. */
@Serializable
data class ColorBalanceShift(val cyanRed: Float = 0f, val magentaGreen: Float = 0f, val yellowBlue: Float = 0f)

@Serializable
data class ColorBalance(
    val shadows: ColorBalanceShift = ColorBalanceShift(),
    val midtones: ColorBalanceShift = ColorBalanceShift(),
    val highlights: ColorBalanceShift = ColorBalanceShift(),
    val preserveLuminosity: Boolean = true,
) {
    fun of(t: ToneRange) = when (t) {
        ToneRange.SHADOWS -> shadows
        ToneRange.MIDTONES -> midtones
        ToneRange.HIGHLIGHTS -> highlights
    }

    fun with(t: ToneRange, s: ColorBalanceShift) = when (t) {
        ToneRange.SHADOWS -> copy(shadows = s)
        ToneRange.MIDTONES -> copy(midtones = s)
        ToneRange.HIGHLIGHTS -> copy(highlights = s)
    }
}
