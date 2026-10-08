package app.croptop.core.model

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdjustmentsTest {
    private fun near(a: Float, b: Float, eps: Float = 1e-3f) = assertTrue(abs(a - b) < eps, "$a != $b")

    @Test
    fun `identity curve is linear`() {
        listOf(0f, 0.25f, 0.5f, 0.9f, 1f).forEach { near(it, Curve.Identity.evaluate(it)) }
        assertTrue(Curve.Identity.isIdentity)
    }

    @Test
    fun `s curve passes through points and never overshoots`() {
        val (c, _) = Curve.Identity.withPoint(CurvePoint(0.25f, 0.15f))
        val (s, i) = c.withPoint(CurvePoint(0.75f, 0.85f))
        assertEquals(2, i)
        near(0.15f, s.evaluate(0.25f))
        near(0.85f, s.evaluate(0.75f))
        var prev = -1f
        for (k in 0..100) {
            val y = s.evaluate(k / 100f)
            assertTrue(y in 0f..1f && y >= prev - 1e-4f, "monotone at $k")
            prev = y
        }
    }

    @Test
    fun `move keeps order and endpoints stay`() {
        val (c, i) = Curve.Identity.withPoint(CurvePoint(0.5f, 0.5f))
        val moved = c.moved(i, CurvePoint(1.2f, 0.7f))
        assertTrue(moved.points[1].x < moved.points[2].x)
        assertEquals(c, c.without(0))
        assertEquals(2, c.without(1).points.size)
    }

    @Test
    fun `levels maps black white and gamma`() {
        val l = Levels(inBlack = 0.1f, inWhite = 0.9f)
        near(0f, l.apply(0.05f))
        near(1f, l.apply(0.95f))
        near(0.5f, l.apply(0.5f))
        val g = Levels().withGrayPosition(0.25f)
        near(0.25f, g.grayPosition, 1e-2f)
        assertTrue(g.apply(0.25f) in 0.45f..0.55f)
    }

    @Test
    fun `gradient interpolates between stops`() {
        val g = ColorGradient.BlackToWhite
        near(0.5f, g.colorAt(0.5f).r)
        assertEquals(RgbaColor.Black, g.colorAt(-1f))
    }

    @Test
    fun `hue range weights wrap around red`() {
        val reds = HueChannel.REDS.defaultRange!!
        kotlin.test.assertEquals(1f, reds.weight(0f))
        kotlin.test.assertEquals(1f, reds.weight(350f))
        kotlin.test.assertEquals(0.5f, reds.weight(30f), 1e-4f)
        kotlin.test.assertEquals(0.5f, reds.weight(330f), 1e-4f)
        kotlin.test.assertEquals(0f, reds.weight(90f))
        kotlin.test.assertEquals(0f, reds.center, 1e-4f)
        kotlin.test.assertEquals(60f, reds.rotate(60f).center, 1e-4f)
        kotlin.test.assertEquals(HueChannel.YELLOWS.defaultRange, reds.rotate(60f))
    }

    @Test
    fun `hue saturation and color balance edits`() {
        val hs = HueSaturation().withShift(HueChannel.BLUES, HueShift(saturation = -40f)).withShift(HueChannel.MASTER, HueShift(hue = 10f))
        kotlin.test.assertEquals(-40f, hs.shift(HueChannel.BLUES).saturation)
        kotlin.test.assertEquals(10f, hs.master.hue)
        kotlin.test.assertEquals(HueChannel.BLUES.defaultRange, hs.range(HueChannel.BLUES))
        val cb = ColorBalance().with(ToneRange.SHADOWS, ColorBalanceShift(cyanRed = 20f))
        kotlin.test.assertEquals(20f, cb.of(ToneRange.SHADOWS).cyanRed)
        kotlin.test.assertEquals(0f, cb.of(ToneRange.MIDTONES).cyanRed)
    }
}
