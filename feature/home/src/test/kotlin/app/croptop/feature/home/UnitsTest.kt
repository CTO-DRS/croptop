package app.croptop.feature.home

import app.croptop.core.model.MAX_DIMENSION
import app.croptop.core.model.SizeUnit
import app.croptop.core.model.fromPixels
import app.croptop.core.model.toPixels
import kotlin.test.Test
import kotlin.test.assertEquals

class UnitsTest {
    @Test
    fun `unit conversions round trip at 300 ppi`() {
        assertEquals(2480, toPixels(210f, SizeUnit.MM, 300f)) // A4 width
        assertEquals(3508, toPixels(29.7f, SizeUnit.CM, 300f)) // A4 height
        assertEquals(1800, toPixels(6f, SizeUnit.IN, 300f))
        assertEquals(6f, fromPixels(1800, SizeUnit.IN, 300f))
        assertEquals(MAX_DIMENSION, toPixels(1e9f, SizeUnit.PX, 72f))
        assertEquals(1, toPixels(0f, SizeUnit.PX, 72f))
    }
}
