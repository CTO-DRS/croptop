package app.croptop.core.input

import app.croptop.core.model.StylusBarrelAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PalmRejectionGateTest {
    @Test
    fun rejectsFingerNearStylusAndExpiresAfterProximityWindow() {
        val gate = PalmRejectionGate(staleAfterMillis = 750)
        gate.observe(PointerKind.STYLUS, 1_000)

        assertTrue(gate.rejects(PointerKind.FINGER, 1_300))
        assertFalse(gate.rejects(PointerKind.STYLUS, 1_300))
        assertFalse(gate.rejects(PointerKind.FINGER, 1_751))
    }

    @Test
    fun stylusExitImmediatelyAllowsTouchAgain() {
        val gate = PalmRejectionGate()
        gate.observe(PointerKind.ERASER, 500)
        assertTrue(gate.rejects(PointerKind.FINGER, 600))

        gate.observeStylusExit(650)
        assertFalse(gate.rejects(PointerKind.FINGER, 651))
    }

    @Test
    fun additionalFingerIsSuppressedOnlyDuringStylusOrEraserGesture() {
        val gate = PalmRejectionGate()
        gate.observe(PointerKind.STYLUS, 1_000)

        assertTrue(rejectsPalmContact(PointerKind.STYLUS, PointerKind.FINGER, gate, 1_100))
        assertTrue(rejectsPalmContact(PointerKind.ERASER, PointerKind.FINGER, gate, 1_100))
        assertFalse(rejectsPalmContact(PointerKind.MOUSE, PointerKind.FINGER, gate, 1_100))
    }

    @Test
    fun stylusEraserEndAndBarrelButtonResolveToConfiguredTools() {
        assertEquals(CanvasPointerAction.ERASER, canvasPointerAction(PointerKind.ERASER, false, StylusBarrelAction.PAN))
        assertEquals(CanvasPointerAction.EYEDROPPER, canvasPointerAction(PointerKind.STYLUS, true, StylusBarrelAction.EYEDROPPER))
        assertEquals(CanvasPointerAction.PAN, canvasPointerAction(PointerKind.STYLUS, true, StylusBarrelAction.PAN))
        assertEquals(CanvasPointerAction.ACTIVE_TOOL, canvasPointerAction(PointerKind.MOUSE, true, StylusBarrelAction.PAN))
    }
}
