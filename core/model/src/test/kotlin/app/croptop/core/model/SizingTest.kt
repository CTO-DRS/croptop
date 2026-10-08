package app.croptop.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SizingTest {
    private val img = ImageSizing(DocSize(6000, 4000), 300f)

    @Test
    fun `units round trip including percent`() {
        assertEquals(3000, toPixels(50f, SizeUnit.PERCENT, 300f, basePx = 6000))
        assertEquals(50f, fromPixels(3000, SizeUnit.PERCENT, 300f, basePx = 6000))
        assertEquals(600, toPixels(2f, SizeUnit.IN, 300f))
        assertEquals(2.54f, fromPixels(300, SizeUnit.CM, 300f), 1e-4f)
    }

    @Test
    fun `linked resize keeps aspect, unlinked does not`() {
        assertEquals(DocSize(3000, 2000), img.withWidth(3000f, SizeUnit.PX).size)
        assertEquals(DocSize(1500, 1000), img.withHeight(25f, SizeUnit.PERCENT).size)
        assertEquals(DocSize(3000, 4000), img.copy(linked = false).withWidth(3000f, SizeUnit.PX).size)
    }

    @Test
    fun `resample off trades resolution for print size, pixels fixed`() {
        val s = img.copy(resample = false).withWidth(10f, SizeUnit.IN)
        assertEquals(DocSize(6000, 4000), s.size)
        assertEquals(600f, s.ppi, 0.01f)
        val r = img.withPpi(150f) // resample on: same print size, half the pixels
        assertEquals(DocSize(3000, 2000), r.size)
        assertTrue(r.changed)
        assertFalse(r.reset().changed)
    }

    @Test
    fun `automatic resample picks lanczos down, bicubic up`() {
        assertEquals(Resample.LANCZOS, Resample.AUTOMATIC.resolve(DocSize(100, 100), DocSize(50, 50)))
        assertEquals(Resample.BICUBIC, Resample.AUTOMATIC.resolve(DocSize(100, 100), DocSize(200, 200)))
        assertEquals(Resample.NEAREST, Resample.NEAREST.resolve(DocSize(100, 100), DocSize(50, 50)))
    }

    @Test
    fun `canvas anchor offsets and clipping`() {
        val c = CanvasResize(DocSize(100, 100), DocSize(200, 150), Anchor.BOTTOM_RIGHT)
        assertEquals(100, c.offsetX)
        assertEquals(50, c.offsetY)
        assertFalse(c.clips)
        val shrink = CanvasResize.relative(DocSize(100, 100), -20, 0, Anchor.CENTER)
        assertEquals(DocSize(80, 100), shrink.size)
        assertEquals(-10, shrink.offsetX)
        assertTrue(shrink.clips)
    }
}
