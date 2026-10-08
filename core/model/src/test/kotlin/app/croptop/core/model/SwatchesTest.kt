package app.croptop.core.model

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SwatchesTest {
    private fun near(a: RgbaColor, b: RgbaColor, tol: Float = 0.01f) =
        assertTrue(abs(a.r - b.r) < tol && abs(a.g - b.g) < tol && abs(a.b - b.b) < tol, "$a != $b")

    @Test
    fun `ase round trip keeps names and colors`() {
        val sw = listOf(Swatch("Brand red", RgbaColor(0.9f, 0.1f, 0.2f)), Swatch("Ink", RgbaColor(0.05f, 0.05f, 0.1f)))
        val back = SwatchIo.parse(SwatchIo.writeAse(sw), "x.ase")
        assertEquals(sw.map { it.name }, back.map { it.name })
        sw.zip(back).forEach { (a, b) -> near(a.color, b.color) }
    }

    @Test
    fun `aco v1 plus v2 names, rgb hsb cmyk gray`() {
        val v1 = ByteBuffer.allocate(2 + 2 + 4 * 10)
        v1.putShort(1).putShort(4)
        v1.putShort(0).putShort(0xFFFF.toShort()).putShort(0).putShort(0).putShort(0) // RGB red
        v1.putShort(1).putShort(0x5555).putShort(0xFFFF.toShort()).putShort(0xFFFF.toShort()).putShort(0) // HSB 120° green
        v1.putShort(2).putShort(0xFFFF.toShort()).putShort(0xFFFF.toShort()).putShort(0xFFFF.toShort()).putShort(0) // CMYK: K=100%
        v1.putShort(8).putShort(5000).putShort(0).putShort(0).putShort(0) // 50% gray
        val names = listOf("Red", "Green", "Black", "Mid")
        val v2 = ByteBuffer.allocate(4 + names.sumOf { 10 + 4 + (it.length + 1) * 2 })
        v2.putShort(2).putShort(4)
        val colorWords = listOf(
            listOf(0, 0xFFFF, 0, 0, 0),
            listOf(1, 0x5555, 0xFFFF, 0xFFFF, 0),
            listOf(2, 0xFFFF, 0xFFFF, 0xFFFF, 0),
            listOf(8, 5000, 0, 0, 0),
        )
        names.forEachIndexed { i, n ->
            colorWords[i].forEach { v2.putShort(it.toShort()) }
            v2.putShort(0).putShort((n.length + 1).toShort())
            n.forEach { v2.putChar(it) }
            v2.putChar('\u0000')
        }
        val parsed = SwatchIo.parse(v1.array() + v2.array(), "palette.aco")
        assertEquals(names, parsed.map { it.name })
        near(parsed[0].color, RgbaColor(1f, 0f, 0f))
        near(parsed[1].color, RgbaColor(0f, 1f, 0f))
        near(parsed[2].color, RgbaColor(0f, 0f, 0f))
        near(parsed[3].color, RgbaColor(0.5f, 0.5f, 0.5f))
    }

    @Test
    fun `lab round trip and garbage rejected`() {
        val c = RgbaColor(0.2f, 0.6f, 0.3f)
        near(c, ColorMath.fromLab(ColorMath.toLab(c)))
        assertFailsWith<SwatchFormatException> { SwatchIo.parse(byteArrayOf(1, 2, 3, 4, 5), "x.png") }
        assertFailsWith<SwatchFormatException> { SwatchIo.parse(byteArrayOf(0, 1, 0, 9), "x.aco") }
    }
}
