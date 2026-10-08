package app.croptop.core.engine

import app.croptop.core.model.AdjustmentInfo
import app.croptop.core.model.ColorBalance
import app.croptop.core.model.ColorBalanceShift
import app.croptop.core.model.Curve
import app.croptop.core.model.CurveChannels
import app.croptop.core.model.CurvePoint
import app.croptop.core.model.HueChannel
import app.croptop.core.model.HueRange
import app.croptop.core.model.HueSaturation
import app.croptop.core.model.HueShift
import app.croptop.core.model.Levels
import app.croptop.core.model.ParamValue
import kotlin.math.roundToInt

/**
 * Photoshop adjustment layer settings (the legacy binary blocks of the PSD spec) mapped onto CropTop
 * adjustments. Unknown or malformed blocks return null, and the importer skips that layer as before.
 */
internal object PsdAdjustments {
    fun parse(key: String, hex: String): AdjustmentInfo? = runCatching {
        val data = Reader(hexToBytes(hex))
        when (key) {
            "nvrt" -> AdjustmentInfo("invert")

            "thrs" -> AdjustmentInfo("threshold", mapOf("level" to ParamValue.Num(data.short().toFloat().coerceIn(1f, 255f))))

            "post" -> AdjustmentInfo("posterize", mapOf("levels" to ParamValue.Num(data.short().toFloat().coerceIn(2f, 255f))))

            "brit" -> AdjustmentInfo(
                "brightnessContrast",
                mapOf(
                    "brightness" to ParamValue.Num(data.short().toFloat().coerceIn(-150f, 150f)),
                    "contrast" to ParamValue.Num(data.short().toFloat().coerceIn(-50f, 100f)),
                    "legacy" to ParamValue.Bool(false),
                ),
            )

            "levl" -> levels(data)

            "curv" -> curves(data)

            "hue2", "hue " -> hueSaturation(data, key == "hue2")

            "blnc" -> colorBalance(data)

            else -> null
        }
    }.getOrNull()

    /** The legacy block (key, payload) Photoshop reads for [info], or null when it has no PSD equivalent. */
    fun encode(info: AdjustmentInfo): Pair<String, ByteArray>? = runCatching {
        val out = Writer()
        fun num(key: String, default: Float) = (info.values[key] as? ParamValue.Num)?.v ?: default
        when (info.specId) {
            "invert" -> "nvrt" to ByteArray(0)

            "threshold" -> "thrs" to out.short(num("level", 128f).roundToInt()).short(0).bytes()

            "posterize" -> "post" to out.short(num("levels", 4f).roundToInt()).short(0).bytes()

            "brightnessContrast" -> "brit" to out.short(num("brightness", 0f).roundToInt()).short(num("contrast", 0f).roundToInt())
                .short(127).byte(0).byte(0).bytes()

            "levels" -> {
                val l = (info.values["levels"] as? ParamValue.LevelsValue)?.v ?: Levels()
                out.short(2)
                out.short((l.inBlack * 255f).roundToInt()).short((l.inWhite * 255f).roundToInt())
                    .short((l.outBlack * 255f).roundToInt()).short((l.outWhite * 255f).roundToInt()).short((l.gamma * 100f).roundToInt())
                repeat(28) { out.short(0).short(255).short(0).short(255).short(100) }
                "levl" to out.bytes()
            }

            "curves" -> {
                val c = when (val v = info.values["curve"]) {
                    is ParamValue.CurveChannelsValue -> v.v
                    is ParamValue.CurveValue -> CurveChannels(rgb = v.v)
                    else -> CurveChannels()
                }
                val curves = listOf(c.rgb, c.red, c.green, c.blue)
                out.byte(0).short(1).int(0b1111)
                for (curve in curves) {
                    val points = curve.points.take(19)
                    out.short(points.size)
                    points.forEach { p -> out.short((p.y * 255f).roundToInt()).short((p.x * 255f).roundToInt()) }
                }
                "curv" to out.bytes()
            }

            "hueSaturation" -> {
                val h = (info.values["hueSat"] as? ParamValue.HueSatValue)?.v ?: HueSaturation()
                fun shift(s: HueShift) {
                    out.short(s.hue.roundToInt()).short(s.saturation.roundToInt()).short(s.lightness.roundToInt())
                }
                out.short(2).byte(if (h.colorize) 1 else 0).byte(0)
                shift(if (h.colorize) h.master else HueShift(0f, 25f, 0f))
                shift(if (h.colorize) HueShift() else h.master)
                for (channel in listOf(
                    HueChannel.REDS,
                    HueChannel.YELLOWS,
                    HueChannel.GREENS,
                    HueChannel.CYANS,
                    HueChannel.BLUES,
                    HueChannel.MAGENTAS,
                )) {
                    val r = h.range(channel) ?: HueRange(0f, 0f, 0f, 0f)
                    listOf(r.outerStart, r.innerStart, r.innerEnd, r.outerEnd).forEach { out.short(it.roundToInt()) }
                    shift(h.shift(channel))
                }
                "hue2" to out.bytes()
            }

            "colorBalance" -> {
                val b = (info.values["balance"] as? ParamValue.BalanceValue)?.v ?: ColorBalance()
                for (s in listOf(b.shadows, b.midtones, b.highlights)) {
                    out.short(s.cyanRed.roundToInt()).short(s.magentaGreen.roundToInt()).short(s.yellowBlue.roundToInt())
                }
                "blnc" to out.byte(if (b.preserveLuminosity) 1 else 0).byte(0).bytes()
            }

            else -> null
        }
    }.getOrNull()

    private class Writer {
        private val buffer = java.io.ByteArrayOutputStream()
        fun byte(v: Int) = apply { buffer.write(v and 0xFF) }
        fun short(v: Int) = apply { byte(v shr 8).byte(v) }
        fun int(v: Int) = apply { short(v shr 16).short(v) }
        fun bytes(): ByteArray = buffer.toByteArray()
    }

    private fun levels(data: Reader): AdjustmentInfo? {
        if (data.short() != 2) return null
        // Record 0 is the composite (RGB) channel: input floor/ceiling, output floor/ceiling, gamma × 100.
        val inFloor = data.short()
        val inCeiling = data.short()
        val outFloor = data.short()
        val outCeiling = data.short()
        val gamma = data.short()
        val levels = Levels(
            inBlack = inFloor.coerceIn(0, 253) / 255f,
            inWhite = inCeiling.coerceIn(2, 255) / 255f,
            gamma = (gamma / 100f).coerceIn(0.1f, 9.99f),
            outBlack = outFloor.coerceIn(0, 255) / 255f,
            outWhite = outCeiling.coerceIn(0, 255) / 255f,
        )
        return AdjustmentInfo("levels", mapOf("levels" to ParamValue.LevelsValue(levels)))
    }

    private fun curves(data: Reader): AdjustmentInfo? {
        data.skip(1)
        val version = data.short()
        if (version != 1 && version != 4) return null
        val present = data.int()
        val curves = arrayOfNulls<Curve>(4)
        for (channel in 0 until 32) {
            if (present and (1 shl channel) == 0) continue
            val count = data.short()
            if (count !in 2..19) return null
            val points = List(count) {
                val output = data.short()
                val input = data.short()
                CurvePoint(input.coerceIn(0, 255) / 255f, output.coerceIn(0, 255) / 255f)
            }.sortedBy { it.x }.distinctBy { it.x }
            if (channel < 4 && points.size >= 2) curves[channel] = Curve(points)
        }
        val channels = CurveChannels(
            rgb = curves[0] ?: Curve.Identity,
            red = curves[1] ?: Curve.Identity,
            green = curves[2] ?: Curve.Identity,
            blue = curves[3] ?: Curve.Identity,
        )
        return AdjustmentInfo("curves", mapOf("curve" to ParamValue.CurveChannelsValue(channels)))
    }

    private fun hueSaturation(data: Reader, modern: Boolean): AdjustmentInfo? {
        if (data.short() != 2) return null
        val colorize = data.byte() != 0
        data.skip(1)
        val colorization = HueShift(data.short().toFloat(), data.short().toFloat(), data.short().toFloat())
        val master = HueShift(data.short().toFloat(), data.short().toFloat(), data.short().toFloat())
        val channels = mutableMapOf<HueChannel, HueShift>()
        val ranges = mutableMapOf<HueChannel, HueRange>()
        val order = listOf(HueChannel.REDS, HueChannel.YELLOWS, HueChannel.GREENS, HueChannel.CYANS, HueChannel.BLUES, HueChannel.MAGENTAS)
        for (channel in order) {
            if (data.remaining < 14) break
            val range = List(4) { data.short().toFloat() }
            val shift = HueShift(data.short().toFloat(), data.short().toFloat(), data.short().toFloat())
            if (modern) ranges[channel] = HueRange(wrap(range[0]), wrap(range[1]), wrap(range[2]), wrap(range[3]))
            if (!shift.isIdentity) channels[channel] = shift
        }
        val settings = HueSaturation(
            master = if (colorize) colorization else master,
            channels = if (colorize) emptyMap() else channels,
            ranges = ranges,
            colorize = colorize,
        )
        return AdjustmentInfo("hueSaturation", mapOf("hueSat" to ParamValue.HueSatValue(settings)))
    }

    private fun colorBalance(data: Reader): AdjustmentInfo {
        fun shift() = ColorBalanceShift(data.short().toFloat(), data.short().toFloat(), data.short().toFloat())
        val shadows = shift()
        val midtones = shift()
        val highlights = shift()
        val preserve = data.remaining == 0 || data.byte() != 0
        return AdjustmentInfo("colorBalance", mapOf("balance" to ParamValue.BalanceValue(ColorBalance(shadows, midtones, highlights, preserve))))
    }

    private fun wrap(degrees: Float): Float = ((degrees % 360f) + 360f) % 360f

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "odd hex length" }
        return ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    /** Big-endian reader; running off the end throws (the block is then ignored). */
    private class Reader(private val bytes: ByteArray) {
        private var position = 0
        val remaining: Int get() = bytes.size - position

        fun byte(): Int {
            require(position < bytes.size) { "truncated adjustment block" }
            return bytes[position++].toInt() and 0xFF
        }

        fun skip(count: Int) {
            require(remaining >= count) { "truncated adjustment block" }
            position += count
        }

        /** Signed 16-bit. */
        fun short(): Int = ((byte() shl 8) or byte()).toShort().toInt()

        fun int(): Int = (byte() shl 24) or (byte() shl 16) or (byte() shl 8) or byte()
    }
}
