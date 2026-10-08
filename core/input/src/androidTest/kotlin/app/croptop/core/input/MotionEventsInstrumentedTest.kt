package app.croptop.core.input

import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MotionEventsInstrumentedTest {
    @Test
    fun stylusHistoryRetainsOrderedPositionPressureTiltAndOrientation() {
        val first = coordinates(10f, 20f, 0.2f, 0.1f, 0.3f)
        val event = MotionEvent.obtain(
            10L,
            10L,
            MotionEvent.ACTION_DOWN,
            1,
            arrayOf(properties()),
            arrayOf(first),
            0,
            MotionEvent.BUTTON_STYLUS_PRIMARY,
            1f,
            1f,
            1,
            0,
            InputDevice.SOURCE_STYLUS,
            0,
        )
        try {
            event.addBatch(20L, arrayOf(coordinates(15f, 25f, 0.5f, 0.4f, 0.6f)), 0)
            event.addBatch(30L, arrayOf(coordinates(21f, 29f, 0.9f, 0.7f, 0.8f)), 0)

            assertEquals(PointerKind.STYLUS, MotionEvents.kind(event))
            assertTrue(MotionEvents.stylusBarrelPressed(event))
            val samples = MotionEvents.samples(event)
            assertEquals(3, samples.size)
            assertEquals(listOf(10L, 20L, 30L), samples.map { it.timeMillis })
            assertEquals(listOf(10f, 15f, 21f), samples.map { it.x })
            assertEquals(listOf(0.2f, 0.5f, 0.9f), samples.map { it.pressure })
            assertEquals(0.4f, samples[1].tilt)
            assertEquals(0.6f, samples[1].orientation)
            assertFalse(MotionEvents.cancelled(event))
        } finally {
            event.recycle()
        }
    }

    @Test
    fun canceledMotionEventIsRecognizedForDiscardingTheStroke() {
        val event = MotionEvent.obtain(
            10L,
            12L,
            MotionEvent.ACTION_CANCEL,
            1,
            arrayOf(properties()),
            arrayOf(coordinates(5f, 6f, 0.8f, 0f, 0f)),
            0,
            0,
            1f,
            1f,
            1,
            0,
            InputDevice.SOURCE_STYLUS,
            MotionEvent.FLAG_CANCELED,
        )
        try {
            assertTrue(MotionEvents.cancelled(event))
        } finally {
            event.recycle()
        }
    }

    private fun properties() = MotionEvent.PointerProperties().apply {
        id = 0
        toolType = MotionEvent.TOOL_TYPE_STYLUS
    }

    private fun coordinates(x: Float, y: Float, pressure: Float, tilt: Float, orientation: Float) = MotionEvent.PointerCoords().apply {
        this.x = x
        this.y = y
        this.pressure = pressure
        size = 1f
        setAxisValue(MotionEvent.AXIS_TILT, tilt)
        setAxisValue(MotionEvent.AXIS_ORIENTATION, orientation)
    }
}
