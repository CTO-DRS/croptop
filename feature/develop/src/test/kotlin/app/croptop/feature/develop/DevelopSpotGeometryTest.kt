package app.croptop.feature.develop

import app.croptop.core.model.CropSettings
import app.croptop.core.model.Spot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DevelopSpotGeometryTest {
    @Test
    fun `crop mapping round trips and a straightened crop is not placeable`() {
        val crop = CropSettings(0.1f, 0.2f, 0.9f, 0.8f)
        val shown = SpotGeometry.toFrame(crop, 0.5f, 0.5f)
        assertEquals(0.5f, shown.x, 1e-5f)
        assertEquals(0.5f, shown.y, 1e-5f)
        val back = SpotGeometry.fromFrame(crop, 0.25f, 0.75f)
        assertEquals(0.3f, back.x, 1e-5f)
        assertEquals(0.65f, back.y, 1e-5f)
        assertTrue(SpotGeometry.placeable(crop))
        assertTrue(!SpotGeometry.placeable(crop.copy(angle = 2f)))
    }

    @Test
    fun `radius is a share of the longer edge in both axes`() {
        val wide = SpotGeometry.radii(0.1f, 2f)
        assertEquals(0.1f, wide.x, 1e-5f)
        assertEquals(0.2f, wide.y, 1e-5f)
        val tall = SpotGeometry.radii(0.1f, 0.5f)
        assertEquals(0.2f, tall.x, 1e-5f)
        assertEquals(0.1f, tall.y, 1e-5f)
    }

    @Test
    fun `the default source goes right when there is room and left at the right edge`() {
        val right = SpotGeometry.defaultSource(0.3f, 0.5f, 0.02f, 1f)
        assertEquals(0.36f, right.x, 1e-5f)
        val left = SpotGeometry.defaultSource(0.98f, 0.5f, 0.02f, 1f)
        assertEquals(0.92f, left.x, 1e-5f)
        assertEquals(0.5f, left.y, 1e-5f)
    }

    @Test
    fun `hit testing finds the target and the source circle and prefers the last spot`() {
        val a = Spot(0.3f, 0.3f, 0.05f, 0.6f, 0.3f)
        val b = Spot(0.3f, 0.3f, 0.05f, 0.8f, 0.8f)
        assertEquals(1 to false, SpotGeometry.hitSpot(listOf(a, b), 0.3f, 0.3f, 1f))
        assertEquals(0 to true, SpotGeometry.hitSpot(listOf(a, b), 0.61f, 0.3f, 1f))
        assertNull(SpotGeometry.hitSpot(listOf(a, b), 0.5f, 0.9f, 1f))
    }
}
