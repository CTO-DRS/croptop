package app.croptop.feature.develop

import app.croptop.core.model.CropSettings
import kotlin.test.Test
import kotlin.test.assertEquals

class DevelopCropDragTest {
    private val crop = CropSettings(0.2f, 0.2f, 0.8f, 0.8f)

    @Test
    fun `corners edges and the inside are told apart`() {
        assertEquals(CropGrab.TOP_LEFT, CropDrag.hit(crop, 0.21f, 0.19f, 0.05f, 0.05f))
        assertEquals(CropGrab.BOTTOM_RIGHT, CropDrag.hit(crop, 0.8f, 0.82f, 0.05f, 0.05f))
        assertEquals(CropGrab.LEFT, CropDrag.hit(crop, 0.2f, 0.5f, 0.05f, 0.05f))
        assertEquals(CropGrab.TOP, CropDrag.hit(crop, 0.5f, 0.21f, 0.05f, 0.05f))
        assertEquals(CropGrab.MOVE, CropDrag.hit(crop, 0.5f, 0.5f, 0.05f, 0.05f))
        assertEquals(CropGrab.NONE, CropDrag.hit(crop, 0.05f, 0.95f, 0.05f, 0.05f))
    }

    @Test
    fun `moving keeps the frame size and stops at the image edge`() {
        val moved = CropDrag.drag(crop, CropGrab.MOVE, 0.5f, -0.5f)
        assertEquals(0.6f, moved.right - moved.left, 1e-5f)
        assertEquals(1f, moved.right, 1e-5f)
        assertEquals(0f, moved.top, 1e-5f)
    }

    @Test
    fun `a corner drag cannot cross the opposite side`() {
        val dragged = CropDrag.drag(crop, CropGrab.TOP_LEFT, 0.9f, 0.9f)
        assertEquals(0.8f - CropDrag.MIN_SIDE, dragged.left, 1e-5f)
        assertEquals(0.8f - CropDrag.MIN_SIDE, dragged.top, 1e-5f)
        assertEquals(0.8f, dragged.right, 1e-5f)
    }

    @Test
    fun `an edge drag changes only that side`() {
        val dragged = CropDrag.drag(crop, CropGrab.RIGHT, 0.5f, 0.3f)
        assertEquals(1f, dragged.right, 1e-5f)
        assertEquals(0.2f, dragged.left, 1e-5f)
        assertEquals(0.8f, dragged.bottom, 1e-5f)
    }
}
