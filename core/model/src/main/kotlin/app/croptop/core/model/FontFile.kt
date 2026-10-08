package app.croptop.core.model

/** Random access to font file bytes; the picker reads only a few small tables, never the whole file. */
fun interface FontBytes {
    /** Reads [length] bytes at [offset]; may return fewer at end of file. */
    fun read(offset: Long, length: Int): ByteArray
}

class FontFormatException(message: String) : Exception(message)

/**
 * Minimal SFNT reader (TrueType/OpenType/collections) for the font picker: family and style names
 * (`name`), weight and italic (`OS/2`) and variable axes (`fvar`). Shaping and rendering are the text
 * engine's job.
 */
object FontFile {
    private const val TTCF = 0x74746366
    private const val MAX_TABLES = 512
    private const val MAX_TABLE_BYTES = 1 shl 20

    /** Number of faces: 1 for a plain font, N for a .ttc/.otc collection. */
    fun faceCount(src: FontBytes): Int {
        val head = src.read(0, 12)
        if (head.size < 12) throw FontFormatException("File is too short to be a font.")
        return if (head.u32(0) == TTCF.toLong()) head.u32(8).toInt().coerceIn(1, 256) else 1
    }

    /** Reads face [index]. [path] is stored on the result for the renderer. */
    fun read(src: FontBytes, path: String, index: Int = 0): FontFace {
        val head = src.read(0, 12)
        if (head.size < 12) throw FontFormatException("File is too short to be a font.")
        val base: Long = if (head.u32(0) == TTCF.toLong()) {
            val n = head.u32(8).toInt()
            if (index !in 0 until n) throw FontFormatException("Face $index is not in this collection.")
            src.read(12L + 4 * index, 4).u32(0)
        } else {
            0L
        }
        val dir = src.read(base, 12)
        val version = dir.u32(0)
        if (version != 0x00010000L && version != 0x4F54544FL && version != 0x74727565L) throw FontFormatException("Not a TrueType or OpenType font.")
        val numTables = dir.u16(4)
        if (numTables > MAX_TABLES) throw FontFormatException("Too many tables.")
        val records = src.read(base + 12, numTables * 16)
        val tables = HashMap<String, Pair<Long, Int>>()
        for (i in 0 until numTables) {
            if (records.size < i * 16 + 16) break
            val tag = String(records, i * 16, 4, Charsets.ISO_8859_1)
            tables[tag] = records.u32(i * 16 + 8) to records.u32(i * 16 + 12).toInt()
        }

        fun table(tag: String): ByteArray? = tables[tag]?.let { (off, len) -> if (len in 1..MAX_TABLE_BYTES) src.read(off, len) else null }

        val names = table("name")?.let(::parseNames).orEmpty()
        val os2 = table("OS/2")
        val weight = os2?.takeIf { it.size >= 6 }?.u16(4)?.coerceIn(1, 1000) ?: 400
        val fsSelection = os2?.takeIf { it.size >= 64 }?.u16(62) ?: 0
        val axes = table("fvar")?.let { parseAxes(it, names) }.orEmpty()
        val family = names[16] ?: names[1] ?: throw FontFormatException("Font has no family name.")
        val style = names[17] ?: names[2] ?: styleName(weight, fsSelection and 0x201 != 0)
        return FontFace(
            family = family,
            style = style,
            path = path,
            index = index,
            weight = weight,
            italic = fsSelection and 0x201 != 0 || style.contains("italic", ignoreCase = true) || style.contains("oblique", ignoreCase = true),
            axes = axes,
        )
    }

    /** "Bold Italic" etc. for faces (or variable instances) that have no subfamily name. */
    fun styleName(weight: Int, italic: Boolean): String {
        val w = when {
            weight < 150 -> "Thin"
            weight < 250 -> "ExtraLight"
            weight < 350 -> "Light"
            weight < 450 -> "Regular"
            weight < 550 -> "Medium"
            weight < 650 -> "SemiBold"
            weight < 750 -> "Bold"
            weight < 850 -> "ExtraBold"
            else -> "Black"
        }
        return when {
            !italic -> w
            w == "Regular" -> "Italic"
            else -> "$w Italic"
        }
    }

    /** nameID → string, preferring Windows English, then any Windows, then Mac Roman. */
    private fun parseNames(t: ByteArray): Map<Int, String> {
        if (t.size < 6) return emptyMap()
        val count = t.u16(2)
        val strings = t.u16(4)
        val best = HashMap<Int, Pair<Int, String>>() // nameID → (rank, value)
        for (i in 0 until count) {
            val r = 6 + i * 12
            if (r + 12 > t.size) break
            val platform = t.u16(r)
            val encoding = t.u16(r + 2)
            val language = t.u16(r + 4)
            val id = t.u16(r + 6)
            if (id !in setOf(1, 2, 16, 17) && id < 256) continue
            val len = t.u16(r + 8)
            val off = strings + t.u16(r + 10)
            if (off + len > t.size) continue
            val (rank, value) = when {
                platform == 3 && (encoding == 1 || encoding == 10) ->
                    (if (language == 0x0409) 0 else 1) to String(t, off, len, Charsets.UTF_16BE)

                platform == 0 -> 2 to String(t, off, len, Charsets.UTF_16BE)

                platform == 1 && encoding == 0 -> 3 to String(t, off, len, Charsets.ISO_8859_1)

                else -> continue
            }
            val v = value.trim()
            if (v.isEmpty()) continue
            if (best[id]?.first?.let { it <= rank } != true) best[id] = rank to v
        }
        return best.mapValues { it.value.second }
    }

    private fun parseAxes(t: ByteArray, names: Map<Int, String>): List<FontAxis> {
        if (t.size < 16) return emptyList()
        val axesOffset = t.u16(4)
        val count = t.u16(8)
        val size = t.u16(10)
        if (size < 20) return emptyList()
        return (0 until count).mapNotNull { i ->
            val a = axesOffset + i * size
            if (a + 20 > t.size) return@mapNotNull null
            val tag = String(t, a, 4, Charsets.ISO_8859_1)
            val min = t.fixed(a + 4)
            val def = t.fixed(a + 8)
            val max = t.fixed(a + 12)
            val hidden = t.u16(a + 16) and 1 != 0
            if (hidden || min > max) return@mapNotNull null
            FontAxis(tag, names[t.u16(a + 18)] ?: AxisNames[tag] ?: tag, min, def.coerceIn(min, max), max)
        }
    }

    private val AxisNames = mapOf("wght" to "Weight", "wdth" to "Width", "slnt" to "Slant", "ital" to "Italic", "opsz" to "Optical Size")

    private fun ByteArray.u16(i: Int): Int = ((this[i].toInt() and 0xFF) shl 8) or (this[i + 1].toInt() and 0xFF)

    private fun ByteArray.u32(i: Int): Long = (u16(i).toLong() shl 16) or u16(i + 2).toLong()

    private fun ByteArray.fixed(i: Int): Float = u32(i).toInt() / 65536f
}
