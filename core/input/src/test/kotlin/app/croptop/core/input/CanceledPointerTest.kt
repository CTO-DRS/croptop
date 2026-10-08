package app.croptop.core.input

import android.view.MotionEvent
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CanceledPointerTest {
    @Test
    fun `canceled secondary pointer up does not cancel active stylus pointer`() {
        assertFalse(
            isCanceledPointer(
                MotionEvent.ACTION_POINTER_UP,
                MotionEvent.FLAG_CANCELED,
                actionIndex = 1,
                pointerIndex = 0,
            ),
        )
        assertTrue(
            isCanceledPointer(
                MotionEvent.ACTION_POINTER_UP,
                MotionEvent.FLAG_CANCELED,
                actionIndex = 1,
                pointerIndex = 1,
            ),
        )
    }

    @Test
    fun `whole gesture cancel ignores pointer index and canceled active up is retained`() {
        assertTrue(isCanceledPointer(MotionEvent.ACTION_CANCEL, 0, actionIndex = 0, pointerIndex = 1))
        assertTrue(isCanceledPointer(MotionEvent.ACTION_UP, MotionEvent.FLAG_CANCELED, actionIndex = 0, pointerIndex = 0))
        assertFalse(isCanceledPointer(MotionEvent.ACTION_UP, 0, actionIndex = 0, pointerIndex = 0))
    }
}
