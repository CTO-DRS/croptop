package app.croptop.feature.develop

import app.croptop.core.model.WbPreset
import app.croptop.core.model.WhiteBalance
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WhiteBalancePickerTest {
    private fun encode(linear: Double): Int {
        val v = if (linear <= 0.0031308) linear * 12.92 else 1.055 * linear.pow(1 / 2.4) - 0.055
        return (v.coerceIn(0.0, 1.0) * 255.0 + 0.5).toInt()
    }

    private fun pixel(r: Double, g: Double, b: Double) = (0xff shl 24) or (encode(r) shl 16) or (encode(g) shl 8) or encode(b)

    @Test
    fun `a gray card rendered with a known balance solves back to that balance`() {
        val truth = WhiteBalance(WbPreset.CUSTOM, 3600f, 20f)
        val g = WhiteBalancePicker.gains(3600.0, 20.0)
        // A neutral gray after the as-shot preview is shifted by the inverse of the unknown true balance.
        val gray = 0.3
        val shown = pixel(gray / g[0], gray / g[1], gray / g[2])
        val solved = assertNotNull(WhiteBalancePicker.solve(shown, WhiteBalance()))
        assertEquals(truth.temperature, solved.temperature, 120f)
        assertEquals(truth.tint, solved.tint, 6f)
        assertEquals(WbPreset.CUSTOM, solved.preset)
    }

    @Test
    fun `the solved balance equalizes the channels of any warm or cool pixel`() {
        for ((r, g, b) in listOf(Triple(0.42, 0.30, 0.18), Triple(0.18, 0.28, 0.40), Triple(0.30, 0.34, 0.30))) {
            val solved = assertNotNull(WhiteBalancePicker.solve(pixel(r, g, b), WhiteBalance()))
            val k = WhiteBalancePicker.gains(solved.temperature.toDouble(), solved.tint.toDouble())
            val out = doubleArrayOf(r * k[0], g * k[1], b * k[2])
            assertEquals(out[0], out[1], out[1] * 0.06)
            assertEquals(out[2], out[1], out[1] * 0.06)
        }
    }

    @Test
    fun `black and clipped pixels are refused`() {
        assertNull(WhiteBalancePicker.solve(0xff000000.toInt(), WhiteBalance()))
        assertNull(WhiteBalancePicker.solve(0xffffffff.toInt(), WhiteBalance()))
    }

    @Test
    fun `gains match the engine at the 5500 K reference`() {
        val g = WhiteBalancePicker.gains(5500.0, 0.0)
        assertTrue(g.all { it in 0.9..1.1 })
    }
}
