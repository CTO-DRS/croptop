package app.croptop.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PerspectiveCropTest {
    @Test
    fun `rectangular quad maps exactly to output corners`() {
        val quad = CropQuad(DocPoint(2f, 3f), DocPoint(12f, 3f), DocPoint(12f, 8f), DocPoint(2f, 8f))
        val size = assertNotNull(PerspectiveCropGeometry.outputSize(quad))
        assertEquals(DocSize(10, 5), size)
        val matrix = assertNotNull(PerspectiveCropGeometry.homography(quad, size))
        assertEquals(DocPoint(0f, 0f), PerspectiveCropGeometry.map(matrix, quad.topLeft))
        assertEquals(DocPoint(10f, 0f), PerspectiveCropGeometry.map(matrix, quad.topRight))
        assertEquals(DocPoint(10f, 5f), PerspectiveCropGeometry.map(matrix, quad.bottomRight))
        assertEquals(DocPoint(0f, 5f), PerspectiveCropGeometry.map(matrix, quad.bottomLeft))
    }

    @Test
    fun `rejects crossed and nonfinite quads`() {
        val crossed = CropQuad(DocPoint(0f, 0f), DocPoint(10f, 10f), DocPoint(10f, 0f), DocPoint(0f, 10f))
        val nonfinite = CropQuad(DocPoint(Float.NaN, 0f), DocPoint(10f, 0f), DocPoint(10f, 10f), DocPoint(0f, 10f))
        assertNull(PerspectiveCropGeometry.outputSize(crossed))
        assertNull(PerspectiveCropGeometry.outputSize(nonfinite))
    }
}
