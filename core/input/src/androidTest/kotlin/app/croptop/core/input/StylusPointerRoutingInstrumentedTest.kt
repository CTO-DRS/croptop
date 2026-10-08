package app.croptop.core.input

import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.croptop.core.model.StylusBarrelAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StylusPointerRoutingInstrumentedTest {
    @Test
    fun historicalSamplesUseTheRequestedStylusPointerInAMultiPointerEvent() {
        val event = MotionEvent.obtain(
            10L,
            20L,
            MotionEvent.ACTION_MOVE,
            2,
            arrayOf(properties(4, MotionEvent.TOOL_TYPE_FINGER), properties(9, MotionEvent.TOOL_TYPE_STYLUS)),
            arrayOf(coordinates(3f, 4f, 0.1f), coordinates(30f, 40f, 0.4f)),
            0,
            MotionEvent.BUTTON_STYLUS_SECONDARY,
            1f,
            1f,
            17,
            0,
            InputDevice.SOURCE_STYLUS,
            0,
        )
        try {
            event.addBatch(30L, arrayOf(coordinates(5f, 6f, 0.2f), coordinates(50f, 60f, 0.8f)), 0)

            assertEquals(PointerKind.STYLUS, MotionEvents.kind(event, pointerIndex = 1))
            assertTrue(MotionEvents.stylusBarrelPressed(event))
            assertEquals(
                listOf(PointerSample(30f, 40f, 0.4f, 0f, 0f, 20L), PointerSample(50f, 60f, 0.8f, 0f, 0f, 30L)),
                MotionEvents.samples(event, pointerIndex = 1),
            )
            assertEquals(
                CanvasPointerAction.PAN,
                canvasPointerAction(PointerKind.STYLUS, MotionEvents.stylusBarrelPressed(event), StylusBarrelAction.PAN),
            )
        } finally {
            event.recycle()
        }
    }

    @Test
    fun eraserToolTypeAndCanceledMotionEventStayDistinctFromNormalPenInput() {
        val eraser = MotionEvent.obtain(
            1L,
            2L,
            MotionEvent.ACTION_DOWN,
            1,
            arrayOf(properties(2, MotionEvent.TOOL_TYPE_ERASER)),
            arrayOf(coordinates(8f, 9f, 1f)),
            0,
            0,
            1f,
            1f,
            17,
            0,
            InputDevice.SOURCE_STYLUS,
            0,
        )
        val cancelled = MotionEvent.obtain(
            1L,
            3L,
            MotionEvent.ACTION_CANCEL,
            1,
            arrayOf(properties(2, MotionEvent.TOOL_TYPE_STYLUS)),
            arrayOf(coordinates(8f, 9f, 0.5f)),
            0,
            0,
            1f,
            1f,
            17,
            0,
            InputDevice.SOURCE_STYLUS,
            MotionEvent.FLAG_CANCELED,
        )
        try {
            assertEquals(PointerKind.ERASER, MotionEvents.kind(eraser))
            assertEquals(CanvasPointerAction.ERASER, canvasPointerAction(MotionEvents.kind(eraser), false, StylusBarrelAction.PAN))
            assertTrue(MotionEvents.cancelled(cancelled))
        } finally {
            eraser.recycle()
            cancelled.recycle()
        }
    }

    private fun properties(id: Int, toolType: Int) = MotionEvent.PointerProperties().apply {
        this.id = id
        this.toolType = toolType
    }

    private fun coordinates(x: Float, y: Float, pressure: Float) = MotionEvent.PointerCoords().apply {
        this.x = x
        this.y = y
        this.pressure = pressure
        size = 1f
    }
}
