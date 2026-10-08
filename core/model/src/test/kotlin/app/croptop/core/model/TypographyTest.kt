package app.croptop.core.model

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TypographyTest {
    /** Builds a tiny SFNT with name, OS/2 and (optionally) fvar tables. */
    private fun font(
        names: Map<Int, String>,
        weight: Int = 400,
        italic: Boolean = false,
        axes: List<Triple<String, FloatArray, Int>> = emptyList(),
    ): ByteArray {
        val name = ByteArrayOutputStream()
        val strings = ByteArrayOutputStream()
        val recs = names.entries.sortedBy { it.key }
        fun u16(o: ByteArrayOutputStream, v: Int) {
            o.write(v shr 8 and 0xFF)
            o.write(v and 0xFF)
        }
        u16(name, 0)
        u16(name, recs.size)
        u16(name, 6 + recs.size * 12)
        recs.forEach { (id, s) ->
            val b = s.toByteArray(Charsets.UTF_16BE)
            u16(name, 3)
            u16(name, 1)
            u16(name, 0x0409)
            u16(name, id)
            u16(name, b.size)
            u16(name, strings.size())
            strings.write(b)
        }
        name.write(strings.toByteArray())
        val os2 = ByteBuffer.allocate(64).putShort(4, weight.toShort()).putShort(62, (if (italic) 1 else 0).toShort()).array()
        val tables = mutableListOf("name" to name.toByteArray(), "OS/2" to os2)
        if (axes.isNotEmpty()) {
            val f = ByteBuffer.allocate(16 + axes.size * 20)
            f.putShort(0, 1).putShort(4, 16).putShort(8, axes.size.toShort()).putShort(10, 20)
            axes.forEachIndexed { i, (tag, v, nameId) ->
                val a = 16 + i * 20
                tag.toByteArray(Charsets.ISO_8859_1).forEachIndexed { k, b -> f.put(a + k, b) }
                f.putInt(a + 4, (v[0] * 65536).toInt()).putInt(a + 8, (v[1] * 65536).toInt()).putInt(a + 12, (v[2] * 65536).toInt())
                f.putShort(a + 18, nameId.toShort())
            }
            tables += "fvar" to f.array()
        }
        val out = ByteBuffer.allocate(12 + tables.size * 16 + tables.sumOf { it.second.size })
        out.putInt(0x00010000).putShort(tables.size.toShort())
        var offset = 12 + tables.size * 16
        tables.forEachIndexed { i, (tag, data) ->
            val r = 12 + i * 16
            tag.toByteArray(Charsets.ISO_8859_1).forEachIndexed { k, b -> out.put(r + k, b) }
            out.putInt(r + 8, offset).putInt(r + 12, data.size)
            data.forEachIndexed { k, b -> out.put(offset + k, b) }
            offset += data.size
        }
        return out.array()
    }

    private fun src(b: ByteArray) = FontBytes { off, len ->
        b.copyOfRange(off.toInt().coerceAtMost(b.size), (off + len).toInt().coerceAtMost(b.size))
    }

    @Test
    fun readsNamesWeightAndItalic() {
        val face = FontFile.read(src(font(mapOf(1 to "Inter", 2 to "Bold Italic"), weight = 700, italic = true)), "/f/Inter-BI.ttf")
        assertEquals("Inter", face.family)
        assertEquals("Bold Italic", face.style)
        assertEquals(700, face.weight)
        assertTrue(face.italic)
        assertFalse(face.isVariable)
    }

    @Test
    fun typographicNamesWinAndAxesAreRead() {
        val bytes = font(
            mapOf(1 to "Recursive Sans Linear", 2 to "Regular", 16 to "Recursive", 17 to "Linear Regular", 256 to "Casual"),
            axes = listOf(Triple("wght", floatArrayOf(300f, 400f, 1000f), 0), Triple("CASL", floatArrayOf(0f, 0f, 1f), 256)),
        )
        val face = FontFile.read(src(bytes), "/r.ttf")
        assertEquals("Recursive", face.family)
        assertEquals("Linear Regular", face.style)
        assertEquals(listOf(FontAxis("wght", "Weight", 300f, 400f, 1000f), FontAxis("CASL", "Casual", 0f, 0f, 1f)), face.axes)
    }

    @Test
    fun readsCollectionsAndRejectsGarbage() {
        val a = font(mapOf(1 to "Noto Sans CJK JP", 2 to "Regular"))
        val b = font(mapOf(1 to "Noto Sans CJK KR", 2 to "Regular"))

        // TTC: header + two offsets; each face's directory offsets are file-absolute, so shift them.
        fun shifted(f: ByteArray, by: Int): ByteArray {
            val bb = ByteBuffer.wrap(f.copyOf())
            val n = bb.getShort(4).toInt()
            repeat(n) { i -> bb.putInt(12 + i * 16 + 8, bb.getInt(12 + i * 16 + 8) + by) }
            return bb.array()
        }
        val head = 20
        val ttc = ByteBuffer.allocate(head + a.size + b.size)
            .putInt(0x74746366).putInt(0x00010000).putInt(2).putInt(head).putInt(head + a.size)
            .put(shifted(a, head)).put(shifted(b, head + a.size)).array()
        assertEquals(2, FontFile.faceCount(src(ttc)))
        assertEquals("Noto Sans CJK KR", FontFile.read(src(ttc), "/n.ttc", 1).family)
        assertFailsWith<FontFormatException> { FontFile.read(src(ttc), "/n.ttc", 2) }
        assertFailsWith<FontFormatException> { FontFile.read(src(ByteArray(40) { 7 }), "/x") }
        assertFailsWith<FontFormatException> { FontFile.read(src(ByteArray(3)), "/x") }
    }

    @Test
    fun hostFontsParseWithoutErrors() {
        // Smoke check over real files when the host has them (Linux CI images usually do).
        val files = listOf("/usr/share/fonts", "/system/fonts").map(::File).filter { it.isDirectory }
            .flatMap { d -> d.walk().filter { it.extension.lowercase() in setOf("ttf", "otf", "ttc") }.take(200).toList() }
        files.forEach { f ->
            RandomAccessFile(f, "r").use { raf ->
                val bytes = FontBytes { off, len ->
                    val n = (raf.length() - off).coerceIn(0, len.toLong()).toInt()
                    ByteArray(n).also {
                        raf.seek(off)
                        raf.readFully(it)
                    }
                }
                repeat(FontFile.faceCount(bytes)) { i ->
                    val face = runCatching {
                        FontFile.read(bytes, f.path, i)
                    }.getOrElse { e -> if (e is FontFormatException) return@repeat else throw e }
                    assertTrue(face.family.isNotBlank(), f.path)
                }
            }
        }
    }

    @Test
    fun familiesGroupAndSearch() {
        val faces = listOf(
            FontFace("Roboto", "Bold", "/a", weight = 700),
            FontFace("Roboto", "Italic", "/b", italic = true),
            FontFace("Roboto", "Regular", "/c"),
            FontFace("Noto Serif", "Regular", "/d"),
            FontFace("Source Code Pro", "Regular", "/e"),
        )
        val families = FontFamily.group(faces)
        assertEquals(listOf("Noto Serif", "Roboto", "Source Code Pro"), families.map { it.name })
        assertEquals(listOf("Regular", "Bold", "Italic"), families[1].faces.map { it.style })
        assertEquals("Regular", families[1].face("Black").style)
        // Prefix matches ("Ro…") rank above substring matches ("…Pro").
        assertEquals(listOf("Roboto", "Source Code Pro"), families.search("ro").map { it.name })
        assertEquals(listOf("Source Code Pro", "Noto Serif", "Roboto"), families.search("", favorites = setOf("Source Code Pro")).map { it.name })
        assertEquals(listOf("Noto Serif"), families.search("", favorites = setOf("Noto Serif"), favoritesOnly = true).map { it.name })
    }

    @Test
    fun stylesClampAndFeaturesToggle() {
        val s = CharacterStyle(sizePt = 5000f, tracking = -4000, horizontalScale = -5f).normalized()
        assertEquals(CharacterStyle.MAX_SIZE, s.sizePt)
        assertEquals(-CharacterStyle.MAX_TRACKING, s.tracking)
        assertEquals(0f, s.horizontalScale)
        assertEquals(28.8f, CharacterStyle(sizePt = 24f).effectiveLeadingPt, 1e-4f)
        val noLiga = OpenTypeFeature.LIGATURES.toggle(CharacterStyle())
        assertFalse(OpenTypeFeature.LIGATURES.isOn(noLiga))
        assertTrue(OpenTypeFeature.LIGATURES.isOn(OpenTypeFeature.LIGATURES.toggle(noLiga)))
        val frac = OpenTypeFeature.FRACTIONS.toggle(CharacterStyle())
        assertEquals(setOf("frac"), frac.features)
        assertEquals("Bold Italic", FontFile.styleName(700, true))
        assertEquals("Italic", FontFile.styleName(400, true))
        assertEquals(-40f, ParagraphStyle(spaceBeforePt = -40f, indentLeftPt = -40f).normalized().indentLeftPt)
        assertEquals(0f, ParagraphStyle(spaceBeforePt = -40f).normalized().spaceBeforePt)
    }
}
