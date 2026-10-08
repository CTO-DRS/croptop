package app.croptop.core.model

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ViewportStateTest {
    private val base = ViewportState(zoom = 0.5f, panX = 40f, panY = 10f, viewWidth = 1600, viewHeight = 1000)

    private fun near(a: Float, b: Float) = assertTrue(abs(a - b) < 1e-2f, "$a != $b")

    @Test
    fun `doc to view round trips with rotation and flip`() {
        val vp = base.copy(rotationDegrees = 33f, flipHorizontal = true)
        val p = DocPoint(1234.5f, 678.25f)
        val back = vp.viewToDoc(vp.docToView(p))
        near(p.x, back.x)
        near(p.y, back.y)
    }

    @Test
    fun `zoom at keeps anchor fixed`() {
        val anchor = ViewPoint(700f, 420f)
        val before = base.viewToDoc(anchor)
        val zoomed = base.zoomAt(anchor, 3f)
        val after = zoomed.viewToDoc(anchor)
        near(before.x, after.x)
        near(before.y, after.y)
        near(1.5f, zoomed.zoom)
    }

    @Test
    fun `zoom clamps to 1 to 6400 percent`() {
        assertEquals(ViewportState.MAX_ZOOM, base.zoomAt(ViewPoint(0f, 0f), 1e6f).zoom)
        assertEquals(ViewportState.MIN_ZOOM, base.zoomAt(ViewPoint(0f, 0f), 1e-6f).zoom)
    }

    @Test
    fun `fit centers document`() {
        val fit = base.fit(DocSize(6000, 4000), marginPx = 0f)
        near(1000f / 4000f, fit.zoom)
        val center = fit.docToView(DocPoint(3000f, 2000f))
        near(800f, center.x)
        near(500f, center.y)
    }

    @Test
    fun `step zoom walks presets`() {
        val a = ViewPoint(0f, 0f)
        val vp = base.copy(zoom = 1f)
        assertEquals(2f, vp.stepZoom(a, zoomIn = true).zoom)
        assertEquals(0.6667f, vp.stepZoom(a, zoomIn = false).zoom)
    }

    @Test
    fun `rotation normalizes and keeps anchor`() {
        val anchor = ViewPoint(800f, 500f)
        val before = base.viewToDoc(anchor)
        val r = base.rotateAt(anchor, -30f)
        assertEquals(330f, r.rotationDegrees)
        val after = r.viewToDoc(anchor)
        near(before.x, after.x)
        near(before.y, after.y)
    }
}
