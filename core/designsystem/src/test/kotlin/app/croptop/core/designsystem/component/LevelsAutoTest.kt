package app.croptop.core.designsystem.component

import app.croptop.core.model.Levels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class LevelsAutoTest {
    @Test
    fun `auto trims histogram tails resets gamma and preserves output range`() {
        val histogram = FloatArray(256)
        histogram[2] = 10f
        for (index in 50..200) histogram[index] = 100f
        histogram[250] = 10f
        val original = Levels(gamma = 1.6f, outBlack = 0.1f, outWhite = 0.9f)

        val result = original.autoFromHistogram(histogram)

        assertEquals(50f / 255f, result.inBlack)
        assertEquals(200f / 255f, result.inWhite)
        assertEquals(1f, result.gamma)
        assertEquals(original.outBlack, result.outBlack)
        assertEquals(original.outWhite, result.outWhite)
    }

    @Test
    fun `auto leaves empty and single tone histograms unchanged`() {
        val original = Levels(gamma = 1.8f)

        assertSame(original, original.autoFromHistogram(null))
        assertSame(original, original.autoFromHistogram(FloatArray(256)))
        val singleTone = FloatArray(256).also { it[80] = 10f }
        assertSame(original, original.autoFromHistogram(singleTone))
    }
}
