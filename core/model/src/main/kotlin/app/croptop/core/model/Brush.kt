package app.croptop.core.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlinx.serialization.Serializable

/** What drives a dynamic property (brush engine). */
@Serializable
enum class DynamicsInput(val label: String) {
    OFF("Off"),
    PRESSURE("Pen pressure"),
    TILT("Pen tilt"),
    VELOCITY("Speed"),
}

/**
 * One dynamic property: the input is mapped through [curve], then scaled between [minimum] (0–1)
 * and 1. Off means the property stays at its base value.
 */
@Serializable
data class Dynamics(val input: DynamicsInput = DynamicsInput.OFF, val curve: Curve = Curve.Identity, val minimum: Float = 0f) {
    /** Multiplier in [minimum, 1] for an input value in 0–1. */
    fun factor(value: Float): Float {
        if (input == DynamicsInput.OFF) return 1f
        val t = curve.evaluate(value.coerceIn(0f, 1f)).coerceIn(0f, 1f)
        val min = minimum.coerceIn(0f, 1f)
        return min + (1f - min) * t
    }
}

@Serializable
enum class SmoothingMode(val label: String) {
    /** Lazy rope: the brush trails the pointer by a string length. */
    ROPE("Rope"),

    /** Weighted average of recent samples. */
    AVERAGE("Average"),
}

/**
 * Brush settings. Percentages are 0–1; [size] is the diameter in pixels; [spacing] is a
 * fraction of the diameter; angles are degrees. The engine implements the same meaning;
 * [BrushDabs] is the reference used by the settings preview and tests.
 */
@Serializable
data class BrushSettings(
    val size: Float = 45f,
    val hardness: Float = 0.8f,
    val opacity: Float = 1f,
    val flow: Float = 1f,
    val spacing: Float = 0.25f,
    val angle: Float = 0f,
    val roundness: Float = 1f,
    val sizeDynamics: Dynamics = Dynamics(DynamicsInput.PRESSURE),
    val opacityDynamics: Dynamics = Dynamics(),
    val flowDynamics: Dynamics = Dynamics(),
    val sizeJitter: Float = 0f,
    val angleJitter: Float = 0f,
    /** Scatter distance as a multiple of the diameter (0–10). */
    val scatter: Float = 0f,
    val scatterBothAxes: Boolean = false,
    /** Dabs per spacing step (1–16). */
    val count: Int = 1,
    val smoothing: Float = 0.1f,
    val smoothingMode: SmoothingMode = SmoothingMode.ROPE,
    val buildUp: Boolean = false,
    val wetEdges: Boolean = false,
) {
    fun normalized(): BrushSettings = copy(
        size = size.coerceIn(MIN_SIZE, MAX_SIZE),
        hardness = hardness.coerceIn(0f, 1f),
        opacity = opacity.coerceIn(0.01f, 1f),
        flow = flow.coerceIn(0.01f, 1f),
        spacing = spacing.coerceIn(0.01f, 10f),
        angle = ((angle + 180f).mod(360f)) - 180f,
        roundness = roundness.coerceIn(0.01f, 1f),
        sizeJitter = sizeJitter.coerceIn(0f, 1f),
        angleJitter = angleJitter.coerceIn(0f, 1f),
        scatter = scatter.coerceIn(0f, 10f),
        count = count.coerceIn(1, 16),
        smoothing = smoothing.coerceIn(0f, 1f),
    )

    companion object {
        const val MIN_SIZE = 1f
        const val MAX_SIZE = 5000f
    }
}

@Serializable
enum class BrushCategory(val label: String) {
    GENERAL("General"),
    DRAWING("Drawing"),
    PAINTING("Painting"),
    TEXTURE("Texture"),
    RETOUCH("Retouch"),
    CUSTOM("My Brushes"),
}

/** A named brush. Built-in presets have negative ids and can't be edited in place. */
@Serializable
data class BrushPreset(
    val id: Long,
    val name: String,
    val category: BrushCategory,
    val settings: BrushSettings,
    val favorite: Boolean = false,
) {
    val builtIn: Boolean get() = id < 0

    companion object {
        private val Soft = Curve(listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.3f), CurvePoint(1f, 1f)))

        val BuiltIn: List<BrushPreset> = listOf(
            BrushPreset(-1, "Hard Round", BrushCategory.GENERAL, BrushSettings(size = 30f, hardness = 1f)),
            BrushPreset(-2, "Soft Round", BrushCategory.GENERAL, BrushSettings(size = 80f, hardness = 0f, flow = 0.6f)),
            BrushPreset(
                -3,
                "Airbrush",
                BrushCategory.GENERAL,
                BrushSettings(size = 120f, hardness = 0f, flow = 0.1f, buildUp = true, sizeDynamics = Dynamics()),
            ),
            BrushPreset(
                -4,
                "Pencil",
                BrushCategory.DRAWING,
                BrushSettings(
                    size = 4f,
                    hardness = 1f,
                    spacing = 0.1f,
                    opacityDynamics = Dynamics(DynamicsInput.PRESSURE, Soft, 0.2f),
                    sizeDynamics = Dynamics(),
                ),
            ),
            BrushPreset(
                -5,
                "Ink Pen",
                BrushCategory.DRAWING,
                BrushSettings(
                    size = 10f,
                    hardness = 0.95f,
                    spacing = 0.08f,
                    sizeDynamics = Dynamics(DynamicsInput.PRESSURE, Soft, 0.1f),
                    smoothing = 0.4f,
                ),
            ),
            BrushPreset(
                -6,
                "Marker",
                BrushCategory.DRAWING,
                BrushSettings(size = 24f, hardness = 0.9f, roundness = 0.35f, angle = 30f, opacity = 0.8f, sizeDynamics = Dynamics()),
            ),
            BrushPreset(
                -7,
                "Round Paint",
                BrushCategory.PAINTING,
                BrushSettings(size = 60f, hardness = 0.7f, flow = 0.5f, flowDynamics = Dynamics(DynamicsInput.PRESSURE, minimum = 0.2f)),
            ),
            BrushPreset(-8, "Wet Edges", BrushCategory.PAINTING, BrushSettings(size = 70f, hardness = 0.5f, flow = 0.6f, wetEdges = true)),
            BrushPreset(
                -9,
                "Scatter Dots",
                BrushCategory.TEXTURE,
                BrushSettings(
                    size = 18f,
                    hardness = 1f,
                    spacing = 1.2f,
                    scatter = 2f,
                    scatterBothAxes = true,
                    count = 2,
                    sizeJitter = 0.5f,
                    sizeDynamics = Dynamics(),
                ),
            ),
            BrushPreset(
                -10,
                "Chalk",
                BrushCategory.TEXTURE,
                BrushSettings(size = 40f, hardness = 0.6f, spacing = 0.35f, scatter = 0.3f, sizeJitter = 0.3f, angleJitter = 1f, roundness = 0.6f),
            ),
            BrushPreset(
                -11,
                "Dodge & Burn Soft",
                BrushCategory.RETOUCH,
                BrushSettings(size = 200f, hardness = 0f, flow = 0.08f, sizeDynamics = Dynamics()),
            ),
            BrushPreset(-12, "Retouch Hard", BrushCategory.RETOUCH, BrushSettings(size = 25f, hardness = 0.85f, sizeDynamics = Dynamics())),
        )
    }
}

/** One stylus/mouse sample; [tilt] is 0 (upright) to 1 (flat); [time] in seconds. */
data class BrushSample(
    val x: Float,
    val y: Float,
    val pressure: Float = 1f,
    val tilt: Float = 0f,
    val time: Float = 0f,
    val tiltX: Float = 0f,
    val tiltY: Float = 0f,
    val orientation: Float = 0f,
)

/** One stamped dab. [alpha] already includes opacity dynamics times flow. */
data class Dab(val x: Float, val y: Float, val diameter: Float, val angle: Float, val roundness: Float, val alpha: Float)

/**
 * Reference dab placement: dabs every `spacing × diameter`
 * along the polyline, with dynamics, jitter and scatter from a seeded generator so the same input
 * always gives the same dabs.
 */
object BrushDabs {
    /** Speed (px/s) that maps to velocity input 1. */
    const val FULL_SPEED = 3000f

    fun dabs(settings: BrushSettings, samples: List<BrushSample>, seed: Int = 1): List<Dab> {
        val s = settings.normalized()
        if (samples.isEmpty()) return emptyList()
        val rng = java.util.Random(seed.toLong())
        val out = ArrayList<Dab>()

        fun input(d: Dynamics, a: BrushSample, speed: Float) = when (d.input) {
            DynamicsInput.OFF -> 1f
            DynamicsInput.PRESSURE -> a.pressure
            DynamicsInput.TILT -> 1f - a.tilt
            DynamicsInput.VELOCITY -> 1f - (speed / FULL_SPEED).coerceIn(0f, 1f)
        }

        fun stamp(p: BrushSample, speed: Float) {
            val baseD = s.size * s.sizeDynamics.factor(input(s.sizeDynamics, p, speed))
            repeat(s.count) {
                val d = (baseD * (1f - s.sizeJitter * rng.nextFloat())).coerceAtLeast(0.5f)
                val alpha = s.opacity * s.opacityDynamics.factor(input(s.opacityDynamics, p, speed)) *
                    s.flow * s.flowDynamics.factor(input(s.flowDynamics, p, speed))
                var x = p.x
                var y = p.y
                if (s.scatter > 0f) {
                    val r = s.scatter * d * (rng.nextFloat() * 2f - 1f)
                    if (s.scatterBothAxes) {
                        val a = rng.nextFloat() * 2f * PI.toFloat()
                        x += r * cos(a)
                        y += r * sin(a)
                    } else {
                        y += r
                    }
                }
                val angle = s.angle + s.angleJitter * 180f * (rng.nextFloat() * 2f - 1f)
                out += Dab(x, y, d, angle, s.roundness, alpha.coerceIn(0f, 1f))
            }
        }

        stamp(samples.first(), 0f)
        var carry = 0f // distance travelled since the last dab
        for (i in 1 until samples.size) {
            val a = samples[i - 1]
            val b = samples[i]
            val len = hypot(b.x - a.x, b.y - a.y)
            if (len <= 0f) continue
            val dt = (b.time - a.time).takeIf { it > 0f }
            val speed = dt?.let { len / it } ?: 0f
            var t = 0f
            while (true) {
                // Spacing follows the current (dynamic) size so fast thin strokes stay continuous.
                val here = lerp(a, b, t / len)
                val step = (s.size * s.sizeDynamics.factor(input(s.sizeDynamics, here, speed)) * s.spacing).coerceAtLeast(0.5f)
                val need = step - carry
                if (t + need > len) {
                    carry += len - t
                    break
                }
                t += need
                carry = 0f
                stamp(lerp(a, b, t / len), speed)
                if (out.size > MAX_DABS) return out
            }
        }
        return out
    }

    private fun lerp(a: BrushSample, b: BrushSample, f: Float) = BrushSample(
        a.x + (b.x - a.x) * f,
        a.y + (b.y - a.y) * f,
        a.pressure + (b.pressure - a.pressure) * f,
        a.tilt + (b.tilt - a.tilt) * f,
        a.time + (b.time - a.time) * f,
        a.tiltX + (b.tiltX - a.tiltX) * f,
        a.tiltY + (b.tiltY - a.tiltY) * f,
        a.orientation + (b.orientation - a.orientation) * f,
    )

    /** The S-shaped preview stroke with pressure rising then falling, like Photoshop's preview. */
    fun previewStroke(width: Float, height: Float, points: Int = 64): List<BrushSample> = (0 until points).map { i ->
        val t = i / (points - 1f)
        BrushSample(
            x = width * (0.06f + 0.88f * t),
            y = height * (0.5f + 0.28f * sin(t * 2f * PI.toFloat())),
            pressure = sin(t * PI.toFloat()).coerceIn(0.05f, 1f),
            tilt = abs(0.5f - t),
            time = t * 0.6f,
        )
    }

    private const val MAX_DABS = 20_000
}

/** `.aebrushes` files: a JSON list of named [BrushSettings] (export/import between devices). */
object BrushFileFormat {
    private const val FORMAT = "croptop.brushes"

    @Serializable
    private data class File(val format: String = FORMAT, val version: Int = 1, val presets: List<Entry>)

    @Serializable
    private data class Entry(val name: String, val settings: BrushSettings)

    private val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(presets: List<Pair<String, BrushSettings>>): ByteArray =
        json.encodeToString(File.serializer(), File(presets = presets.map { Entry(it.first, it.second) })).toByteArray()

    /** Null when [bytes] isn't a brushes file. */
    fun decode(bytes: ByteArray): List<Pair<String, BrushSettings>>? {
        val file = runCatching { json.decodeFromString(File.serializer(), bytes.decodeToString()) }.getOrNull() ?: return null
        if (file.format != FORMAT) return null
        return file.presets.map { it.name to it.settings.normalized() }
    }
}
