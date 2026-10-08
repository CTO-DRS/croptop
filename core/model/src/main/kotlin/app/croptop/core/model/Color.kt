package app.croptop.core.model

import kotlinx.serialization.Serializable

/** Storage bit depth per channel. 16/32 are Pro. */
@Serializable
enum class ColorDepth(val bitsPerChannel: Int, val label: String) {
    U8(8, "8-bit"),
    U16(16, "16-bit"),
    F32(32, "32-bit float"),
}

/** Built-in working spaces. */
@Serializable
enum class WorkingSpace(val label: String) {
    SRGB("sRGB IEC61966-2.1"),
    DISPLAY_P3("Display P3"),
    ADOBE_RGB("Adobe RGB (1998)"),
    PROPHOTO_RGB("ProPhoto RGB"),
}

@Serializable
sealed interface ColorProfileRef {
    val displayName: String

    @Serializable
    data class BuiltIn(val space: WorkingSpace, val linear: Boolean = false) : ColorProfileRef {
        override val displayName: String get() = if (linear) "${space.label} (Linear)" else space.label
    }

    /** An ICC profile embedded in or imported with a document. [hash] identifies the bytes held by the engine. */
    @Serializable
    data class Embedded(override val displayName: String, val hash: String) : ColorProfileRef

    companion object {
        val Srgb: ColorProfileRef = BuiltIn(WorkingSpace.SRGB)
        val DisplayP3: ColorProfileRef = BuiltIn(WorkingSpace.DISPLAY_P3)
    }
}

/** Non-premultiplied sRGB-encoded color used by UI (swatches, pickers). 0..1 floats. */
@Serializable
data class RgbaColor(val r: Float, val g: Float, val b: Float, val a: Float = 1f) {
    fun toArgb(): Int {
        fun c(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return (c(a) shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
    }

    val hex: String get() = "%06X".format(toArgb() and 0xFFFFFF)

    companion object {
        val Black = RgbaColor(0f, 0f, 0f)
        val White = RgbaColor(1f, 1f, 1f)
        val Transparent = RgbaColor(0f, 0f, 0f, 0f)

        fun fromArgb(argb: Int) = RgbaColor(
            r = (argb shr 16 and 0xFF) / 255f,
            g = (argb shr 8 and 0xFF) / 255f,
            b = (argb and 0xFF) / 255f,
            a = (argb ushr 24) / 255f,
        )

        /** Parses `#RRGGBB`, `RRGGBB`, `#RGB` or `#AARRGGBB`. Returns null when invalid. */
        fun parseHex(input: String): RgbaColor? {
            val s = input.trim().removePrefix("#")
            val expanded = when (s.length) {
                3 -> s.map { "$it$it" }.joinToString("")
                6, 8 -> s
                else -> return null
            }
            val v = expanded.toLongOrNull(16) ?: return null
            return fromArgb(if (expanded.length == 6) (0xFF000000 or v).toInt() else v.toInt())
        }
    }
}
