package app.croptop.core.model

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ColorMathTest {
    private fun near(a: Float, b: Float, eps: Float = 0.01f) = assertTrue(abs(a - b) < eps, "$a != $b")

    @Test
    fun `hsv round trips across hues`() {
        listOf(0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFF4C8DFF, 0xFF808080, 0xFFFFFFFF, 0xFF000000, 0xFFB23F10).forEach { argb ->
            val c = RgbaColor.fromArgb(argb.toInt())
            assertEquals(c.toArgb(), ColorMath.fromHsv(ColorMath.toHsv(c)).toArgb(), "%08X".format(argb))
        }
        near(220f, ColorMath.toHsv(RgbaColor.fromArgb(0xFF3366CC.toInt())).h, 1f)
    }

    @Test
    fun `lab reference values`() {
        val white = ColorMath.toLab(RgbaColor.White)
        near(100f, white.l, 0.1f)
        near(0f, white.a, 0.2f)
        val red = ColorMath.toLab(RgbaColor(1f, 0f, 0f))
        near(53.24f, red.l, 0.3f)
        near(80.09f, red.a, 0.5f)
        near(67.2f, red.b, 0.5f)
    }

    @Test
    fun `complementary of red is cyan`() {
        val cyan = ColorMath.harmony(RgbaColor(1f, 0f, 0f), ColorMath.Harmony.COMPLEMENTARY).single()
        assertEquals("00FFFF", cyan.hex)
        assertEquals(2, ColorMath.harmony(RgbaColor(1f, 0f, 0f), ColorMath.Harmony.TRIADIC).size)
    }
}
