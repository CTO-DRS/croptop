package app.croptop.feature.editor.tools

import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.RefineEdgeBrushMode
import app.croptop.core.engine.api.RefineEdgeBrushSession
import app.croptop.core.engine.api.RefineEdgeBrushSpec
import app.croptop.core.engine.api.RefineEdgeOutput
import app.croptop.core.engine.api.RefineEdgeSession
import app.croptop.core.engine.api.RefineEdgeSettings
import app.croptop.core.engine.api.RefineEdgeViewMode
import app.croptop.core.engine.api.RejectionCode
import app.croptop.core.engine.api.StrokeSampleLayout
import app.croptop.core.model.DocPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class RefineEdgeBrushControllerTest {
    @Test
    fun `add and subtract gestures send document coordinates in stroke layout`() {
        val refine = RecordingRefineSession()
        val controller = RefineEdgeBrushController(refine)

        assertNull(controller.begin(DocPoint(12.5f, 21f), pressure = 0.6f, timeMillis = 2_000L))
        assertNull(controller.move(DocPoint(18f, 29.5f), pressure = 1.2f, timeMillis = 2_250L))
        assertEquals(CommandResult.Accepted(7L), controller.end())
        assertFalse(controller.isActive)

        val add = refine.strokes.single()
        assertEquals(RefineEdgeBrushMode.ADD, add.spec.mode)
        assertEquals(2, add.samples.size)
        assertSample(add.samples[0], x = 12.5f, y = 21f, pressure = 0.6f, seconds = 0f)
        assertSample(add.samples[1], x = 18f, y = 29.5f, pressure = 1f, seconds = 0.25f)
        assertEquals(1, add.endCount)

        controller.setSpec(RefineEdgeBrushSpec(mode = RefineEdgeBrushMode.SUBTRACT, diameter = 44f))
        assertNull(controller.begin(DocPoint(3f, 4f), timeMillis = 5_000L))
        assertEquals(CommandResult.Accepted(7L), controller.end())
        assertEquals(RefineEdgeBrushMode.SUBTRACT, refine.strokes.last().spec.mode)
        assertSample(refine.strokes.last().samples.single(), x = 3f, y = 4f, pressure = 1f, seconds = 0f)
    }

    @Test
    fun `cancel is idempotent and permits a later stroke`() {
        val refine = RecordingRefineSession()
        val controller = RefineEdgeBrushController(refine)

        assertNull(controller.begin(DocPoint(1f, 2f)))
        assertTrue(controller.isActive)
        controller.cancel()
        controller.cancel()

        assertFalse(controller.isActive)
        assertEquals(1, refine.strokes.single().cancelCount)
        assertEquals(0, refine.strokes.single().endCount)
        assertIs<CommandResult.Rejected>(controller.end())

        assertNull(controller.begin(DocPoint(5f, 6f)))
        assertEquals(CommandResult.Accepted(7L), controller.end())
    }

    @Test
    fun `engine end rejection is returned and closes the stroke`() {
        val rejection = CommandResult.Rejected(RejectionCode.UNSUPPORTED, "Refine brush unavailable")
        val refine = RecordingRefineSession(endResult = rejection)
        val controller = RefineEdgeBrushController(refine)

        assertNull(controller.begin(DocPoint(1f, 2f)))
        assertEquals(rejection, controller.end())
        assertFalse(controller.isActive)
        assertEquals(1, refine.strokes.single().endCount)
        assertIs<CommandResult.Rejected>(controller.end())
    }

    @Test
    fun `sample rejection cancels the partial engine stroke`() {
        val refine = RecordingRefineSession(addFailure = UnsupportedOperationException("No brush support"))
        val controller = RefineEdgeBrushController(refine)

        val result = controller.begin(DocPoint(1f, 2f))

        assertEquals(RejectionCode.UNSUPPORTED, assertIs<CommandResult.Rejected>(result).code)
        assertFalse(controller.isActive)
        assertEquals(1, refine.strokes.single().cancelCount)
    }

    @Test
    fun `invalid coordinates do not start an engine stroke`() {
        val refine = RecordingRefineSession()
        val controller = RefineEdgeBrushController(refine)

        val result = controller.begin(DocPoint(Float.NaN, 2f))

        assertEquals(RejectionCode.INVALID_ARGUMENT, assertIs<CommandResult.Rejected>(result).code)
        assertTrue(refine.strokes.isEmpty())
    }

    @Test
    fun `elapsed time conversion does not overflow for extreme timestamps`() {
        val refine = RecordingRefineSession()
        val controller = RefineEdgeBrushController(refine)

        assertNull(controller.begin(DocPoint(1f, 2f), timeMillis = Long.MIN_VALUE))
        assertNull(controller.move(DocPoint(3f, 4f), timeMillis = Long.MAX_VALUE))

        val actual = refine.strokes.single().samples.last()[StrokeSampleLayout.TIME_SECONDS]
        val expected = ((Long.MAX_VALUE.toDouble() - Long.MIN_VALUE.toDouble()) / 1_000.0).toFloat()
        assertEquals(expected, actual)
    }

    private fun assertSample(sample: FloatArray, x: Float, y: Float, pressure: Float, seconds: Float) {
        assertEquals(StrokeSampleLayout.STRIDE, sample.size)
        assertEquals(x, sample[StrokeSampleLayout.X])
        assertEquals(y, sample[StrokeSampleLayout.Y])
        assertEquals(pressure, sample[StrokeSampleLayout.PRESSURE])
        assertEquals(0f, sample[StrokeSampleLayout.TILT_X])
        assertEquals(0f, sample[StrokeSampleLayout.TILT_Y])
        assertEquals(0f, sample[StrokeSampleLayout.ORIENTATION])
        assertEquals(seconds, sample[StrokeSampleLayout.TIME_SECONDS])
    }

    private class RecordingRefineSession(
        private val endResult: CommandResult = CommandResult.Accepted(7L),
        private val addFailure: Throwable? = null,
    ) : RefineEdgeSession {
        val strokes = mutableListOf<RecordingBrushSession>()
        private val viewModeState = MutableStateFlow(RefineEdgeViewMode.OVERLAY)
        override val viewMode: StateFlow<RefineEdgeViewMode> get() = viewModeState
        override fun setViewMode(mode: RefineEdgeViewMode) = CommandResult.Accepted(7L)
        override fun update(settings: RefineEdgeSettings) = CommandResult.Accepted(7L)
        override fun commit(output: RefineEdgeOutput) = CommandResult.Accepted(7L)
        override fun cancel() = Unit

        override fun beginBrushStroke(spec: RefineEdgeBrushSpec): RefineEdgeBrushSession =
            RecordingBrushSession(spec, endResult, addFailure).also(strokes::add)
    }

    private class RecordingBrushSession(
        val spec: RefineEdgeBrushSpec,
        private val result: CommandResult,
        private val addFailure: Throwable?,
    ) : RefineEdgeBrushSession {
        val samples = mutableListOf<FloatArray>()
        var endCount = 0
        var cancelCount = 0

        override fun add(samples: FloatArray) {
            addFailure?.let { throw it }
            this.samples += samples.copyOf()
        }

        override fun end(): CommandResult {
            endCount++
            return result
        }

        override fun cancel() {
            cancelCount++
        }
    }
}
