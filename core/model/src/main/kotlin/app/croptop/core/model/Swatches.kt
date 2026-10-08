package app.croptop.core.model

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.serialization.Serializable

@Serializable
data class Swatch(val name: String, val color: RgbaColor)

class SwatchFormatException(message: String) : Exception(message)

/**
 * Photoshop swatch files: `.aco` (v1 and v2 with names) and Adobe `.ase` exchange files.
 * CMYK is converted naively (no ICC), which matches what most hobby palettes expect on screen.
 */
object SwatchIo {
    const val MAX_SWATCHES = 4096

    fun parse(bytes: ByteArray, fileName: String = ""): List<Swatch> = when {
        bytes.size >= 4 && String(bytes, 0, 4, Charsets.US_ASCII) == "ASEF" -> parseAse(bytes)
        fileName.endsWith(".aco", ignoreCase = true) || (bytes.size >= 2 && bytes[0].toInt() == 0 && bytes[1].toInt() in 1..2) -> parseAco(bytes)
        else -> throw SwatchFormatException("Not a swatch file. Choose an .aco or .ase file.")
    }

    private fun cmyk(c: Float, m: Float, y: Float, k: Float) = RgbaColor((1 - c) * (1 - k), (1 - m) * (1 - k), (1 - y) * (1 - k))

    private fun acoColor(space: Int, w: IntArray): RgbaColor? = when (space) {
        0 -> RgbaColor(w[0] / 65535f, w[1] / 65535f, w[2] / 65535f)

        1 -> ColorMath.fromHsv(Hsv(w[0] / 65535f * 360f, w[1] / 65535f, w[2] / 65535f))

        // ACO stores CMYK inverted: 0 = 100% ink.
        2 -> cmyk(1 - w[0] / 65535f, 1 - w[1] / 65535f, 1 - w[2] / 65535f, 1 - w[3] / 65535f)

        7 -> ColorMath.fromLab(Lab(w[0] / 100f, w[1].toShort() / 100f, w[2].toShort() / 100f))

        8 -> (1f - w[0] / 10000f).coerceIn(0f, 1f).let { RgbaColor(it, it, it) }

        else -> null
    }

    fun parseAco(bytes: ByteArray): List<Swatch> {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        fun u16() = if (buf.remaining() >= 2) buf.short.toInt() and 0xFFFF else throw SwatchFormatException("The swatch file is truncated.")

        fun section(named: Boolean): List<Swatch> {
            val count = u16()
            if (count > MAX_SWATCHES) throw SwatchFormatException("Too many swatches ($count).")
            return (0 until count).mapNotNull { i ->
                val space = u16()
                val w = IntArray(4) { u16() }
                val name = if (named) {
                    u16() // reserved 0
                    val len = u16()
                    if (len > 1024 || buf.remaining() < len * 2) throw SwatchFormatException("The swatch file is truncated.")
                    val chars = CharArray(len) { buf.short.toInt().toChar() }
                    String(chars).trimEnd('\u0000')
                } else {
                    ""
                }
                acoColor(space, w)?.let { Swatch(name.ifBlank { "Swatch ${i + 1}" }, it) }
            }
        }
        val version = u16()
        if (version !in 1..2) throw SwatchFormatException("Unsupported .aco version $version.")
        val v1 = section(named = version == 2)
        // v1 files usually carry a v2 section with names right after; prefer it.
        if (version == 1 && buf.remaining() >= 4) {
            val mark = buf.position()
            if (u16() == 2) return runCatching { section(named = true) }.getOrElse { v1 }
            buf.position(mark)
        }
        return v1
    }

    fun parseAse(bytes: ByteArray): List<Swatch> {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        if (bytes.size < 12) throw SwatchFormatException("The swatch file is truncated.")
        buf.position(4)
        buf.int // version 1.0
        val blocks = buf.int
        if (blocks < 0 || blocks > MAX_SWATCHES * 2) throw SwatchFormatException("Too many swatches.")
        val out = mutableListOf<Swatch>()
        repeat(blocks) {
            if (buf.remaining() < 6) throw SwatchFormatException("The swatch file is truncated.")
            val type = buf.short.toInt() and 0xFFFF
            val length = buf.int
            if (length < 0 || length > buf.remaining()) throw SwatchFormatException("The swatch file is truncated.")
            val end = buf.position() + length
            if (type == 0x0001 && length >= 2) {
                val nameLen = buf.short.toInt() and 0xFFFF
                val name = String(CharArray(nameLen) { buf.short.toInt().toChar() }).trimEnd('\u0000')
                val model = ByteArray(4).also { buf.get(it) }.toString(Charsets.US_ASCII)
                val color = when (model) {
                    "RGB " -> RgbaColor(buf.float, buf.float, buf.float)
                    "CMYK" -> cmyk(buf.float, buf.float, buf.float, buf.float)
                    "LAB " -> ColorMath.fromLab(Lab(buf.float * 100f, buf.float, buf.float))
                    "Gray" -> buf.float.let { RgbaColor(it, it, it) }
                    else -> null
                }
                if (color != null) out += Swatch(name.ifBlank { "Swatch ${out.size + 1}" }, color)
            }
            buf.position(end)
        }
        return out
    }

    /** Writes an RGB `.ase` (the most portable swatch format). */
    fun writeAse(swatches: List<Swatch>): ByteArray {
        val out = ByteArrayOutputStream()
        fun b(size: Int, fill: ByteBuffer.() -> Unit) = out.write(ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN).apply(fill).array())
        b(12) {
            put("ASEF".toByteArray(Charsets.US_ASCII))
            putShort(1)
            putShort(0)
            putInt(swatches.size)
        }
        swatches.forEach { s ->
            val name = s.name.take(255)
            val length = 2 + (name.length + 1) * 2 + 4 + 12 + 2
            b(6 + length) {
                putShort(0x0001)
                putInt(length)
                putShort((name.length + 1).toShort())
                name.forEach { putChar(it) }
                putChar('\u0000')
                put("RGB ".toByteArray(Charsets.US_ASCII))
                putFloat(s.color.r)
                putFloat(s.color.g)
                putFloat(s.color.b)
                putShort(2) // normal (not global/spot)
            }
        }
        return out.toByteArray()
    }
}
