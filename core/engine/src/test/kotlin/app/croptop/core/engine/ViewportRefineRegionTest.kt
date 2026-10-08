package app.croptop.core.engine

import app.croptop.core.model.ViewportState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ViewportRefineRegionTest {
    private fun view(zoom: Float, panX: Float = 0f, panY: Float = 0f, rotation: Float = 0f, w: Int = 1000, h: Int = 800) =
        ViewportState(zoom = zoom, panX = panX, panY = panY, rotationDegrees = rotation, viewWidth = w, viewHeight = h)

    @Test
    fun `nothing is re-rendered while the preview already resolves the view`() {
        // A 6000 px wide document has a 2048 px preview: 0.34 screen px per document px is as sharp as it gets.
        assertNull(viewportRefineRegion(view(0.34f), 6000, 4000, 2048f / 6000f))
        assertNull(viewportRefineRegion(view(0.30f), 6000, 4000, 2048f / 6000f))
        // A document whose preview is the document itself needs no refinement at any zoom.
        assertNull(viewportRefineRegion(view(4f), 1500, 1000, 1f))
    }

    @Test
    fun `zoomed in, the visible part plus a margin is chosen and kept inside the document`() {
        // Zoom 1 with the document's top-left at the view's top-left: the view shows 1000×800 document pixels.
        val r = assertNotNull(viewportRefineRegion(view(1f), 6000, 4000, 2048f / 6000f))
        assertEquals(0, r.left)
        assertEquals(0, r.top)
        assertTrue(r.right in 1100..1101 && r.bottom in 880..881, "10% margin on the right and bottom: $r")
        // Panned so the view sits in the middle: a margin on every side.
        val middle = assertNotNull(viewportRefineRegion(view(1f, panX = -2000f, panY = -1500f), 6000, 4000, 2048f / 6000f))
        assertTrue(middle.left in 1890..1900 && middle.top in 1410..1420, "$middle")
        assertTrue(middle.width in 1190..1210 && middle.height in 950..970, "$middle")
        // Panned off the end: clamped to the document.
        val end = assertNotNull(viewportRefineRegion(view(1f, panX = -5500f, panY = -3600f), 6000, 4000, 2048f / 6000f))
        assertEquals(6000, end.right)
        assertEquals(4000, end.bottom)
    }

    @Test
    fun `too large a region is cut down around its centre to the pixel budget`() {
        // At zoom 0.5 the 1000×800 view shows 2000×1600 document pixels; a budget of 1 MP keeps the middle ~1 MP of that.
        val r = assertNotNull(viewportRefineRegion(view(0.5f), 6000, 4000, 0.2f, maxPixels = 1_000_000))
        assertTrue(r.width.toLong() * r.height <= 1_050_000, "$r")
        val full = assertNotNull(viewportRefineRegion(view(0.5f), 6000, 4000, 0.2f, maxPixels = 100_000_000))
        assertTrue(full.width > r.width && full.height > r.height)
        assertEquals((full.left + full.right) / 2f, (r.left + r.right) / 2f, 3f)
    }

    @Test
    fun `a rotated view still covers everything that is visible`() {
        val straight = assertNotNull(viewportRefineRegion(view(2f, panX = -1000f, panY = -600f), 6000, 4000, 0.3f))
        val turned = assertNotNull(viewportRefineRegion(view(2f, panX = -1000f, panY = -600f, rotation = 30f), 6000, 4000, 0.3f))
        assertTrue(turned.width.toLong() * turned.height >= straight.width.toLong() * straight.height, "the turned view's bounding box is at least as big")
    }

    @Test
    fun `unusable views and documents give nothing`() {
        assertNull(viewportRefineRegion(ViewportState(zoom = 2f), 6000, 4000, 0.3f), "a view with no size yet")
        assertNull(viewportRefineRegion(view(2f), 0, 4000, 0.3f))
        assertNull(viewportRefineRegion(view(Float.NaN), 6000, 4000, 0.3f))
    }
}
