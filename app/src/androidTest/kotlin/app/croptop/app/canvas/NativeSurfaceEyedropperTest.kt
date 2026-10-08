package app.croptop.app.canvas

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.croptop.core.designsystem.theme.CropTopTheme
import app.croptop.core.engine.NativeEngine
import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.OverlayState
import app.croptop.core.engine.api.SampleMode
import app.croptop.core.input.Modifiers
import app.croptop.core.input.PointerKind
import app.croptop.core.model.BackgroundContents
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocSize
import app.croptop.core.model.FillSource
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.RgbaColor
import app.croptop.core.model.ViewPoint
import app.croptop.core.model.ViewportState
import app.croptop.feature.editor.canvas.CanvasViewport
import app.croptop.feature.editor.canvas.LocalCanvasHost
import app.croptop.feature.editor.tools.EyedropperTool
import app.croptop.feature.editor.tools.PointerPhase
import app.croptop.feature.editor.tools.ToolContext
import app.croptop.feature.editor.tools.ToolPointerEvent
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies the production full-flavor Surface draws the same document pixels as NativeEngine sampling. */
@RunWith(AndroidJUnit4::class)
class NativeSurfaceEyedropperTest {
    @Test
    fun fullFlavorSurfaceKeeps120HzEyedropperToolDispatchResponsive() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = NativeEngine(context)
        val document = engine.newDocument(
            NewDocumentSpec(
                name = "Full surface eyedropper cadence",
                size = DocSize(200, 200),
                background = BackgroundContents.TRANSPARENT,
            ),
        )
        val expected = RgbaColor(0.35f, 0.6f, 0.8f)
        assertTrue(document.fill(FillSource.Solid(expected)) is CommandResult.Accepted)
        val samplerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val samplePoints = Collections.synchronizedList(mutableListOf<DocPoint>())
        val foregroundUpdates = Collections.synchronizedList(mutableListOf<RgbaColor>())
        val sampleDurations = Collections.synchronizedList(mutableListOf<Long>())
        val committed = CountDownLatch(1)
        val tool = EyedropperTool(
            sample = { point, mode, size ->
                samplePoints += point
                val started = SystemClock.elapsedRealtimeNanos()
                val color = document.trySample(point, mode, size)?.color
                sampleDurations += SystemClock.elapsedRealtimeNanos() - started
                color
            },
            onForeground = foregroundUpdates::add,
            onBackground = {},
            onCommit = { committed.countDown() },
            activeColor = { RgbaColor.Black },
        ).also { it.useAsyncSampling(samplerScope) }
        var viewportState by mutableStateOf(ViewportState())
        val fitReady = CountDownLatch(1)
        val scenario = ActivityScenario.launch(NativeSurfaceEyedropperActivity::class.java)
        try {
            scenario.onActivity { activity ->
                activity.setContent {
                    CompositionLocalProvider(LocalCanvasHost provides FlavorCanvasHost) {
                        CropTopTheme {
                            CanvasViewport(
                                viewport = viewportState,
                                onViewportChange = { viewportState = it },
                                document = DocSize(200, 200),
                                modifier = Modifier.fillMaxSize(),
                                background = Color.Black,
                                tool = tool,
                                eyedropperTool = tool,
                                handle = document,
                            )
                            SideEffect { if (viewportState.zoom > 1.01f) fitReady.countDown() }
                        }
                    }
                }
            }
            assertTrue("CanvasViewport did not fit the document", fitReady.await(5, TimeUnit.SECONDS))
            assertTrue("The document does not use its native viewport", document.rendersViewport)
            assertNotNull("The production host never created a valid document Surface", awaitDocumentSurface(scenario))

            val centerX = viewportState.viewWidth / 2f
            val centerY = viewportState.viewHeight / 2f
            val downTime = SystemClock.uptimeMillis()
            val dispatchDurations = mutableListOf<Long>()
            val downAt = ViewPoint(centerX - 45f, centerY - 35f)
            val contextForTool = object : ToolContext {
                override val viewport: ViewportState get() = this@NativeSurfaceEyedropperTest.run { viewportState }
                override val document = DocSize(200, 200)
                override fun updateViewport(state: ViewportState) {
                    viewportState = state
                }
            }
            dispatchToolEvent(scenario, tool, contextForTool, PointerPhase.DOWN, downAt, downAt, downTime, dispatchDurations)
            repeat(120) { index ->
                val eventTime = downTime + (index + 1) * 8L
                val started = SystemClock.elapsedRealtimeNanos()
                dispatchToolEvent(
                    scenario,
                    tool,
                    contextForTool,
                    PointerPhase.MOVE,
                    ViewPoint(centerX - 45f + index * 0.7f, centerY - 35f + index * 0.5f),
                    downAt,
                    eventTime,
                    dispatchDurations,
                )
                dispatchDurations[dispatchDurations.lastIndex] = SystemClock.elapsedRealtimeNanos() - started
                SystemClock.sleep(8)
            }
            val releaseTime = SystemClock.uptimeMillis()
            val releaseAt = ViewPoint(centerX + 40f, centerY + 30f)
            dispatchToolEvent(scenario, tool, contextForTool, PointerPhase.UP, releaseAt, downAt, releaseTime, dispatchDurations)
            assertTrue("Eyedropper did not acknowledge its final sample", committed.await(10, TimeUnit.SECONDS))
            assertTrue("Native Surface sampler was never invoked", samplePoints.isNotEmpty())
            assertTrue("No color preview reached the UI", foregroundUpdates.isNotEmpty())
            val releasePoint = viewportState.viewToDoc(ViewPoint(centerX + 40f, centerY + 30f))
            assertEquals(releasePoint, samplePoints.last())
            val finalSample = requireNotNull(document.trySample(releasePoint, SampleMode.MERGED))
            val committedColor = foregroundUpdates.last()
            assertEquals(finalSample.color.r, committedColor.r, 0.01f)
            assertEquals(finalSample.color.g, committedColor.g, 0.01f)
            assertEquals(finalSample.color.b, committedColor.b, 0.01f)

            val sortedDispatch = dispatchDurations.sorted()
            val p95DispatchMs = sortedDispatch[(sortedDispatch.size * 0.95).toInt().coerceAtMost(sortedDispatch.lastIndex)] / 1_000_000.0
            val sortedSamples = sampleDurations.sorted()
            val p95SampleMs = sortedSamples[(sortedSamples.size * 0.95).toInt().coerceAtMost(sortedSamples.lastIndex)] / 1_000_000.0
            android.util.Log.i(
                "FullSurfaceEyedropper",
                "cadence=120Hz moves=120 samples=${samplePoints.size} colors=${foregroundUpdates.size} " +
                    "dispatchP95Ms=$p95DispatchMs sampleP95Ms=$p95SampleMs",
            )
            assertTrue("Pointer dispatch p95 exceeded a 60 Hz frame budget: ${p95DispatchMs}ms", p95DispatchMs < 16.7)
        } finally {
            tool.close()
            samplerScope.cancel()
            scenario.close()
            document.close()
            engine.close()
        }
    }

    @Test
    fun fullFlavorSurfaceRendersDocumentColorReturnedByNativeSampler() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = NativeEngine(context)
        val document = engine.newDocument(
            NewDocumentSpec(
                name = "Full surface eyedropper parity",
                size = DocSize(200, 200),
                background = BackgroundContents.TRANSPARENT,
            ),
        )
        val expected = RgbaColor(0.35f, 0.6f, 0.8f)
        assertTrue(document.fill(FillSource.Solid(expected)) is CommandResult.Accepted)
        var viewport by mutableStateOf(ViewportState())
        val fitReady = CountDownLatch(1)
        val scenario = ActivityScenario.launch(NativeSurfaceEyedropperActivity::class.java)

        try {
            scenario.onActivity { activity ->
                activity.setContent {
                    CompositionLocalProvider(LocalCanvasHost provides FlavorCanvasHost) {
                        CropTopTheme {
                            CanvasViewport(
                                viewport = viewport,
                                onViewportChange = { viewport = it },
                                document = DocSize(200, 200),
                                modifier = Modifier.fillMaxSize(),
                                background = Color.Black,
                                handle = document,
                                engineOverlays = OverlayState(),
                            )
                            SideEffect { if (viewport.zoom > 1.01f) fitReady.countDown() }
                        }
                    }
                }
            }

            assertTrue("CanvasViewport did not fit the document", fitReady.await(5, TimeUnit.SECONDS))
            assertTrue("The document does not use its native viewport", document.rendersViewport)
            runBlocking { document.awaitViewportPreview() }
            val surface = awaitDocumentSurface(scenario)
            assertNotNull("The production host never created a valid document Surface", surface)
            val nativeSample = document.trySample(
                viewport.viewToDoc(ViewPoint(viewport.viewWidth / 2f, viewport.viewHeight / 2f)),
                SampleMode.MERGED,
            )
            assertNotNull("The native eyedropper sampler returned no pixel", nativeSample)
            val sampleColor = nativeSample!!.color
            assertEquals(expected.r, sampleColor.r, 0.01f)
            assertEquals(expected.g, sampleColor.g, 0.01f)
            assertEquals(expected.b, sampleColor.b, 0.01f)

            val view = requireNotNull(surface)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val copyResult = copySurfaceWhenFirstBufferArrives(view, bitmap)
            assertEquals("The native document Surface could not be copied", PixelCopy.SUCCESS, copyResult)

            // The fitted square image is centered in the host; checking its center verifies actual Surface output.
            val actualPixel = bitmap.getPixel(view.width / 2, view.height / 2)
            bitmap.recycle()
            assertColorNear(expected.toArgb(), actualPixel, tolerance = 28)
            android.util.Log.i(
                "FullSurfaceEyedropper",
                "surface=${view.width}x${view.height} sample=${sampleColor.hex} pixel=#${actualPixel.toUInt().toString(16)}",
            )
        } finally {
            scenario.close()
            document.close()
            engine.close()
        }
    }

    @Test
    fun fullFlavorSurfaceShowsTheSoftProofAndTheGamutWarningOnTheRealViewport() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = NativeEngine(context)
        val document = engine.newDocument(
            NewDocumentSpec(name = "Soft proof surface", size = DocSize(200, 200), background = BackgroundContents.TRANSPARENT),
        )
        // Pure sRGB blue is outside any CMYK press gamut.
        assertTrue(document.fill(FillSource.Solid(RgbaColor(0f, 0f, 1f))) is CommandResult.Accepted)
        var viewport by mutableStateOf(ViewportState())
        val fitReady = CountDownLatch(1)
        val scenario = ActivityScenario.launch(NativeSurfaceEyedropperActivity::class.java)
        try {
            scenario.onActivity { activity ->
                activity.setContent {
                    CompositionLocalProvider(LocalCanvasHost provides FlavorCanvasHost) {
                        CropTopTheme {
                            CanvasViewport(
                                viewport = viewport,
                                onViewportChange = { viewport = it },
                                document = DocSize(200, 200),
                                modifier = Modifier.fillMaxSize(),
                                background = Color.Black,
                                handle = document,
                                engineOverlays = OverlayState(),
                            )
                            SideEffect { if (viewport.zoom > 1.01f) fitReady.countDown() }
                        }
                    }
                }
            }
            assertTrue("CanvasViewport did not fit the document", fitReady.await(5, TimeUnit.SECONDS))
            runBlocking { document.awaitViewportPreview() }
            val view = requireNotNull(awaitDocumentSurface(scenario)) { "no document Surface" }

            fun centerPixel(): Int {
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                assertEquals(PixelCopy.SUCCESS, copySurfaceWhenFirstBufferArrives(view, bitmap))
                return bitmap.getPixel(view.width / 2, view.height / 2).also { bitmap.recycle() }
            }
            /** Frames arrive on the next display frame, so wait for the picture to settle on something other than [not]. */
            fun centerPixelOtherThan(not: Int): Int {
                val deadline = SystemClock.uptimeMillis() + 5_000
                var pixel = centerPixel()
                while (pixel == not && SystemClock.uptimeMillis() < deadline) {
                    SystemClock.sleep(100)
                    pixel = centerPixel()
                }
                return pixel
            }

            val plain = centerPixel()
            assertColorNear(RgbaColor(0f, 0f, 1f).toArgb(), plain, tolerance = 28)

            assertTrue(document.setSoftProof(app.croptop.core.model.SoftProofSettings()) is CommandResult.Accepted)
            val proofed = centerPixelOtherThan(plain)
            android.util.Log.i("SoftProofSurface", "plain=#${plain.toUInt().toString(16)} proofed=#${proofed.toUInt().toString(16)}")
            assertTrue("press blue is duller than sRGB blue: #${proofed.toUInt().toString(16)}", (proofed shr 16 and 0xFF) + (proofed shr 8 and 0xFF) > 40)

            assertTrue(document.setSoftProof(app.croptop.core.model.SoftProofSettings(gamutWarning = true)) is CommandResult.Accepted)
            val warned = centerPixelOtherThan(proofed)
            android.util.Log.i("SoftProofSurface", "warned=#${warned.toUInt().toString(16)}")
            assertColorNear(0xFF808080.toInt(), warned, tolerance = 20)

            assertTrue(document.setSoftProof(null) is CommandResult.Accepted)
            val back = centerPixelOtherThan(warned)
            assertColorNear(RgbaColor(0f, 0f, 1f).toArgb(), back, tolerance = 28)
        } finally {
            scenario.close()
            document.close()
            engine.close()
        }
    }

    @Test
    fun fullFlavorSurfaceShowsFullResolutionDetailWhenZoomedIntoALargeDocument() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = NativeEngine(context)
        // 4000×3000 is larger than the 2048 px preview: only a re-render of the visible region can show one-pixel detail at 100%.
        val document = engine.newDocument(
            NewDocumentSpec(name = "Sharp zoom", size = DocSize(4000, 3000), background = BackgroundContents.WHITE),
        )
        // Alternating one-pixel black and white columns in the middle of the picture (even columns black).
        val layer = checkNotNull(document.layers.value.primary)
        assertTrue(
            document.applyPixelPatch(
                layer,
                app.croptop.core.model.DocRect(1900, 1400, 2100, 1600),
                app.croptop.core.engine.api.PixelPatch(200, 200, ByteArray(200 * 200 * 4) { i -> if (i % 4 == 3) -1 else if ((i / 4) % 2 == 0) 0 else -1 }),
                "Stripes",
            ) is CommandResult.Accepted,
        )
        var viewport by mutableStateOf(ViewportState())
        val fitReady = CountDownLatch(1)
        val scenario = ActivityScenario.launch(NativeSurfaceEyedropperActivity::class.java)
        try {
            scenario.onActivity { activity ->
                activity.setContent {
                    CompositionLocalProvider(LocalCanvasHost provides FlavorCanvasHost) {
                        CropTopTheme {
                            CanvasViewport(
                                viewport = viewport,
                                onViewportChange = { viewport = it },
                                document = DocSize(4000, 3000),
                                modifier = Modifier.fillMaxSize(),
                                background = Color.Black,
                                handle = document,
                                engineOverlays = OverlayState(),
                            )
                            SideEffect { if (viewport.zoom > 0.05f) fitReady.countDown() }
                        }
                    }
                }
            }
            assertTrue("CanvasViewport did not fit the document", fitReady.await(5, TimeUnit.SECONDS))
            runBlocking { document.awaitViewportPreview() }
            val view = requireNotNull(awaitDocumentSurface(scenario)) { "no document Surface" }
            // Centre the document's middle (2000, 1500) in the view at exactly 100%.
            val centred = viewport.copy(zoom = 1f, panX = viewport.viewWidth / 2f - 2000f, panY = viewport.viewHeight / 2f - 1500f)
            scenario.onActivity { viewport = centred }

            fun stripeContrast(): Int {
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                assertEquals(PixelCopy.SUCCESS, copySurfaceWhenFirstBufferArrives(view, bitmap))
                val y = view.height / 2
                var best = 0
                for (x in view.width / 2 - 20 until view.width / 2 + 20) {
                    best = maxOf(best, kotlin.math.abs((bitmap.getPixel(x, y) shr 8 and 0xFF) - (bitmap.getPixel(x + 1, y) shr 8 and 0xFF)))
                }
                bitmap.recycle()
                return best
            }
            // The sharp region arrives after the view has rested; before that the 2048 px preview averages the stripes to grey.
            // The emulator composites on its CPU, so allow it a minute; a device does this in a fraction of a second.
            val started = SystemClock.uptimeMillis()
            val deadline = started + 90_000
            var contrast = stripeContrast()
            while (contrast < 200 && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(250)
                contrast = stripeContrast()
            }
            android.util.Log.i("SharpZoom", "neighbouring-pixel contrast at 100% = $contrast after ${SystemClock.uptimeMillis() - started} ms")
            assertTrue("one-pixel stripes are resolved at 100% (contrast $contrast)", contrast >= 200)
        } finally {
            scenario.close()
            document.close()
            engine.close()
        }
    }

    private fun awaitDocumentSurface(scenario: ActivityScenario<NativeSurfaceEyedropperActivity>): SurfaceView? {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            var found: SurfaceView? = null
            scenario.onActivity { activity ->
                fun visit(view: View) {
                    if (found == null && view is SurfaceView && view.holder.surface.isValid && view.width > 0 && view.height > 0) {
                        found = view
                    }
                    if (view is ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
                }
                visit(activity.findViewById(android.R.id.content))
            }
            if (found != null) return found
            SystemClock.sleep(25)
        }
        return null
    }

    /** Surface validity precedes its first posted frame; retry only PixelCopy's no-buffer-yet result. */
    private fun copySurfaceWhenFirstBufferArrives(view: SurfaceView, bitmap: Bitmap): Int {
        val deadline = SystemClock.uptimeMillis() + 5_000
        var result = PixelCopy.ERROR_UNKNOWN
        do {
            val copied = CountDownLatch(1)
            PixelCopy.request(
                view,
                bitmap,
                { copyResult ->
                    result = copyResult
                    copied.countDown()
                },
                Handler(Looper.getMainLooper()),
            )
            val remaining = (deadline - SystemClock.uptimeMillis()).coerceAtLeast(1L)
            if (!copied.await(remaining, TimeUnit.MILLISECONDS)) return PixelCopy.ERROR_TIMEOUT
            if (result != PixelCopy.ERROR_SOURCE_NO_DATA) return result
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        return result
    }

    private fun dispatchToolEvent(
        scenario: ActivityScenario<NativeSurfaceEyedropperActivity>,
        tool: EyedropperTool,
        context: ToolContext,
        phase: PointerPhase,
        view: ViewPoint,
        downAt: ViewPoint,
        eventTime: Long,
        durations: MutableList<Long>,
    ) {
        val started = SystemClock.elapsedRealtimeNanos()
        scenario.onActivity {
            tool.onPointer(
                ToolPointerEvent(
                    phase = phase,
                    view = view,
                    doc = context.viewport.viewToDoc(view),
                    pressure = 0.8f,
                    modifiers = Modifiers(),
                    downAt = downAt,
                    pointerKind = PointerKind.STYLUS,
                    eventTimeMillis = eventTime,
                ),
                context,
            )
        }
        durations += SystemClock.elapsedRealtimeNanos() - started
    }

    private fun assertColorNear(expected: Int, actual: Int, tolerance: Int) {
        for (shift in intArrayOf(16, 8, 0)) {
            val expectedChannel = expected shr shift and 0xFF
            val actualChannel = actual shr shift and 0xFF
            assertTrue(
                "Surface pixel channel mismatch: expected #${expected.toUInt().toString(16)}, actual #${actual.toUInt().toString(16)}",
                kotlin.math.abs(expectedChannel - actualChannel) <= tolerance,
            )
        }
    }
}
