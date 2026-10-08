package app.croptop.feature.develop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import app.croptop.core.model.LocalMask
import app.croptop.core.model.LocalMaskTemplates
import app.croptop.core.model.MaskComponent
import app.croptop.core.model.MaskKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DevelopMaskHandlesTest {
    @Test
    fun `a wide frame in a square box is letterboxed top and bottom`() {
        val fitted = FittedFrame.of(IntSize(400, 400), 200, 100)!!
        assertEquals(0f, fitted.left, 1e-4f)
        assertEquals(100f, fitted.top, 1e-4f)
        assertEquals(400f, fitted.width, 1e-4f)
        assertEquals(200f, fitted.height, 1e-4f)
    }

    @Test
    fun `normalized and pixel positions round trip through the fitted frame`() {
        val fitted = FittedFrame.of(IntSize(400, 400), 200, 100)!!
        val px = fitted.toPx(Offset(0.25f, 0.5f))
        val back = fitted.toNorm(px)
        assertEquals(0.25f, back.x, 1e-4f)
        assertEquals(0.5f, back.y, 1e-4f)
        assertTrue(fitted.contains(px))
        assertTrue(!fitted.contains(Offset(5f, 5f)))
    }

    @Test
    fun `an empty box has no fitted frame`() {
        assertNull(FittedFrame.of(IntSize.Zero, 10, 10))
    }

    @Test
    fun `linear handles sit on the two endpoints and clamp when dragged outside the frame`() {
        val mask = LocalMask(components = listOf(MaskComponent.linear(0.5f, 0.15f, 0.5f, 0.55f)))
        val handles = handlesOf(mask)
        assertEquals(2, handles.size)
        assertEquals(Offset(0.5f, 0.15f), handles[0].at)
        val moved = handles[1].apply(mask.components[0], Offset(1.4f, -0.2f))
        assertEquals(1f, moved.params[2], 1e-4f)
        assertEquals(0f, moved.params[3], 1e-4f)
    }

    @Test
    fun `radial rim handles keep the radius at least a sliver wide`() {
        val mask = LocalMask(components = listOf(MaskComponent.radial(0.5f, 0.5f, 0.25f, 0.25f)))
        val handles = handlesOf(mask)
        assertEquals(3, handles.size)
        assertEquals(Offset(0.75f, 0.5f), handles[1].at)
        val shrunk = handles[1].apply(mask.components[0], Offset(0.5f, 0.5f))
        assertEquals(0.005f, shrunk.params[2], 1e-4f)
    }

    @Test
    fun `brush and colour components expose no handles`() {
        assertTrue(handlesOf(LocalMaskTemplates.of(MaskKind.BRUSH, 1)).isEmpty())
        assertTrue(handlesOf(LocalMaskTemplates.of(MaskKind.COLOR, 1)).isEmpty())
    }
}
