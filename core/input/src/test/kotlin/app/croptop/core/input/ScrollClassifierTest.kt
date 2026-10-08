package app.croptop.core.input

import app.croptop.core.model.WheelBehavior
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ScrollClassifierTest {
    private fun wheel(v: Float, m: Modifiers = Modifiers.None, kind: PointerKind = PointerKind.MOUSE) =
        RawScroll(x = 10f, y = 20f, vScroll = v, kind = kind, modifiers = m)

    @Test
    fun `plain wheel pans vertically`() {
        val g = ScrollClassifier.classify(wheel(1f), WheelBehavior.SCROLL)
        assertEquals(CanvasGesture.Pan(0f, ScrollClassifier.PX_PER_NOTCH), g)
    }

    @Test
    fun `ctrl wheel zooms at cursor`() {
        val g = assertIs<CanvasGesture.Zoom>(ScrollClassifier.classify(wheel(1f, Modifiers(ctrl = true)), WheelBehavior.SCROLL))
        assertEquals(1.2f, g.factor, 1e-4f)
        assertEquals(10f, g.anchorX)
        val out = assertIs<CanvasGesture.Zoom>(ScrollClassifier.classify(wheel(-1f, Modifiers(ctrl = true)), WheelBehavior.SCROLL))
        assertTrue(out.factor < 1f)
    }

    @Test
    fun `alt wheel resizes brush, shift wheel pans horizontally`() {
        assertEquals(CanvasGesture.BrushSize(2f), ScrollClassifier.classify(wheel(2f, Modifiers(alt = true)), WheelBehavior.SCROLL))
        assertEquals(
            CanvasGesture.Pan(ScrollClassifier.PX_PER_NOTCH, 0f),
            ScrollClassifier.classify(wheel(1f, Modifiers(shift = true)), WheelBehavior.SCROLL),
        )
    }

    @Test
    fun `wheel zoom preference applies to mice only`() {
        assertIs<CanvasGesture.Zoom>(ScrollClassifier.classify(wheel(1f), WheelBehavior.ZOOM))
        assertIs<CanvasGesture.Pan>(ScrollClassifier.classify(wheel(1f, kind = PointerKind.TOUCHPAD), WheelBehavior.ZOOM))
    }

    @Test
    fun `touchpad pinch zooms and swipe pans`() {
        assertEquals(
            CanvasGesture.Zoom(1.05f, 1f, 2f),
            ScrollClassifier.classify(RawScroll(1f, 2f, pinchScale = 1.05f, kind = PointerKind.TOUCHPAD), WheelBehavior.SCROLL),
        )
        assertEquals(
            CanvasGesture.Pan(-3f, -4f),
            ScrollClassifier.classify(RawScroll(0f, 0f, swipeDx = 3f, swipeDy = 4f, kind = PointerKind.TOUCHPAD), WheelBehavior.SCROLL),
        )
    }
}
