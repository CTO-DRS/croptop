package app.croptop.core.designsystem.component

import app.croptop.core.model.BlendIfRange
import kotlin.test.Test
import kotlin.test.assertEquals

class BlendIfSliderMathTest {

    @Test
    fun unsplitPairMovesTogether() {
        assertEquals(BlendIfRange(blackLo = 80, blackHi = 80), BlendIfSliderMath.move(BlendIfRange(), BlendIfThumb.BLACK_HI, 80, split = false))
        assertEquals(BlendIfRange(whiteLo = 200, whiteHi = 200), BlendIfSliderMath.move(BlendIfRange(), BlendIfThumb.WHITE_LO, 200, split = false))
    }

    @Test
    fun altSplitsAPair() {
        val split = BlendIfSliderMath.move(BlendIfRange(blackLo = 50, blackHi = 50), BlendIfThumb.BLACK_HI, 120, split = true)
        assertEquals(BlendIfRange(blackLo = 50, blackHi = 120), split)
        // Once split, each half moves alone without Alt.
        assertEquals(BlendIfRange(blackLo = 20, blackHi = 120), BlendIfSliderMath.move(split, BlendIfThumb.BLACK_LO, 20, split = false))
    }

    @Test
    fun thumbsNeverCross() {
        val r = BlendIfRange(blackLo = 10, blackHi = 60, whiteLo = 180, whiteHi = 240)
        assertEquals(10, BlendIfSliderMath.move(r, BlendIfThumb.BLACK_HI, 0, false).blackHi.coerceAtLeast(10))
        assertEquals(60, BlendIfSliderMath.move(r, BlendIfThumb.BLACK_LO, 200, false).blackLo)
        assertEquals(180, BlendIfSliderMath.move(r, BlendIfThumb.BLACK_HI, 255, false).blackHi)
        assertEquals(240, BlendIfSliderMath.move(r, BlendIfThumb.WHITE_LO, 255, false).whiteLo)
        assertEquals(180, BlendIfSliderMath.move(r, BlendIfThumb.WHITE_HI, 0, false).whiteHi)
    }

    @Test
    fun nearestPicksTheClosestThumbAndTheMovableHalfOfAStack() {
        val r = BlendIfRange(blackLo = 40, blackHi = 90, whiteLo = 200, whiteHi = 200)
        assertEquals(BlendIfThumb.BLACK_LO, BlendIfSliderMath.nearest(r, 30))
        assertEquals(BlendIfThumb.BLACK_HI, BlendIfSliderMath.nearest(r, 100))
        val stacked = BlendIfRange()
        assertEquals(BlendIfThumb.BLACK_HI, BlendIfSliderMath.nearest(stacked, 5))
        assertEquals(BlendIfThumb.WHITE_LO, BlendIfSliderMath.nearest(stacked, 250))
    }
}
