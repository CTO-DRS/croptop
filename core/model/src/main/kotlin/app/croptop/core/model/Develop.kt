package app.croptop.core.model

import kotlinx.serialization.Serializable

/**
 * RAW develop settings. Versioned, fully serializable (sidecar/XMP export, presets,
 * copy/paste, sync). Every field defaults to "no change", so [isDefault] reflects an untouched photo.
 * Proposed for — the pipeline consumes it; fields may be adjusted with a version bump.
 */
@Serializable
data class DevelopSettings(
    val version: Int = VERSION,
    val profile: DevelopProfile = DevelopProfile.NEUTRAL,
    val whiteBalance: WhiteBalance = WhiteBalance(),
    val basic: BasicTone = BasicTone(),
    val toneCurve: Curve = Curve.Identity,
    val mixer: ColorMixer = ColorMixer(),
    val grading: ColorGrading = ColorGrading(),
    /** Camera-calibration primaries and shadow tint. */
    val calibration: Calibration = Calibration(),
    val detail: Detail = Detail(),
    val optics: Optics = Optics(),
    val effects: Effects = Effects(),
    val crop: CropSettings = CropSettings(),
    /** Automatic level/perspective correction applied before the crop. */
    val upright: Upright = Upright.OFF,
    val uprightManual: UprightManual = UprightManual(),
    val uprightGuides: List<UprightGuide> = emptyList(),
    /** Spot repairs on the uncropped frame, applied in order before the crop. */
    val spots: List<Spot> = emptyList(),
    /** Local adjustments (Lightroom-style masks), applied in order on top of the global result. */
    val masks: List<LocalMask> = emptyList(),
) {
    val isDefault: Boolean get() = copy(version = VERSION) == DevelopSettings()

    companion object {
        const val VERSION = 1
    }
}

@Serializable
enum class DevelopProfile(
    val label: String,
) {
    NEUTRAL("Neutral"),
    VIVID("Vivid"),
    PORTRAIT("Portrait"),
    LANDSCAPE("Landscape"),
    MONOCHROME("Monochrome"),
}

@Serializable
enum class WbPreset(
    val label: String,
) {
    AS_SHOT(
        "As shot",
    ),
    AUTO(
        "Auto",
    ),
    DAYLIGHT("Daylight"),
    CLOUDY("Cloudy"),
    SHADE("Shade"),
    TUNGSTEN("Tungsten"),
    FLUORESCENT("Fluorescent"),
    FLASH("Flash"),
    CUSTOM("Custom"),
}

/** [temperature] in Kelvin (2000–50000), [tint] −150…+150 (green … magenta). */
@Serializable
data class WhiteBalance(val preset: WbPreset = WbPreset.AS_SHOT, val temperature: Float = 5500f, val tint: Float = 0f)

/** Basic panel. Exposure in EV (−5…+5); all others −100…+100. */
@Serializable
data class BasicTone(
    val exposure: Float = 0f,
    val contrast: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val whites: Float = 0f,
    val blacks: Float = 0f,
    val texture: Float = 0f,
    val clarity: Float = 0f,
    val dehaze: Float = 0f,
    val vibrance: Float = 0f,
    val saturation: Float = 0f,
)

/** The 8 color-mixer ranges. */
@Serializable
enum class ColorRange(val label: String, val argb: Long) {
    RED("Red", 0xFFE5484D),
    ORANGE("Orange", 0xFFF08C2E),
    YELLOW("Yellow", 0xFFF5C518),
    GREEN("Green", 0xFF30A46C),
    AQUA("Aqua", 0xFF12A4B4),
    BLUE("Blue", 0xFF3E7BFA),
    PURPLE("Purple", 0xFF8E4EC6),
    MAGENTA("Magenta", 0xFFD6409F),
}

/** Hue/saturation/luminance shift, each −100…+100. */
@Serializable
data class HslShift(val hue: Float = 0f, val saturation: Float = 0f, val luminance: Float = 0f)

@Serializable
data class ColorMixer(val ranges: Map<ColorRange, HslShift> = emptyMap()) {
    fun of(r: ColorRange): HslShift = ranges[r] ?: HslShift()

    fun with(r: ColorRange, s: HslShift): ColorMixer = copy(ranges = (ranges + (r to s)).filterValues { it != HslShift() })
}

/** A grading wheel: hue 0–360, saturation 0–100, luminance −100…+100. */
@Serializable
data class GradeWheel(val hue: Float = 0f, val saturation: Float = 0f, val luminance: Float = 0f)

@Serializable
data class ColorGrading(
    val shadows: GradeWheel = GradeWheel(),
    val midtones: GradeWheel = GradeWheel(),
    val highlights: GradeWheel = GradeWheel(),
    val global: GradeWheel = GradeWheel(),
    /** 0…100 */
    val blending: Float = 50f,
    /** −100…+100 */
    val balance: Float = 0f,
)

@Serializable
data class Detail(
    /** 0…150 */
    val sharpenAmount: Float = 40f,
    /** 0.5…3.0 px */
    val sharpenRadius: Float = 1f,
    /** 0…100 */
    val sharpenDetail: Float = 25f,
    /** 0…100 */
    val sharpenMasking: Float = 0f,
    val luminanceNoise: Float = 0f,
    val colorNoise: Float = 25f,
)

@Serializable
data class Optics(
    val lensProfile: Boolean = true,
    val removeChromaticAberration: Boolean = true,
    val vignetting: Float = 0f,
    val distortion: Float = 0f,
    /** Manual defringe, 0–20 each: desaturates purple and green fringes next to high-contrast edges. */
    val defringePurple: Float = 0f,
    val defringeGreen: Float = 0f,
)

@Serializable
data class Effects(val vignetteAmount: Float = 0f, val vignetteMidpoint: Float = 50f, val grainAmount: Float = 0f, val grainSize: Float = 25f)

/** Normalized crop rectangle (0–1) + straighten angle in degrees. */
@Serializable
data class CropSettings(val left: Float = 0f, val top: Float = 0f, val right: Float = 1f, val bottom: Float = 1f, val angle: Float = 0f)

/** Automatic level and perspective correction; the crop frame is relative to its result. */
@Serializable
enum class Upright(val label: String) {
    OFF("Off"),
    LEVEL("Level"),
    VERTICAL("Vertical"),
    FULL("Full"),
    AUTO("Auto"),
    GUIDED("Guided"),
    MANUAL("Manual"),
}

/** A line drawn for Guided upright that should become vertical or horizontal; fractions of the frame (0–1). */
@Serializable
data class UprightGuide(val x1: Float, val y1: Float, val x2: Float, val y2: Float) {
    init {
        require(listOf(x1, y1, x2, y2).all { it in 0f..1f }) { "upright guide must be inside the frame" }
    }

    companion object {
        const val MAX = 4
    }
}

/** Manual upright: tilt about x and y (−100…100), rotation in degrees, aspect (−100…100), scale %, offsets (−100…100). */
@Serializable
data class UprightManual(
    val vertical: Float = 0f,
    val horizontal: Float = 0f,
    val rotate: Float = 0f,
    val aspect: Float = 0f,
    val scale: Float = 100f,
    val xOffset: Float = 0f,
    val yOffset: Float = 0f,
) {
    val isIdentity: Boolean get() = this == UprightManual()
}

/**
 * One spot repair: the circle at ([x], [y]) is replaced from the circle at ([sourceX], [sourceY]). Positions are fractions of the
 * frame, [radius] a fraction of the longer edge; [heal] also matches the copied colour to its surroundings.
 */
@Serializable
data class Spot(
    val x: Float,
    val y: Float,
    val radius: Float = 0.02f,
    val sourceX: Float,
    val sourceY: Float,
    val feather: Float = 0.5f,
    val opacity: Float = 1f,
    val heal: Boolean = true,
) {
    init {
        require(x in 0f..1f && y in 0f..1f && sourceX in 0f..1f && sourceY in 0f..1f) { "spot positions must be inside the frame" }
        require(radius in MIN_RADIUS..MAX_RADIUS && feather in 0f..1f && opacity in 0f..1f) { "spot size, feather and opacity are outside their ranges" }
    }

    companion object {
        const val MAX_SPOTS = 32
        const val MIN_RADIUS = 0.001f
        const val MAX_RADIUS = 0.25f
    }
}

/** Primary hue/saturation shifts (−100…100 each) and the shadow tint (−100 green … +100 magenta). */
@Serializable
data class Calibration(
    val shadowTint: Float = 0f,
    val redHue: Float = 0f,
    val redSaturation: Float = 0f,
    val greenHue: Float = 0f,
    val greenSaturation: Float = 0f,
    val blueHue: Float = 0f,
    val blueSaturation: Float = 0f,
) {
    val isIdentity: Boolean get() = this == Calibration()

    fun toList() = listOf(shadowTint, redHue, redSaturation, greenHue, greenSaturation, blueHue, blueSaturation)

    companion object {
        const val SIZE = 7

        fun of(v: List<Float>) = Calibration(v[0], v[1], v[2], v[3], v[4], v[5], v[6])
    }
}

@Serializable
enum class MaskKind(val label: String) {
    LINEAR("Linear gradient"),
    RADIAL("Radial gradient"),
    BRUSH("Brush"),
    LUMINANCE("Luminance range"),
    COLOR("Color range"),
    /** A segmentation model's result (subject, sky, background); see [AiMaskBitmap]. Always the last entry: native code uses the ordinal. */
    AI("AI selection"),
    ;

    companion object {
        /** The kinds the panel can create by hand; AI masks come from a model's result. */
        val MANUAL: List<MaskKind> = entries.filter { it != AI }
    }
}

/** How a component joins the mask built so far (the first component always starts it). */
@Serializable
enum class MaskOp(val label: String) { ADD("Add"), SUBTRACT("Subtract"), INTERSECT("Intersect") }

/** One brush stroke over a mask, in normalized image coordinates; [size] is a fraction of the longer edge. */
@Serializable
data class MaskStroke(
    val points: List<MaskPoint>,
    val size: Float = 0.05f,
    val feather: Float = 0.5f,
    val flow: Float = 1f,
    val erase: Boolean = false,
) {
    init {
        require(size in 0.0005f..1f && feather in 0f..1f && flow in 0f..1f) { "brush stroke is outside its ranges" }
        require(points.size <= MAX_POINTS) { "a brush stroke has at most $MAX_POINTS points" }
    }

    companion object {
        const val MAX_POINTS = 8192
    }
}

@Serializable
data class MaskPoint(val x: Float, val y: Float)

/**
 * One building block of a local mask; [params] (8 numbers) by kind: LINEAR x1 y1 x2 y2 (full strength at the start point);
 * RADIAL centre x, y, radius x, y (fractions of width/height), rotation°, feather 0–1, invert 0/1; LUMINANCE low, high, smoothness;
 * COLOR red, green, blue (display-referred 0–1), tolerance, feather. BRUSH uses [strokes].
 */
@Serializable
data class MaskComponent(
    val kind: MaskKind,
    val op: MaskOp = MaskOp.ADD,
    val params: List<Float> = List(8) { 0f },
    val strokes: List<MaskStroke> = emptyList(),
    /** The model result of an [MaskKind.AI] component; null for every other kind. */
    val bitmap: AiMaskBitmap? = null,
) {
    init {
        require(params.size == 8 && params.all { it.isFinite() }) { "a mask component needs 8 finite parameters" }
        require(strokes.size <= MAX_STROKES) { "a mask has at most $MAX_STROKES strokes" }
        require((kind == MaskKind.AI) == (bitmap != null)) { "only AI components carry a bitmap, and they all do" }
    }

    companion object {
        const val MAX_STROKES = 64

        fun linear(x1: Float, y1: Float, x2: Float, y2: Float) = MaskComponent(MaskKind.LINEAR, params = listOf(x1, y1, x2, y2, 0f, 0f, 0f, 0f))

        fun radial(cx: Float, cy: Float, rx: Float, ry: Float, feather: Float = 0.5f, rotation: Float = 0f, invert: Boolean = false) =
            MaskComponent(MaskKind.RADIAL, params = listOf(cx, cy, rx, ry, rotation, feather, if (invert) 1f else 0f, 0f))

        fun luminance(low: Float = 0f, high: Float = 1f, smoothness: Float = 0.1f) =
            MaskComponent(MaskKind.LUMINANCE, params = listOf(low, high, smoothness, 0f, 0f, 0f, 0f, 0f))

        fun color(r: Float, g: Float, b: Float, tolerance: Float = 0.1f, feather: Float = 0.1f) =
            MaskComponent(MaskKind.COLOR, params = listOf(r, g, b, tolerance, feather, 0f, 0f, 0f))

        fun brush(strokes: List<MaskStroke> = emptyList()) = MaskComponent(MaskKind.BRUSH, strokes = strokes)

        fun ai(bitmap: AiMaskBitmap, op: MaskOp = MaskOp.ADD) = MaskComponent(MaskKind.AI, op, bitmap = bitmap)
    }
}

/**
 * A coarse mask from a segmentation model (255 = inside), kept small so it lives in the recipe: [width] × [height] bytes (2–256 per
 * side) as Base64 in [data]. The pipeline spreads it over the frame with its edges following the picture. [label] names it in the panel.
 */
@Serializable
data class AiMaskBitmap(val width: Int, val height: Int, val data: String, val label: String = "AI selection") {
    init {
        require(width in MIN_SIDE..MAX_SIDE && height in MIN_SIDE..MAX_SIDE) { "an AI mask is $MIN_SIDE–$MAX_SIDE px per side" }
    }

    /** The mask bytes, row by row. */
    fun bytes(): ByteArray = java.util.Base64.getDecoder().decode(data).also {
        require(it.size == width * height) { "the AI mask data does not match ${width}x$height" }
    }

    companion object {
        const val MIN_SIDE = 2
        const val MAX_SIDE = 256

        fun of(width: Int, height: Int, bytes: ByteArray, label: String = "AI selection"): AiMaskBitmap {
            require(bytes.size == width * height) { "the AI mask data does not match ${width}x$height" }
            return AiMaskBitmap(width, height, java.util.Base64.getEncoder().encodeToString(bytes), label)
        }
    }
}

/** A local adjustment: the [adjust] Basic controls apply only where the combined [components] cover, scaled by [amount] (0–1). */
@Serializable
data class LocalMask(
    val name: String = "Mask",
    val enabled: Boolean = true,
    val invert: Boolean = false,
    val amount: Float = 1f,
    val adjust: BasicTone = BasicTone(),
    val components: List<MaskComponent> = emptyList(),
) {
    init {
        require(amount in 0f..1f) { "mask amount must be 0..1" }
        require(components.size <= MAX_COMPONENTS) { "a mask has at most $MAX_COMPONENTS components" }
    }

    companion object {
        const val MAX_COMPONENTS = 8
        const val MAX_MASKS = 16
    }
}

/** Starting points for the Masks panel. */
object LocalMaskTemplates {
    fun of(kind: MaskKind, number: Int): LocalMask {
        val component = when (kind) {
            MaskKind.LINEAR -> MaskComponent.linear(0.5f, 0.15f, 0.5f, 0.55f)
            MaskKind.RADIAL -> MaskComponent.radial(0.5f, 0.5f, 0.25f, 0.25f)
            MaskKind.BRUSH -> MaskComponent.brush()
            MaskKind.LUMINANCE -> MaskComponent.luminance(0.6f, 1f, 0.15f)
            MaskKind.COLOR -> MaskComponent.color(0.5f, 0.5f, 0.5f)
            MaskKind.AI -> throw IllegalArgumentException("AI masks are made from a model's result, not from a template")
        }
        return LocalMask(name = "${kind.label} $number", components = listOf(component))
    }
}

/** A named develop preset stores only the sections it touches ([sections]); others keep the photo's values. */
@Serializable
data class DevelopPreset(val name: String, val settings: DevelopSettings, val sections: Set<DevelopSection> = DevelopSection.entries.toSet()) {
    fun applyTo(target: DevelopSettings): DevelopSettings = DevelopSection.entries.fold(target) { acc, sec ->
        if (sec in sections) sec.copy(from = settings, to = acc) else acc
    }
}

/** Sections for presets and copy/paste/sync. */
@Serializable
enum class DevelopSection(val label: String) {
    PROFILE("Profile"),
    WHITE_BALANCE("White balance"),
    BASIC("Basic"),
    TONE_CURVE("Tone curve"),
    COLOR_MIXER("Color mixer"),
    GRADING("Color grading"),
    DETAIL("Detail"),
    OPTICS("Optics"),
    GEOMETRY("Geometry"),
    CALIBRATION("Calibration"),
    EFFECTS("Effects"),
    CROP("Crop"),
    SPOTS("Spot removal"),
    MASKS("Local masks"),
    ;

    fun copy(from: DevelopSettings, to: DevelopSettings): DevelopSettings = when (this) {
        PROFILE -> to.copy(profile = from.profile)
        WHITE_BALANCE -> to.copy(whiteBalance = from.whiteBalance)
        BASIC -> to.copy(basic = from.basic)
        TONE_CURVE -> to.copy(toneCurve = from.toneCurve)
        COLOR_MIXER -> to.copy(mixer = from.mixer)
        GRADING -> to.copy(grading = from.grading)
        DETAIL -> to.copy(detail = from.detail)
        OPTICS -> to.copy(optics = from.optics.copy(distortion = to.optics.distortion))
        GEOMETRY -> to.copy(upright = from.upright, uprightManual = from.uprightManual, uprightGuides = from.uprightGuides, optics = to.optics.copy(distortion = from.optics.distortion))
        CALIBRATION -> to.copy(calibration = from.calibration)
        EFFECTS -> to.copy(effects = from.effects)
        CROP -> to.copy(crop = from.crop)
        SPOTS -> to.copy(spots = from.spots)
        MASKS -> to.copy(masks = from.masks)
    }
}

/** Built-in looks. Deliberately few and tasteful. */
object BuiltInPresets {
    val all: List<DevelopPreset> = listOf(
        DevelopPreset("Natural pop", DevelopSettings(basic = BasicTone(contrast = 10f, clarity = 8f, vibrance = 15f)), setOf(DevelopSection.BASIC)),
        DevelopPreset(
            "Bright & airy",
            DevelopSettings(basic = BasicTone(exposure = 0.4f, highlights = -30f, shadows = 35f, whites = 10f, vibrance = 10f)),
            setOf(DevelopSection.BASIC),
        ),
        DevelopPreset(
            "Moody",
            DevelopSettings(
                basic = BasicTone(exposure = -0.3f, contrast = 20f, highlights = -40f, blacks = -15f, saturation = -15f),
                grading = ColorGrading(shadows = GradeWheel(210f, 15f), highlights = GradeWheel(40f, 10f)),
            ),
            setOf(DevelopSection.BASIC, DevelopSection.GRADING),
        ),
        DevelopPreset(
            "Classic B&W",
            DevelopSettings(profile = DevelopProfile.MONOCHROME, basic = BasicTone(contrast = 25f, clarity = 15f)),
            setOf(DevelopSection.PROFILE, DevelopSection.BASIC),
        ),
        DevelopPreset(
            "Film grain",
            DevelopSettings(effects = Effects(grainAmount = 25f, grainSize = 30f, vignetteAmount = -12f)),
            setOf(DevelopSection.EFFECTS),
        ),
    )
}
