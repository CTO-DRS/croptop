package app.croptop.core.engine

import app.croptop.core.model.BasicTone
import app.croptop.core.model.AiMaskBitmap
import app.croptop.core.model.Calibration
import app.croptop.core.model.ColorGrading
import app.croptop.core.model.ColorMixer
import app.croptop.core.model.ColorRange
import app.croptop.core.model.CropSettings
import app.croptop.core.model.Curve
import app.croptop.core.model.CurvePoint
import app.croptop.core.model.Detail
import app.croptop.core.model.DevelopProfile
import app.croptop.core.model.DevelopSettings
import app.croptop.core.model.Effects
import app.croptop.core.model.GradeWheel
import app.croptop.core.model.HslShift
import app.croptop.core.model.LocalMask
import app.croptop.core.model.MaskComponent
import app.croptop.core.model.MaskKind
import app.croptop.core.model.MaskOp
import app.croptop.core.model.MaskPoint
import app.croptop.core.model.MaskStroke
import app.croptop.core.model.Optics
import app.croptop.core.model.Spot
import app.croptop.core.model.Upright
import app.croptop.core.model.UprightGuide
import app.croptop.core.model.UprightManual
import app.croptop.core.model.WbPreset
import app.croptop.core.model.WhiteBalance

/**
 * The develop pipeline (native `develop_pipeline`): renders a scene-linear RGBA16/RGBA32F layer through [DevelopSettings] into a new
 * native store the caller owns (`NativeSelectionBridge.destroyStore`). Tone and colour run on the GPU when [useGpu] is set and a
 * hardware compute device exists; each stage falls back to the CPU by itself, and [DevelopRender.toneOnGpu]/[colorOnGpu] say what
 * happened. Settings travel as one FloatArray in the layout documented in `develop_settings_codec.h`.
 */
internal object NativeDevelopBridge {
    enum class Mode { FULL, PREVIEW }

    data class DevelopRender(
        val storeHandle: Long,
        val width: Int,
        val height: Int,
        val toneOnGpu: Boolean,
        val colorOnGpu: Boolean,
        val elapsedMs: Long,
        val warnings: List<String>,
    )

    fun render(
        storeHandle: Long,
        settings: DevelopSettings,
        mode: Mode = Mode.FULL,
        previewMax: Int = 1024,
        useGpu: Boolean = true,
        workerThreads: Int = 0,
        grainSeed: Int = 1,
        /** From [newGeneration]; a render whose generation is no longer the latest is cancelled (fails with "cancelled"). 0 = not cancellable. */
        generation: Long = 0L,
    ): DevelopRender {
        val handle = nativeRender(storeHandle, encode(settings), if (mode == Mode.FULL) 0 else 1, 0, previewMax, workerThreads, useGpu, grainSeed, generation)
        check(handle != 0L) { nativeLastError().ifBlank { "Develop failed" } }
        val info = nativeLastInfo().split(';').mapNotNull { it.split('=', limit = 2).takeIf { p -> p.size == 2 }?.let { p -> p[0] to p[1] } }.toMap()
        return DevelopRender(
            handle,
            info["width"]?.toIntOrNull() ?: 0,
            info["height"]?.toIntOrNull() ?: 0,
            info["toneGpu"] == "1",
            info["colorGpu"] == "1",
            info["elapsedMs"]?.toLongOrNull() ?: 0L,
            info["warnings"].orEmpty().split('|').filter(String::isNotBlank),
        )
    }

    /** Advances the cancellation clock: every render started with an older generation stops at its next boundary. Call before each new preview. */
    fun newGeneration(): Long = nativeNewGeneration()

    /** "device=<name>" when a hardware compute device exists, else "unavailable: <reason>". Creates the device on first call. */
    fun gpuStatus(): String = nativeGpuStatus()

    /** Layout in `develop_settings_codec.h`; exposed for tests. */
    fun encode(s: DevelopSettings): FloatArray {
        val points = s.toneCurve.points
        require(points.size in 2..MAX_CURVE_POINTS) { "the tone curve needs 2..$MAX_CURVE_POINTS points" }
        val out = ArrayList<Float>(FIXED + points.size * 2)
        out += s.version.toFloat()
        out += s.profile.ordinal.toFloat()
        out += s.whiteBalance.preset.ordinal.toFloat()
        out += s.whiteBalance.temperature
        out += s.whiteBalance.tint
        val b = s.basic
        out += listOf(b.exposure, b.contrast, b.highlights, b.shadows, b.whites, b.blacks, b.texture, b.clarity, b.dehaze, b.vibrance, b.saturation)
        for (range in ColorRange.entries) {
            val m = s.mixer.of(range)
            out += listOf(m.hue, m.saturation, m.luminance)
        }
        for (w in listOf(s.grading.shadows, s.grading.midtones, s.grading.highlights, s.grading.global)) out += listOf(w.hue, w.saturation, w.luminance)
        out += s.grading.blending
        out += s.grading.balance
        val d = s.detail
        out += listOf(d.sharpenAmount, d.sharpenRadius, d.sharpenDetail, d.sharpenMasking, d.luminanceNoise, d.colorNoise)
        val e = s.effects
        out += listOf(e.vignetteAmount, e.vignetteMidpoint, e.grainAmount, e.grainSize)
        out += listOf(if (s.optics.lensProfile) 1f else 0f, if (s.optics.removeChromaticAberration) 1f else 0f, s.optics.vignetting, s.optics.distortion)
        val c = s.crop
        out += listOf(c.left, c.top, c.right, c.bottom, c.angle)
        out += points.size.toFloat()
        for (p in points) {
            out += p.x
            out += p.y
        }
        // Calibration (7 floats) and spots (count + 8 each) follow the curve whenever either, or the masks after them, are present.
        if (!s.calibration.isIdentity || s.upright != Upright.OFF || s.optics.defringePurple != 0f || s.optics.defringeGreen != 0f ||
            !s.uprightManual.isIdentity || s.uprightGuides.isNotEmpty() || s.spots.isNotEmpty() || s.masks.isNotEmpty()
        ) {
            require(s.spots.size <= Spot.MAX_SPOTS) { "at most ${Spot.MAX_SPOTS} spot repairs" }
            out += s.calibration.toList()
            out += s.upright.ordinal.toFloat()
            out += s.optics.defringePurple
            out += s.optics.defringeGreen
            val um = s.uprightManual
            out += listOf(um.vertical, um.horizontal, um.rotate, um.aspect, um.scale, um.xOffset, um.yOffset)
            require(s.uprightGuides.size <= UprightGuide.MAX) { "at most ${UprightGuide.MAX} upright guides" }
            out += s.uprightGuides.size.toFloat()
            for (g in s.uprightGuides) out += listOf(g.x1, g.y1, g.x2, g.y2)
            out += s.spots.size.toFloat()
            for (sp in s.spots) out += listOf(sp.x, sp.y, sp.radius, sp.sourceX, sp.sourceY, sp.feather, sp.opacity, if (sp.heal) 1f else 0f)
        }
        if (s.masks.isNotEmpty()) {
            require(s.masks.size <= LocalMask.MAX_MASKS) { "at most ${LocalMask.MAX_MASKS} local masks" }
            out += s.masks.size.toFloat()
            for (m in s.masks) {
                require(m.components.isNotEmpty()) { "a local mask needs at least one component" }
                out += if (m.enabled) 1f else 0f
                out += if (m.invert) 1f else 0f
                out += m.amount
                val a = m.adjust
                out += listOf(a.exposure, a.contrast, a.highlights, a.shadows, a.whites, a.blacks, a.texture, a.clarity, a.dehaze, a.vibrance, a.saturation)
                out += m.components.size.toFloat()
                for (c in m.components) {
                    out += c.kind.ordinal.toFloat()
                    out += c.op.ordinal.toFloat()
                    out += c.params
                    c.bitmap?.let { b ->
                        out += b.width.toFloat()
                        out += b.height.toFloat()
                        for (byte in b.bytes()) out += (byte.toInt() and 0xFF).toFloat()
                    }
                    out += c.strokes.size.toFloat()
                    for (st in c.strokes) {
                        out += if (st.erase) 1f else 0f
                        out += st.size
                        out += st.feather
                        out += st.flow
                        out += st.points.size.toFloat()
                        for (p in st.points) {
                            out += p.x
                            out += p.y
                        }
                    }
                }
            }
        }
        return out.toFloatArray()
    }

    /** Decodes the same versioned layout as [encode], including the variable-length point curve. */
    fun decode(values: FloatArray): DevelopSettings {
        require(values.size >= FIXED && values.all(Float::isFinite)) { "Develop settings array is invalid" }
        require(values[0] == DevelopSettings.VERSION.toFloat()) { "Unsupported Develop settings version" }
        fun enumIndex(value: Float, size: Int, field: String): Int {
            val index = value.toInt()
            require(value == index.toFloat() && index in 0 until size) { "Invalid Develop $field" }
            return index
        }
        var i = 1
        val profile = DevelopProfile.entries[enumIndex(values[i++], DevelopProfile.entries.size, "profile")]
        val preset = WbPreset.entries[enumIndex(values[i++], WbPreset.entries.size, "white balance preset")]
        val whiteBalance = WhiteBalance(preset, values[i++], values[i++])
        val basic = BasicTone(
            exposure = values[i++], contrast = values[i++], highlights = values[i++], shadows = values[i++], whites = values[i++],
            blacks = values[i++], texture = values[i++], clarity = values[i++], dehaze = values[i++], vibrance = values[i++], saturation = values[i++],
        )
        val ranges = buildMap {
            for (range in ColorRange.entries) {
                val shift = HslShift(values[i++], values[i++], values[i++])
                if (shift != HslShift()) put(range, shift)
            }
        }
        val wheels = List(4) { GradeWheel(values[i++], values[i++], values[i++]) }
        val grading = ColorGrading(wheels[0], wheels[1], wheels[2], wheels[3], values[i++], values[i++])
        val detail = Detail(values[i++], values[i++], values[i++], values[i++], values[i++], values[i++])
        val effects = Effects(values[i++], values[i++], values[i++], values[i++])
        val lensProfile = values[i++]
        val removeCa = values[i++]
        require(lensProfile == 0f || lensProfile == 1f) { "Invalid Develop lens-profile flag" }
        require(removeCa == 0f || removeCa == 1f) { "Invalid Develop chromatic-aberration flag" }
        val opticsBase = Optics(lensProfile == 1f, removeCa == 1f, values[i++], values[i++])
        val crop = CropSettings(values[i++], values[i++], values[i++], values[i++], values[i++])
        val countValue = values[i++]
        val count = countValue.toInt()
        require(countValue == count.toFloat() && count in 2..MAX_CURVE_POINTS && values.size >= FIXED + count * 2) {
            "Develop tone-curve point count does not match the settings array"
        }
        val points = List(count) { CurvePoint(values[i++], values[i++]) }
        var calibration = Calibration()
        var spots = emptyList<Spot>()
        var upright = Upright.OFF
        var defringePurple = 0f
        var defringeGreen = 0f
        var uprightManual = UprightManual()
        var uprightGuides = emptyList<UprightGuide>()
        if (i < values.size) {
            require(values.size - i > Calibration.SIZE + 3 + 8) { "Develop calibration data is truncated" }
            calibration = Calibration.of(values.slice(i until i + Calibration.SIZE))
            i += Calibration.SIZE
            upright = Upright.entries[enumIndex(values[i++], Upright.entries.size, "upright mode")]
            defringePurple = values[i++]
            defringeGreen = values[i++]
            uprightManual = UprightManual(values[i++], values[i++], values[i++], values[i++], values[i++], values[i++], values[i++])
            val guideValue = values[i++]
            val guideCount = guideValue.toInt()
            require(guideValue == guideCount.toFloat() && guideCount in 0..UprightGuide.MAX && values.size - i > guideCount * 4) {
                "Develop upright guide data is invalid or truncated"
            }
            uprightGuides = List(guideCount) { UprightGuide(values[i++], values[i++], values[i++], values[i++]) }
            val spotCountValue = values[i++]
            val spotCount = spotCountValue.toInt()
            require(spotCountValue == spotCount.toFloat() && spotCount in 0..Spot.MAX_SPOTS && values.size - i >= spotCount * 8) {
                "Develop spot data is invalid or truncated"
            }
            spots = List(spotCount) {
                Spot(values[i++], values[i++], values[i++], values[i++], values[i++], values[i++], values[i++], values[i++] != 0f)
            }
        }
        val masks = if (i < values.size) decodeMasks(values, i) else emptyList()
        return DevelopSettings(
            profile = profile,
            whiteBalance = whiteBalance,
            basic = basic,
            toneCurve = Curve(points),
            mixer = ColorMixer(ranges),
            grading = grading,
            calibration = calibration,
            detail = detail,
            optics = opticsBase.copy(defringePurple = defringePurple, defringeGreen = defringeGreen),
            effects = effects,
            crop = crop,
            upright = upright,
            uprightManual = uprightManual,
            uprightGuides = uprightGuides,
            spots = spots,
            masks = masks,
        )
    }

    private fun decodeMasks(values: FloatArray, start: Int): List<LocalMask> {
        var i = start
        fun next(): Float {
            require(i < values.size) { "Develop local mask data is truncated" }
            return values[i++]
        }
        fun count(max: Int, what: String): Int {
            val v = next()
            val n = v.toInt()
            require(v == n.toFloat() && n in 0..max) { "Invalid Develop $what count" }
            return n
        }
        val masks = List(count(LocalMask.MAX_MASKS, "local mask")) {
            val enabled = next() != 0f
            val invert = next() != 0f
            val amount = next()
            val adjust = BasicTone(next(), next(), next(), next(), next(), next(), next(), next(), next(), next(), next())
            val components = List(count(LocalMask.MAX_COMPONENTS, "mask component")) {
                val kind = MaskKind.entries[next().toInt().also { k -> require(k in MaskKind.entries.indices) { "Invalid Develop mask kind" } }]
                val op = MaskOp.entries[next().toInt().also { k -> require(k in MaskOp.entries.indices) { "Invalid Develop mask operation" } }]
                val params = List(8) { next() }
                val bitmap = if (kind == MaskKind.AI) {
                    val w = count(AiMaskBitmap.MAX_SIDE, "AI mask width")
                    val h = count(AiMaskBitmap.MAX_SIDE, "AI mask height")
                    val bytes = ByteArray(w * h) { next().toInt().toByte() }
                    AiMaskBitmap.of(w, h, bytes)
                } else {
                    null
                }
                val strokes = List(count(MaskComponent.MAX_STROKES, "brush stroke")) {
                    val erase = next() != 0f
                    val size = next()
                    val feather = next()
                    val flow = next()
                    val points = List(count(MaskStroke.MAX_POINTS, "brush point")) { MaskPoint(next(), next()) }
                    MaskStroke(points, size, feather, flow, erase)
                }
                MaskComponent(kind, op, params, strokes, bitmap)
            }
            LocalMask(enabled = enabled, invert = invert, amount = amount, adjust = adjust, components = components)
        }
        require(i == values.size) { "Develop settings have trailing values" }
        return masks
    }

    const val FIXED = 74
    const val MAX_CURVE_POINTS = 32

    private external fun nativeLastError(): String
    private external fun nativeLastInfo(): String
    private external fun nativeGpuStatus(): String
    private external fun nativeRender(storeHandle: Long, settings: FloatArray, mode: Int, tier: Int, previewMax: Int, workerThreads: Int, useGpu: Boolean, grainSeed: Int, generation: Long): Long
    private external fun nativeNewGeneration(): Long
}
