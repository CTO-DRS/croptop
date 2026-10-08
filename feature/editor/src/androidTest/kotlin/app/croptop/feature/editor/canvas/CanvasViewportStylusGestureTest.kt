package app.croptop.feature.editor.canvas

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.croptop.core.engine.NativeEngine
import app.croptop.core.engine.api.ColorSample
import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.SampleMode
import app.croptop.core.engine.api.StrokeSession
import app.croptop.core.input.PointerKind
import app.croptop.core.model.BackgroundContents
import app.croptop.core.model.BrushSettings
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocSize
import app.croptop.core.model.FillSource
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.RgbaColor
import app.croptop.core.model.ToolId
import app.croptop.core.model.ViewPoint
import app.croptop.core.model.ViewportState
import app.croptop.feature.editor.tools.BrushTool
import app.croptop.feature.editor.tools.EyedropperTool
import app.croptop.feature.editor.tools.PointerPhase
import app.croptop.feature.editor.tools.ToolContext
import app.croptop.feature.editor.tools.ToolController
import app.croptop.feature.editor.tools.ToolPointerEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises Android MotionEvent routing through Compose, CanvasViewport, BrushTool, and its session. */
@RunWith(AndroidJUnit4::class)
class CanvasViewportStylusGestureTest {
    private var scenario: ActivityScenario<CanvasViewportGestureTestActivity>? = null
    private var activeFixture: Fixture? = null

    @After
    fun closeActivity() {
        activeFixture?.close()
        activeFixture = null
        scenario?.close()
        scenario = null
    }

    @Test
    fun secondFingerDuringStylusStrokeIsRejectedAndOneStrokeCommitsOnlyPenPixels() {
        val fixture = Fixture()
        show(fixture)

        val start = SystemClock.uptimeMillis()
        val downTime = start + 1
        dispatch(stylus(MotionEvent.ACTION_DOWN, 7, 32f, 42f, time = downTime, downTime = downTime))
        assertEquals(PointerPhase.DOWN, fixture.nextEvent().phase)
        val viewportBeforePalm = fixture.viewport.value
        dispatch(
            twoPointers(
                MotionEvent.ACTION_POINTER_DOWN,
                actionIndex = 1,
                time = start + 2,
                downTime = downTime,
                stylus = 7,
                sx = 36f,
                sy = 46f,
                finger = 9,
                fx = 140f,
                fy = 150f,
            ),
        )
        assertEquals("active stylus stroke continues while the second finger is rejected", PointerPhase.MOVE, fixture.nextEvent().phase)
        assertEquals("a stylus plus palm must not pan or zoom the canvas", viewportBeforePalm, fixture.viewport.value)
        val batchedMove = twoPointers(
            MotionEvent.ACTION_MOVE,
            actionIndex = 0,
            time = start + 2,
            downTime = downTime,
            stylus = 7,
            sx = 45f,
            sy = 50f,
            finger = 9,
            fx = 145f,
            fy = 155f,
        )
        batchedMove.addBatch(
            start + 3,
            arrayOf(point(50f, 54f), point(145f, 155f)),
            0,
        )
        dispatch(batchedMove)
        assertEquals(PointerPhase.MOVE, fixture.nextEvent().phase)
        assertEquals("moving a palm during a stylus stroke must not pan or zoom", viewportBeforePalm, fixture.viewport.value)
        dispatch(
            twoPointers(
                MotionEvent.ACTION_POINTER_UP,
                actionIndex = 1,
                time = start + 4,
                downTime = downTime,
                stylus = 7,
                sx = 55f,
                sy = 60f,
                finger = 9,
                fx = 150f,
                fy = 160f,
                flags = MotionEvent.FLAG_CANCELED,
            ),
        )
        assertEquals(PointerPhase.MOVE, fixture.nextEvent().phase)
        assertEquals("lifting a palm during a stylus stroke must not pan or zoom", viewportBeforePalm, fixture.viewport.value)
        dispatch(stylus(MotionEvent.ACTION_UP, 7, 62f, 68f, time = start + 5, downTime = downTime))
        assertEquals(PointerPhase.UP, fixture.nextEvent().phase)

        assertEquals(1, fixture.sessions.size)
        val session = fixture.sessions.single()
        assertFalse(session.eraseTool)
        assertEquals(1, session.endCount)
        assertEquals(0, session.cancelCount)
        assertEquals(1, fixture.historySteps)

        assertTrace(fixture.expectedDocPixels, session.committedPixels)
        assertTrue(fixture.routedViewSamples.any { close(it.first, 45f) && close(it.second, 50f) })
        val historicalDocPoint = fixture.documentPoint(45f, 50f)
        assertTrue(session.committedPixels.any { close(it.x, historicalDocPoint.first) && close(it.y, historicalDocPoint.second) })
        assertTrue(fixture.routedViewSamples.any { close(it.first, 62f) && close(it.second, 68f) })
        assertFalse(fixture.routedViewSamples.any { close(it.first, 150f) && close(it.second, 160f) })
    }

    @Test
    fun secondFingerDuringEraserStrokeIsRejectedAndEraserSessionCommits() {
        val fixture = Fixture()
        show(fixture)

        val start = SystemClock.uptimeMillis()
        val downTime = start + 1
        dispatch(
            stylus(
                MotionEvent.ACTION_DOWN,
                4,
                28f,
                34f,
                time = downTime,
                downTime = downTime,
                toolType = MotionEvent.TOOL_TYPE_ERASER,
            ),
        )
        assertEquals(PointerPhase.DOWN, fixture.nextEvent().phase)
        val viewportBeforePalm = fixture.viewport.value
        dispatch(
            twoPointers(
                MotionEvent.ACTION_POINTER_DOWN,
                actionIndex = 1,
                time = start + 2,
                downTime = downTime,
                stylus = 4,
                sx = 38f,
                sy = 44f,
                finger = 8,
                fx = 140f,
                fy = 150f,
                stylusToolType = MotionEvent.TOOL_TYPE_ERASER,
            ),
        )
        assertEquals(PointerPhase.MOVE, fixture.nextEvent().phase)
        assertEquals("a stylus plus palm must not pan or zoom the canvas", viewportBeforePalm, fixture.viewport.value)
        dispatch(
            twoPointers(
                MotionEvent.ACTION_MOVE,
                actionIndex = 0,
                time = start + 3,
                downTime = downTime,
                stylus = 4,
                sx = 50f,
                sy = 60f,
                finger = 8,
                fx = 145f,
                fy = 155f,
                stylusToolType = MotionEvent.TOOL_TYPE_ERASER,
            ),
        )
        assertEquals(PointerPhase.MOVE, fixture.nextEvent().phase)
        assertEquals("moving a palm during an eraser stroke must not pan or zoom", viewportBeforePalm, fixture.viewport.value)
        dispatch(
            twoPointers(
                MotionEvent.ACTION_POINTER_UP,
                actionIndex = 1,
                time = start + 4,
                downTime = downTime,
                stylus = 4,
                sx = 55f,
                sy = 65f,
                finger = 8,
                fx = 150f,
                fy = 160f,
                stylusToolType = MotionEvent.TOOL_TYPE_ERASER,
            ),
        )
        assertEquals(PointerPhase.MOVE, fixture.nextEvent().phase)
        assertEquals("lifting a palm during an eraser stroke must not pan or zoom", viewportBeforePalm, fixture.viewport.value)
        dispatch(
            stylus(
                MotionEvent.ACTION_UP,
                4,
                62f,
                72f,
                time = start + 5,
                downTime = downTime,
                toolType = MotionEvent.TOOL_TYPE_ERASER,
            ),
        )
        assertEquals(PointerPhase.UP, fixture.nextEvent().phase)

        val session = fixture.sessions.single()
        assertTrue(session.eraseTool)
        assertEquals(1, session.endCount)
        assertEquals(0, session.cancelCount)
        assertEquals(1, fixture.historySteps)
        assertTrace(fixture.expectedDocPixels, session.committedPixels)
        assertTrue(fixture.routedViewSamples.any { close(it.first, 62f) && close(it.second, 72f) })
        assertFalse(fixture.routedViewSamples.any { close(it.first, 150f) && close(it.second, 160f) })
    }

    @Test
    fun actionCancelDuringEraserStrokeDiscardsSamplesAndDoesNotCreateHistory() {
        val fixture = Fixture()
        show(fixture)

        val start = SystemClock.uptimeMillis()
        val downTime = start + 1
        dispatch(
            stylus(
                MotionEvent.ACTION_DOWN,
                4,
                28f,
                34f,
                time = downTime,
                downTime = downTime,
                toolType = MotionEvent.TOOL_TYPE_ERASER,
            ),
        )
        assertEquals(PointerPhase.DOWN, fixture.nextEvent().phase)
        dispatch(
            stylus(
                MotionEvent.ACTION_MOVE,
                4,
                68f,
                74f,
                time = start + 2,
                downTime = downTime,
                toolType = MotionEvent.TOOL_TYPE_ERASER,
            ),
        )
        assertEquals(PointerPhase.MOVE, fixture.nextEvent().phase)
        dispatch(
            stylus(
                MotionEvent.ACTION_CANCEL,
                4,
                180f,
                190f,
                time = start + 3,
                downTime = downTime,
                toolType = MotionEvent.TOOL_TYPE_ERASER,
            ),
        )
        val terminal = fixture.nextEvent()
        assertEquals("raw events=${fixture.rawEvents}; terminal=$terminal", PointerPhase.CANCEL, terminal.phase)

        val session = fixture.sessions.single()
        assertTrue(session.eraseTool)
        assertEquals(0, session.endCount)
        assertEquals(1, session.cancelCount)
        assertEquals(0, fixture.historySteps)
        assertTrue(session.committedPixels.isEmpty())
    }

    @Test
    fun normalMouseActionUpWithMotionEventCommitsStroke() {
        val fixture = Fixture()
        show(fixture)

        val start = SystemClock.uptimeMillis()
        val downTime = start + 1
        dispatch(
            mouse(
                MotionEvent.ACTION_DOWN,
                1,
                40f,
                50f,
                time = downTime,
                downTime = downTime,
            ),
        )
        assertEquals(PointerPhase.DOWN, fixture.nextEvent().phase)
        dispatch(mouse(MotionEvent.ACTION_MOVE, 1, 55f, 65f, time = start + 2, downTime = downTime))
        assertEquals(PointerPhase.MOVE, fixture.nextEvent().phase)
        dispatch(mouse(MotionEvent.ACTION_UP, 1, 70f, 80f, time = start + 3, downTime = downTime))
        assertEquals(PointerPhase.UP, fixture.nextEvent().phase)

        val session = fixture.sessions.single()
        assertEquals(1, session.endCount)
        assertEquals(0, session.cancelCount)
        assertEquals(1, fixture.historySteps)
        assertTrace(fixture.expectedDocPixels, session.committedPixels)
    }

    @Test
    fun mouseHoverPublishesMouseKindAndExitClearsIt() {
        val fixture = Fixture()
        show(fixture)

        val start = SystemClock.uptimeMillis()
        dispatchHover(mouse(MotionEvent.ACTION_HOVER_MOVE, 1, 75f, 85f, time = start))
        assertEquals(PointerKind.MOUSE, fixture.hoverKinds.last())

        dispatchHover(mouse(MotionEvent.ACTION_HOVER_EXIT, 1, 75f, 85f, time = start + 1))
        assertEquals(null, fixture.hoverKinds.last())
    }

    @Test
    fun stylusHoverPublishesStylusKindAndExitClearsIt() {
        val fixture = Fixture()
        show(fixture)

        val start = SystemClock.uptimeMillis()
        dispatchHover(stylus(MotionEvent.ACTION_HOVER_MOVE, 1, 75f, 85f, time = start))
        assertEquals(PointerKind.STYLUS, fixture.hoverKinds.last())

        dispatchHover(stylus(MotionEvent.ACTION_HOVER_EXIT, 1, 75f, 85f, time = start + 1))
        assertEquals(null, fixture.hoverKinds.last())
    }

    @Test
    fun stylusPressureTiltAndOrientationReachTheCommittedBrushSamples() {
        val fixture = Fixture()
        show(fixture)

        val downTime = SystemClock.uptimeMillis() + 1
        dispatch(
            stylus(
                MotionEvent.ACTION_DOWN,
                14,
                35f,
                45f,
                time = downTime,
                downTime = downTime,
                pressure = 0.25f,
                tilt = 0.2f,
                orientation = 0.3f,
            ),
        )
        assertEquals(PointerPhase.DOWN, fixture.nextEvent().phase)
        dispatch(
            stylus(
                MotionEvent.ACTION_MOVE,
                14,
                55f,
                65f,
                time = downTime + 10,
                downTime = downTime,
                pressure = 0.75f,
                tilt = 0.8f,
                orientation = 1.2f,
            ),
        )
        assertEquals(PointerPhase.MOVE, fixture.nextEvent().phase)
        dispatch(
            stylus(
                MotionEvent.ACTION_UP,
                14,
                75f,
                85f,
                time = downTime + 20,
                downTime = downTime,
                pressure = 0.5f,
                tilt = 1.0f,
                orientation = 2.0f,
            ),
        )
        assertEquals(PointerPhase.UP, fixture.nextEvent().phase)

        val committed = fixture.sessions.single().committedSamples.flatMap { it.asList() }
        assertEquals(3 * 7, committed.size)
        assertEquals(0.25f, committed[2], 0.001f)
        assertEquals(0.75f, committed[9], 0.001f)
        assertEquals(0.5f, committed[16], 0.001f)
        val expectedTilt = 0.8f / (Math.PI.toFloat() / 2f)
        assertEquals(expectedTilt * kotlin.math.cos(1.2f), committed[10], 0.001f)
        assertEquals(expectedTilt * kotlin.math.sin(1.2f), committed[11], 0.001f)
        assertEquals(1.2f, committed[12], 0.001f)
        assertEquals(2f, committed[19], 0.001f)
    }

    @Test
    fun stylusEyedropperSamplesEveryRoutedUpdateAndReportsDispatchLatency() {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec(
                name = "Eyedropper pointer trace",
                size = DocSize(200, 200),
                background = BackgroundContents.TRANSPARENT,
            ),
        )
        try {
            assertTrue(document.fill(FillSource.Solid(RgbaColor(0.35f, 0.6f, 0.8f))) is CommandResult.Accepted)
            val sampleScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val fixture = Fixture(
                useEyedropper = true,
                nativeSampler = document::trySample,
                asyncSamplerScope = sampleScope,
            )
            show(fixture)

            val downTime = SystemClock.uptimeMillis()
            val centerX = fixture.viewport.value.viewWidth / 2f
            val centerY = fixture.viewport.value.viewHeight / 2f
            val startX = centerX - 30f
            val startY = centerY - 30f
            scenario!!.onActivity { it.startFrameCapture() }
            dispatch(stylus(MotionEvent.ACTION_DOWN, 12, startX, startY, time = downTime, downTime = downTime))
            assertEquals(PointerPhase.DOWN, fixture.nextEvent().phase)

            val roundTripNanos = mutableListOf<Long>()
            val traceStartMillis = SystemClock.uptimeMillis()
            repeat(120) { index ->
                val scheduledMillis = traceStartMillis + (index + 1) * 8L
                val waitMillis = scheduledMillis - SystemClock.uptimeMillis()
                if (waitMillis > 0) SystemClock.sleep(waitMillis)
                val started = SystemClock.elapsedRealtimeNanos()
                dispatch(
                    stylus(
                        MotionEvent.ACTION_MOVE,
                        12,
                        startX + 0.5f + index * 0.5f,
                        startY + 0.25f + index * 0.25f,
                        time = SystemClock.uptimeMillis(),
                        downTime = downTime,
                    ),
                )
                assertEquals(PointerPhase.MOVE, fixture.nextEvent().phase)
                roundTripNanos += SystemClock.elapsedRealtimeNanos() - started
            }

            val releaseX = centerX + 40f
            val releaseY = centerY + 30f
            dispatch(stylus(MotionEvent.ACTION_UP, 12, releaseX, releaseY, time = SystemClock.uptimeMillis(), downTime = downTime))
            assertEquals(PointerPhase.UP, fixture.nextEvent().phase)
            assertTrue("release sample did not commit", fixture.commitSignal.await(15, TimeUnit.SECONDS))
            assertTrue("native sampler did not run", fixture.nativeSampleTimesNanos.isNotEmpty())
            assertEquals(0, fixture.nativeNullSamples)
            assertEquals(fixture.viewport.value.viewToDoc(ViewPoint(releaseX, releaseY)), fixture.sampledPoints.last())
            assertEquals(1, fixture.committedColors.size)
            val sampledColor = fixture.committedColors.single()
            assertTrue(kotlin.math.abs(sampledColor.r - 0.35f) < 0.01f)
            assertTrue(kotlin.math.abs(sampledColor.g - 0.6f) < 0.01f)
            assertTrue(kotlin.math.abs(sampledColor.b - 0.8f) < 0.01f)
            val frames = requireNotNull(scenario).let { activityScenario ->
                var captured: List<FrameSample> = emptyList()
                activityScenario.onActivity { captured = it.stopFrameCapture() }
                captured
            }

            val sorted = roundTripNanos.sorted()
            val p50Millis = sorted[sorted.lastIndex / 2] / 1_000_000.0
            val p95Millis = sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.lastIndex)] / 1_000_000.0
            val maxMillis = sorted.last() / 1_000_000.0
            val sampleTimes = fixture.nativeSampleTimesNanos.sorted()
            val sampleP50Millis = sampleTimes[sampleTimes.lastIndex / 2] / 1_000_000.0
            val sampleP95Millis = sampleTimes[(sampleTimes.size * 0.95).toInt().coerceAtMost(sampleTimes.lastIndex)] / 1_000_000.0
            val sampleMaxMillis = sampleTimes.last() / 1_000_000.0
            assertTrue(
                "native sampling must either coalesce superseded points or keep up with the 120 Hz trace " +
                    "(samples=${fixture.sampledPoints.size}, p95=${sampleP95Millis}ms)",
                fixture.sampledPoints.size < 122 || sampleP95Millis < 8.0,
            )
            val missedDeadlines = frames.count { it.deadlineNanos > 0 && it.totalNanos > it.deadlineNanos }
            android.util.Log.i(
                "EyedropperPointerTrace",
                "engine=NativeEngine updates=${roundTripNanos.size} samples=${fixture.sampledPoints.size} " +
                    "nativeTrySampleMs(p50=$sampleP50Millis,p95=$sampleP95Millis,max=$sampleMaxMillis) " +
                    "dispatchRoundTripMs(p50=$p50Millis,p95=$p95Millis,max=$maxMillis) " +
                    "frameMetricsCount=${frames.size} missedDeadlines=$missedDeadlines",
            )
        } finally {
            activeFixture?.close()
            activeFixture = null
            document.close()
            engine.close()
        }
    }

    private fun dispatchHover(event: MotionEvent) {
        val action = event.actionMasked
        var handled = false
        var routed = false
        try {
            scenario!!.onActivity { activity ->
                val content = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
                val composeView = requireNotNull(content.getChildAt(0)) { "Compose host view was not installed" }
                handled = composeView.dispatchGenericMotionEvent(event)
            }
        } finally {
            routed = requireNotNull(activeFixture).awaitHoverKind()
            event.recycle()
        }
        assertTrue(
            "Compose did not publish hover action=$action (dispatchHandled=$handled, " +
                "kinds=${activeFixture?.hoverKinds}, source=${InputDevice.SOURCE_MOUSE})",
            routed,
        )
    }

    private fun dispatch(event: MotionEvent) {
        try {
            // ActivityScenario synchronously dispatches on main; the event stays valid until its input pass returns.
            scenario!!.onActivity { activity ->
                val content = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
                val composeView = requireNotNull(content.getChildAt(0)) { "Compose host view was not installed" }
                val handled = composeView.dispatchTouchEvent(event)
                // Android views may return false for ACTION_CANCEL after the stream's target is canceled.
                if (event.actionMasked != MotionEvent.ACTION_CANCEL) {
                    check(handled) { "Compose host did not handle ${event.actionMasked}" }
                }
            }
        } finally {
            val routed = requireNotNull(activeFixture).awaitRoutedEvent()
            val action = event.actionMasked
            event.recycle()
            check(routed) {
                "Compose did not route MotionEvent action=$action before timeout"
            }
        }
        // Tool event latches are awaited from the test thread; never use Espresso idle from a callback.
    }

    private fun show(fixture: Fixture) {
        activeFixture = fixture
        scenario = ActivityScenario.launch(CanvasViewportGestureTestActivity::class.java)
        scenario!!.onActivity { activity ->
            activity.setContent { fixture.Content() }
            val content = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
            val composeView = requireNotNull(content.getChildAt(0)) { "Compose host view was not installed" }
            composeView.setOnTouchListener { _, event ->
                fixture.rawEvents += "action=${event.actionMasked} flags=${event.flags} type=${event.getToolType(0)}"
                false
            }
        }
        assertTrue("Compose canvas did not finish its initial fit", fixture.fitReady.await(5, TimeUnit.SECONDS))
    }

    private fun stylus(
        action: Int,
        id: Int,
        x: Float,
        y: Float,
        time: Long,
        downTime: Long = time - 1,
        toolType: Int = MotionEvent.TOOL_TYPE_STYLUS,
        flags: Int = 0,
        pressure: Float = 0.8f,
        tilt: Float = 0f,
        orientation: Float = 0f,
    ): MotionEvent = motion(
        action,
        time,
        arrayOf(pointer(id, toolType)),
        arrayOf(point(x, y, pressure, tilt, orientation)),
        downTime,
        flags,
    )

    private fun mouse(
        action: Int,
        id: Int,
        x: Float,
        y: Float,
        time: Long,
        downTime: Long = time - 1,
    ): MotionEvent = motion(
        action,
        time,
        arrayOf(pointer(id, MotionEvent.TOOL_TYPE_MOUSE)),
        arrayOf(point(x, y)),
        downTime = downTime,
        source = InputDevice.SOURCE_MOUSE,
    )

    private fun twoPointers(
        action: Int,
        actionIndex: Int,
        time: Long,
        downTime: Long,
        stylus: Int,
        sx: Float,
        sy: Float,
        finger: Int,
        fx: Float,
        fy: Float,
        stylusToolType: Int = MotionEvent.TOOL_TYPE_STYLUS,
        flags: Int = 0,
    ): MotionEvent {
        val encodedAction = action or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        return motion(
            encodedAction,
            time,
            arrayOf(pointer(stylus, stylusToolType), pointer(finger, MotionEvent.TOOL_TYPE_FINGER)),
            arrayOf(point(sx, sy), point(fx, fy)),
            downTime = downTime,
            flags = flags,
        )
    }

    private fun motion(
        action: Int,
        time: Long,
        properties: Array<MotionEvent.PointerProperties>,
        coordinates: Array<MotionEvent.PointerCoords>,
        downTime: Long = time - 1,
        flags: Int = 0,
        source: Int = InputDevice.SOURCE_STYLUS,
    ) = MotionEvent.obtain(
        downTime,
        time,
        action,
        properties.size,
        properties,
        coordinates,
        0,
        0,
        1f,
        1f,
        42,
        0,
        source,
        flags,
    )

    private fun pointer(id: Int, toolType: Int) = MotionEvent.PointerProperties().apply {
        this.id = id
        this.toolType = toolType
    }

    private fun point(x: Float, y: Float, pressure: Float = 0.8f, tilt: Float = 0f, orientation: Float = 0f) = MotionEvent.PointerCoords().apply {
        this.x = x
        this.y = y
        this.pressure = pressure
        size = 1f
        setAxisValue(MotionEvent.AXIS_TILT, tilt)
        setAxisValue(MotionEvent.AXIS_ORIENTATION, orientation)
    }

    private fun close(a: Float, b: Float) = kotlin.math.abs(a - b) < 0.5f

    private fun assertTrace(expected: List<Pixel>, actual: List<Pixel>) {
        assertEquals("expected=$expected actual=$actual", expected.size, actual.size)
        expected.zip(actual).forEachIndexed { index, (input, committed) ->
            assertTrue("x[$index] expected=$input actual=$committed full=$actual", close(input.x, committed.x))
            assertTrue("y[$index] expected=$input actual=$committed full=$actual", close(input.y, committed.y))
        }
    }

    private inner class Fixture(
        private val useEyedropper: Boolean = false,
        nativeSampler: ((DocPoint, SampleMode, Int) -> ColorSample?)? = null,
        private val asyncSamplerScope: CoroutineScope? = null,
    ) {
        val sessions = mutableListOf<RecordingStrokeSession>()
        val fitReady = CountDownLatch(1)
        val rawEvents = java.util.Collections.synchronizedList(mutableListOf<String>())
        var historySteps = 0
        val viewport = mutableStateOf(ViewportState())
        private val routedEvents = LinkedBlockingQueue<ToolPointerEvent>()
        private val routedSignal = java.util.concurrent.Semaphore(0)
        val hoverKinds = java.util.Collections.synchronizedList(mutableListOf<PointerKind?>())
        private val hoverSignal = java.util.concurrent.Semaphore(0)
        val expectedDocPixels = mutableListOf<Pixel>()
        val routedViewSamples = mutableListOf<Pair<Float, Float>>()
        val sampledPoints = java.util.Collections.synchronizedList(mutableListOf<DocPoint>())
        val committedColors = java.util.Collections.synchronizedList(mutableListOf<RgbaColor>())
        val nativeSampleTimesNanos = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val commitSignal = CountDownLatch(1)
        var nativeNullSamples = 0
            private set
        private val foregroundColor = mutableStateOf(RgbaColor.Black)
        private val brushTool = createTool(erase = false)
        private val eraserTool = createTool(erase = true)
        private val recordingBrushTool = recordEvents(brushTool)
        private val recordingEraserTool = recordEvents(eraserTool)
        private val eyedropperTool = EyedropperTool(
            sample = { point, mode, size ->
                sampledPoints += point
                val started = SystemClock.elapsedRealtimeNanos()
                val nativeSample = nativeSampler?.invoke(point, mode, size)
                if (nativeSampler != null) nativeSampleTimesNanos += SystemClock.elapsedRealtimeNanos() - started
                if (nativeSampler != null && nativeSample == null) nativeNullSamples++
                nativeSample?.color ?: if (nativeSampler == null) {
                    RgbaColor((point.x / 200f).coerceIn(0f, 1f), 0.25f, 0.75f)
                } else {
                    null
                }
            },
            onForeground = { foregroundColor.value = it },
            onBackground = {},
            onCommit = {
                committedColors += it
                commitSignal.countDown()
            },
            activeColor = { RgbaColor.Black },
        ).also { tool -> asyncSamplerScope?.let(tool::useAsyncSampling) }
        private val recordingEyedropperTool = recordEvents(eyedropperTool)

        private fun createTool(erase: Boolean) = BrushTool(
            tool = ToolId.BRUSH,
            erase = erase,
            settings = { BrushSettings(size = 1f) },
            color = { RgbaColor.Black },
            begin = { _, _ -> RecordingStrokeSession(erase).also(sessions::add) },
            onResult = { if (it is CommandResult.Accepted) historySteps++ },
        )

        private fun recordEvents(delegate: ToolController) = object : ToolController {
            override val tool = delegate.tool

            override fun onPointer(event: ToolPointerEvent, ctx: ToolContext): Boolean {
                if (event.samples.isEmpty()) {
                    expectedDocPixels += Pixel(event.doc.x, event.doc.y)
                } else {
                    event.samples.forEach { sample ->
                        routedViewSamples += sample.x to sample.y
                        val doc = ctx.viewport.viewToDoc(ViewPoint(sample.x, sample.y))
                        expectedDocPixels += Pixel(doc.x, doc.y)
                    }
                }
                val consumed = delegate.onPointer(event, ctx)
                routedEvents.put(event)
                routedSignal.release()
                return consumed
            }
        }

        @androidx.compose.runtime.Composable
        fun Content() {
            SideEffect {
                // The first fit callback alone precedes recomposition; wait until fitted pointer nodes are applied.
                if (viewport.value.zoom > 1.01f) fitReady.countDown()
            }
            Box(Modifier.fillMaxSize().testTag(CANVAS_TAG)) {
                val foreground = foregroundColor.value
                CanvasViewport(
                    viewport = viewport.value,
                    onViewportChange = {
                        viewport.value = it
                    },
                    document = DocSize(200, 200),
                    modifier = Modifier.fillMaxSize(),
                    background = Color.Black,
                    tool = if (useEyedropper) recordingEyedropperTool else recordingBrushTool,
                    eraserTool = recordingEraserTool,
                    eyedropperTool = recordingEyedropperTool,
                    onHoverPointerKind = {
                        hoverKinds += it
                        hoverSignal.release()
                    },
                )
                Box(Modifier.size(12.dp).background(Color(foreground.r, foreground.g, foreground.b, foreground.a)))
            }
        }

        fun nextEvent(): ToolPointerEvent = requireNotNull(routedEvents.poll(5, TimeUnit.SECONDS)) {
            "Timed out waiting for CanvasViewport to route a tool event"
        }

        fun awaitRoutedEvent(): Boolean = routedSignal.tryAcquire(5, TimeUnit.SECONDS)

        fun awaitHoverKind(): Boolean = hoverSignal.tryAcquire(5, TimeUnit.SECONDS)

        fun documentPoint(x: Float, y: Float): Pair<Float, Float> =
            viewport.value.viewToDoc(ViewPoint(x, y)).let { it.x to it.y }

        fun close() {
            eyedropperTool.close()
            asyncSamplerScope?.cancel()
        }
    }

    private class RecordingStrokeSession(val eraseTool: Boolean) : StrokeSession {
        private val inputs = mutableListOf<FloatArray>()
        val committedSamples: List<FloatArray>
            get() = inputs.map(FloatArray::copyOf)
        var endCount = 0
            private set
        var cancelCount = 0
            private set
        var committedPixels: List<Pixel> = emptyList()
            private set

        override fun add(samples: FloatArray) {
            inputs += samples.copyOf()
        }

        override fun end(): CommandResult {
            endCount++
            // The recorder models the points that reach the raster commit boundary.
            committedPixels = inputs.flatMap { chunk ->
                (chunk.indices step 7).map { index -> Pixel(chunk[index], chunk[index + 1]) }
            }
            return CommandResult.Accepted(endCount.toLong())
        }

        override fun cancel() {
            cancelCount++
            inputs.clear()
        }
    }

    private data class Pixel(val x: Float, val y: Float)

    private companion object {
        const val CANVAS_TAG = "stylus-gesture-canvas"
    }
}

data class FrameSample(val totalNanos: Long, val deadlineNanos: Long)

class CanvasViewportGestureTestActivity : ComponentActivity() {
    private val frameSamples = java.util.Collections.synchronizedList(mutableListOf<FrameSample>())
    private var frameThread: HandlerThread? = null
    private var frameListener: Window.OnFrameMetricsAvailableListener? = null

    fun startFrameCapture() {
        stopFrameCapture()
        frameSamples.clear()
        val thread = HandlerThread("canvas-frame-metrics").also { it.start() }
        frameThread = thread
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            frameSamples += FrameSample(
                metrics.getMetric(FrameMetrics.TOTAL_DURATION),
                metrics.getMetric(FrameMetrics.DEADLINE),
            )
        }
        frameListener = listener
        window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
    }

    fun stopFrameCapture(): List<FrameSample> {
        frameListener?.let(window::removeOnFrameMetricsAvailableListener)
        frameListener = null
        frameThread?.let { thread ->
            thread.quitSafely()
            thread.join(1_000)
        }
        frameThread = null
        return synchronized(frameSamples) { frameSamples.toList() }
    }

    override fun onDestroy() {
        stopFrameCapture()
        super.onDestroy()
    }
}
