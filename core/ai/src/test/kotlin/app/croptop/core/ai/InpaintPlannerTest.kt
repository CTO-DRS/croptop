package app.croptop.core.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InpaintPlannerTest {
    @Test
    fun smallHoleInAHugePhotoGetsAContextCropAtFullResolution() {
        val plan = InpaintPlanner.plan(PixelBox(4000, 3000, 4100, 3080), 6000, 4000, 512)
        assertTrue(plan.crop.left <= 4000 && plan.crop.right >= 4100 && plan.crop.top <= 3000 && plan.crop.bottom >= 3080)
        assertEquals(plan.crop.width, plan.crop.height)
        assertTrue(plan.crop.width <= 512, "context crop should fit the network without resampling: ${plan.crop.width}")
        assertEquals(1f, plan.scale)
        assertFalse(plan.needsTiling)
        assertEquals(512 - plan.crop.width, plan.padX)
    }

    @Test
    fun holeNearTheEdgeShiftsTheCropInsideTheImage() {
        val plan = InpaintPlanner.plan(PixelBox(0, 0, 40, 40), 1000, 800, 512)
        assertEquals(0, plan.crop.left)
        assertEquals(0, plan.crop.top)
        assertTrue(plan.crop.right <= 1000 && plan.crop.bottom <= 800)
        val far = InpaintPlanner.plan(PixelBox(960, 760, 1000, 800), 1000, 800, 512)
        assertEquals(1000, far.crop.right)
        assertEquals(800, far.crop.bottom)
    }

    @Test
    fun cropLargerThanTheNetworkIsDownscaledAndBigHolesAskForTiling() {
        val medium = InpaintPlanner.plan(PixelBox(1000, 1000, 1300, 1300), 5000, 5000, 512)
        assertTrue(medium.scale < 1f && medium.crop.width > 512)
        assertFalse(medium.needsTiling)
        val huge = InpaintPlanner.plan(PixelBox(500, 500, 3500, 3500), 4000, 4000, 512)
        assertTrue(huge.needsTiling)
    }

    @Test
    fun imageSmallerThanTheNetworkIsPaddedNotScaled() {
        val plan = InpaintPlanner.plan(PixelBox(20, 20, 60, 50), 200, 120, 512)
        assertEquals(PixelBox(0, 0, 104, 104), plan.crop) // hole 40×30 + 32 px context each side, squared, shifted into the image
        assertEquals(1f, plan.scale)
        assertEquals(512 - 104, plan.padX)
        assertEquals(512 - 104, plan.padY)
    }

    @Test
    fun invalidInputsAreRejected() {
        assertFailsWith<IllegalArgumentException> { InpaintPlanner.plan(PixelBox(0, 0, 10, 10), 5, 5, 512) }
        assertFailsWith<IllegalArgumentException> { PixelBox(3, 3, 3, 9) }
    }
}
