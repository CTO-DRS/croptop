package app.croptop.feature.editor.tools

import app.croptop.core.input.Modifiers
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocSize
import app.croptop.core.model.RgbaColor
import app.croptop.core.model.ViewPoint
import app.croptop.core.model.ViewportState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class ViewToolsTest {
    private class Ctx(override var viewport: ViewportState) : ToolContext {
        override val document = DocSize(4000, 3000)

        override fun updateViewport(state: ViewportState) {
            viewport = state
        }
    }

    private fun ev(phase: PointerPhase, x: Float, y: Float, down: ViewPoint, m: Modifiers = Modifiers.None, clicks: Int = 1) =
        ToolPointerEvent(phase, ViewPoint(x, y), ViewportState().viewToDoc(ViewPoint(x, y)), 1f, m, down, clicks)

    private val base = ViewportState(zoom = 0.25f, viewWidth = 1000, viewHeight = 800)

    @Test
    fun `hand drag pans by delta`() {
        val ctx = Ctx(base)
        val completed = mutableListOf<String>()
        val t = HandTool().apply { onActionCompleted = completed::add }
        val d = ViewPoint(100f, 100f)
        t.onPointer(ev(PointerPhase.DOWN, 100f, 100f, d), ctx)
        t.onPointer(ev(PointerPhase.MOVE, 130f, 90f, d), ctx)
        assertEquals(30f, ctx.viewport.panX)
        assertEquals(-10f, ctx.viewport.panY)
        assertEquals(emptyList(), completed)
        t.onPointer(ev(PointerPhase.UP, 130f, 90f, d), ctx)
        assertEquals(listOf("Pan canvas view"), completed)
    }

    @Test
    fun `zoom click steps in, alt click steps out, double click is 100 percent`() {
        val ctx = Ctx(base)
        val completed = mutableListOf<String>()
        val t = ZoomTool().apply { onActionCompleted = completed::add }
        val d = ViewPoint(500f, 400f)
        t.onPointer(ev(PointerPhase.DOWN, 500f, 400f, d), ctx)
        t.onPointer(ev(PointerPhase.UP, 500f, 400f, d), ctx)
        assertEquals(0.3333f, ctx.viewport.zoom)
        t.onPointer(ev(PointerPhase.DOWN, 500f, 400f, d, Modifiers(alt = true)), ctx)
        t.onPointer(ev(PointerPhase.UP, 500f, 400f, d, Modifiers(alt = true)), ctx)
        assertEquals(0.25f, ctx.viewport.zoom)
        t.onPointer(ev(PointerPhase.DOWN, 500f, 400f, d), ctx)
        t.onPointer(ev(PointerPhase.UP, 500f, 400f, d, clicks = 2), ctx)
        assertEquals(1f, ctx.viewport.zoom)
        assertEquals(listOf("Zoom in", "Zoom out", "Set zoom to actual pixels"), completed)
    }

    @Test
    fun `scrubby zoom doubles per 200px and keeps anchor`() {
        val ctx = Ctx(base)
        val completed = mutableListOf<String>()
        val t = ZoomTool().apply { onActionCompleted = completed::add }
        val d = ViewPoint(300f, 300f)
        val anchorDoc = base.viewToDoc(d)
        t.onPointer(ev(PointerPhase.DOWN, 300f, 300f, d), ctx)
        t.onPointer(ev(PointerPhase.MOVE, 500f, 300f, d), ctx)
        t.onPointer(ev(PointerPhase.UP, 500f, 300f, d), ctx)
        assertEquals(0.5f, ctx.viewport.zoom, 1e-4f)
        val after = ctx.viewport.viewToDoc(d)
        assertEquals(anchorDoc.x, after.x, 0.5f)
        assertEquals(listOf("Scrub zoom"), completed)
    }

    @Test
    fun `rotate view reports once when the gesture commits`() {
        val ctx = Ctx(base)
        val completed = mutableListOf<String>()
        val t = RotateViewTool().apply { onActionCompleted = completed::add }
        val d = ViewPoint(900f, 400f)
        t.onPointer(ev(PointerPhase.DOWN, 900f, 400f, d), ctx)
        t.onPointer(ev(PointerPhase.MOVE, 500f, 800f, d), ctx)
        t.onPointer(ev(PointerPhase.UP, 500f, 800f, d), ctx)
        assertEquals(listOf("Rotate canvas view"), completed)
    }

    @Test
    fun `rotate with shift snaps to 15 degrees`() {
        val ctx = Ctx(base)
        val t = RotateViewTool()
        val d = ViewPoint(900f, 400f) // right of center (500,400): angle 0
        t.onPointer(ev(PointerPhase.DOWN, 900f, 400f, d), ctx)
        t.onPointer(ev(PointerPhase.MOVE, 500f + 400f * 0.9063f, 400f + 400f * 0.4226f, d, Modifiers(shift = true)), ctx) // 25°
        assertEquals(30f, ctx.viewport.rotationDegrees, 0.01f)
        t.onPointer(ev(PointerPhase.CANCEL, 0f, 0f, d), ctx)
        assertTrue(ctx.viewport.rotationDegrees == 0f)
    }
}

class NavigatorMathTest {
    @Test
    fun `zoom slider is logarithmic and round trips`() {
        assertEquals(0f, app.croptop.feature.editor.panels.zoomToSlider(ViewportState.MIN_ZOOM), 1e-3f)
        assertEquals(100f, app.croptop.feature.editor.panels.zoomToSlider(ViewportState.MAX_ZOOM), 1e-3f)
        listOf(0.05f, 0.33f, 1f, 8f).forEach { z ->
            assertEquals(z, app.croptop.feature.editor.panels.sliderToZoom(app.croptop.feature.editor.panels.zoomToSlider(z)), z * 1e-3f)
        }
    }
}

class EyedropperToolTest {
    @Test
    fun `click sets foreground, alt sets background, release commits`() {
        val red = app.croptop.core.model.RgbaColor(1f, 0f, 0f)
        var fg: app.croptop.core.model.RgbaColor? = null
        var bg: app.croptop.core.model.RgbaColor? = null
        val committed = mutableListOf<app.croptop.core.model.RgbaColor>()
        val sizes = mutableListOf<Int>()
        val t = EyedropperTool({ _, _, size ->
            sizes += size
            red
        }, { fg = it }, { bg = it }, { committed += it }) { red }
        val ctx = object : ToolContext {
            override val viewport = ViewportState(zoom = 1f, viewWidth = 100, viewHeight = 100)
            override val document = DocSize(100, 100)
            override fun updateViewport(state: ViewportState) = Unit
        }
        val at = ViewPoint(10f, 10f)
        fun ev(phase: PointerPhase, m: Modifiers = Modifiers.None) = ToolPointerEvent(phase, at, app.croptop.core.model.DocPoint(10f, 10f), 1f, m, at)
        t.onPointer(ev(PointerPhase.DOWN), ctx)
        t.onPointer(ev(PointerPhase.UP), ctx)
        assertEquals(red, fg)
        t.onPointer(ev(PointerPhase.DOWN, Modifiers(alt = true)), ctx)
        t.onPointer(ev(PointerPhase.UP, Modifiers(alt = true)), ctx)
        assertEquals(red, bg)
        assertEquals(2, committed.size)
        assertEquals(listOf(1, 1), sizes)
    }

    @Test
    fun `selected sample size is passed to the engine`() {
        val sampledSizes = mutableListOf<Int>()
        val color = app.croptop.core.model.RgbaColor(0.2f, 0.4f, 0.6f)
        val tool = EyedropperTool({ _, _, size ->
            sampledSizes += size
            color
        }, {}, {}, {}) { color }
        tool.sampleSize = 31
        val ctx = object : ToolContext {
            override val viewport = ViewportState(zoom = 1f, viewWidth = 100, viewHeight = 100)
            override val document = DocSize(100, 100)
            override fun updateViewport(state: ViewportState) = Unit
        }
        val at = ViewPoint(10f, 10f)
        tool.onPointer(
            ToolPointerEvent(PointerPhase.DOWN, at, app.croptop.core.model.DocPoint(10f, 10f), 1f, Modifiers.None, at),
            ctx,
        )
        assertEquals(listOf(31), sampledSizes)
    }

    @Test
    fun `drag samples every pointer update and the release endpoint before commit`() {
        val points = mutableListOf<Float>()
        val committed = mutableListOf<app.croptop.core.model.RgbaColor>()
        fun colorAt(x: Float) = app.croptop.core.model.RgbaColor(x / 10f, 0f, 0f)
        val tool = EyedropperTool(
            { point, _, _ ->
                points += point.x
                colorAt(point.x)
            },
            {},
            {},
            { committed += it },
        ) { app.croptop.core.model.RgbaColor.Black }
        val ctx = object : ToolContext {
            override val viewport = ViewportState(zoom = 1f, viewWidth = 100, viewHeight = 100)
            override val document = DocSize(100, 100)
            override fun updateViewport(state: ViewportState) = Unit
        }
        fun event(phase: PointerPhase, x: Float): ToolPointerEvent {
            val view = ViewPoint(x, 10f)
            return ToolPointerEvent(phase, view, app.croptop.core.model.DocPoint(x, 10f), 1f, Modifiers.None, view)
        }

        tool.onPointer(event(PointerPhase.DOWN, 1f), ctx)
        tool.onPointer(event(PointerPhase.MOVE, 2f), ctx)
        tool.onPointer(event(PointerPhase.MOVE, 3f), ctx)
        tool.onPointer(event(PointerPhase.UP, 4f), ctx)

        assertEquals(listOf(1f, 2f, 3f, 4f), points)
        assertEquals(listOf(colorAt(4f)), committed)
    }

    @Test
    fun `async latest-point sampler retries a declined release and commits that endpoint`() = runTest {
        val releaseColor = RgbaColor(0.2f, 0.4f, 0.8f)
        val sampledPoints = mutableListOf<DocPoint>()
        val committed = mutableListOf<RgbaColor>()
        var releaseAttempts = 0
        val samplerDispatcher = StandardTestDispatcher(testScheduler)
        val samplerScope = CoroutineScope(samplerDispatcher + SupervisorJob())
        val tool = EyedropperTool(
            sample = { point, _, _ ->
                sampledPoints += point
                if (point == DocPoint(4f, 10f) && ++releaseAttempts == 1) null else releaseColor
            },
            onForeground = {},
            onBackground = {},
            onCommit = committed::add,
            activeColor = { RgbaColor.Black },
        )
        tool.useAsyncSampling(samplerScope, samplerDispatcher)
        val ctx = object : ToolContext {
            override val viewport = ViewportState(zoom = 1f, viewWidth = 100, viewHeight = 100)
            override val document = DocSize(100, 100)
            override fun updateViewport(state: ViewportState) = Unit
        }
        fun event(phase: PointerPhase, x: Float) = ToolPointerEvent(
            phase = phase,
            view = ViewPoint(x, 10f),
            doc = DocPoint(x, 10f),
            pressure = 1f,
            modifiers = Modifiers.None,
            downAt = ViewPoint(1f, 10f),
        )

        runCurrent()
        tool.onPointer(event(PointerPhase.DOWN, 1f), ctx)
        tool.onPointer(event(PointerPhase.MOVE, 2f), ctx)
        tool.onPointer(event(PointerPhase.UP, 4f), ctx)
        advanceUntilIdle()

        assertEquals(2, sampledPoints.count { it == DocPoint(4f, 10f) })
        assertEquals(listOf(releaseColor), committed)
        tool.close()
    }

    @Test
    fun `async sampler retains every point in a paced 120 hertz stream when it keeps up`() = runTest {
        val color = RgbaColor(0.2f, 0.4f, 0.8f)
        val sampledPoints = mutableListOf<DocPoint>()
        val foregroundUpdates = mutableListOf<RgbaColor>()
        val committed = mutableListOf<RgbaColor>()
        val samplerDispatcher = StandardTestDispatcher(testScheduler)
        val samplerScope = CoroutineScope(samplerDispatcher + SupervisorJob())
        val tool = EyedropperTool(
            sample = { point, _, _ ->
                sampledPoints += point
                color
            },
            onForeground = foregroundUpdates::add,
            onBackground = {},
            onCommit = committed::add,
            activeColor = { RgbaColor.Black },
        )
        tool.useAsyncSampling(samplerScope, samplerDispatcher)
        val ctx = object : ToolContext {
            override val viewport = ViewportState(zoom = 1f, viewWidth = 200, viewHeight = 100)
            override val document = DocSize(200, 100)
            override fun updateViewport(state: ViewportState) = Unit
        }
        fun event(phase: PointerPhase, x: Float): ToolPointerEvent {
            val point = DocPoint(x, 10f)
            val view = ViewPoint(x, 10f)
            return ToolPointerEvent(
                phase = phase,
                view = view,
                doc = point,
                pressure = 1f,
                modifiers = Modifiers.None,
                downAt = ViewPoint(0f, 10f),
            )
        }

        try {
            tool.onPointer(event(PointerPhase.DOWN, 0f), ctx)
            runCurrent()
            for (index in 1..120) {
                advanceTimeBy(8)
                tool.onPointer(event(PointerPhase.MOVE, index.toFloat()), ctx)
                runCurrent()
            }
            advanceTimeBy(8)
            tool.onPointer(event(PointerPhase.UP, 121f), ctx)
            runCurrent()

            val expectedPoints = (0..121).map { DocPoint(it.toFloat(), 10f) }
            assertEquals(expectedPoints, sampledPoints)
            assertEquals(List(expectedPoints.size) { color }, foregroundUpdates)
            assertEquals(listOf(color), committed)
        } finally {
            tool.close()
            samplerScope.cancel()
        }
    }

    @Test
    fun `async sample completing after cancel cannot update or commit the color`() = runTest {
        val sampleStarted = java.util.concurrent.CountDownLatch(1)
        val finishSample = java.util.concurrent.CountDownLatch(1)
        val sampleDispatcher = java.util.concurrent.Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val samplerDispatcher = StandardTestDispatcher(testScheduler)
        val samplerScope = CoroutineScope(samplerDispatcher + SupervisorJob())
        var foregroundUpdates = 0
        val committed = mutableListOf<RgbaColor>()
        val tool = EyedropperTool(
            sample = { _, _, _ ->
                sampleStarted.countDown()
                check(finishSample.await(5, java.util.concurrent.TimeUnit.SECONDS))
                RgbaColor(0.2f, 0.4f, 0.8f)
            },
            onForeground = { foregroundUpdates++ },
            onBackground = {},
            onCommit = committed::add,
            activeColor = { RgbaColor.Black },
        )
        tool.useAsyncSampling(samplerScope, sampleDispatcher)
        val ctx = object : ToolContext {
            override val viewport = ViewportState(zoom = 1f, viewWidth = 100, viewHeight = 100)
            override val document = DocSize(100, 100)
            override fun updateViewport(state: ViewportState) = Unit
        }
        val at = ViewPoint(10f, 10f)
        val point = DocPoint(10f, 10f)
        fun event(phase: PointerPhase) = ToolPointerEvent(
            phase = phase,
            view = at,
            doc = point,
            pressure = 1f,
            modifiers = Modifiers.None,
            downAt = at,
        )

        try {
            tool.onPointer(event(PointerPhase.DOWN), ctx)
            tool.onPointer(event(PointerPhase.UP), ctx)
            runCurrent()
            assertTrue(sampleStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))

            tool.onPointer(event(PointerPhase.CANCEL), ctx)
            finishSample.countDown()
            advanceUntilIdle()

            assertEquals(0, foregroundUpdates)
            assertEquals(emptyList<RgbaColor>(), committed)
            assertNull(tool.ringPreview)
        } finally {
            finishSample.countDown()
            tool.close()
            sampleDispatcher.close()
        }
    }

    @Test
    fun `alt target change on release refreshes the previous color in the ring`() {
        val red = app.croptop.core.model.RgbaColor(1f, 0f, 0f)
        val green = app.croptop.core.model.RgbaColor(0f, 1f, 0f)
        val blue = app.croptop.core.model.RgbaColor(0f, 0f, 1f)
        var foreground: app.croptop.core.model.RgbaColor? = null
        var background: app.croptop.core.model.RgbaColor? = null
        val tool = EyedropperTool({ _, _, _ -> blue }, { foreground = it }, { background = it }, {}) {
            if (it) green else red
        }
        val ctx = object : ToolContext {
            override val viewport = ViewportState(zoom = 1f, viewWidth = 100, viewHeight = 100)
            override val document = DocSize(100, 100)
            override fun updateViewport(state: ViewportState) = Unit
        }
        fun event(phase: PointerPhase, modifiers: Modifiers = Modifiers.None): ToolPointerEvent {
            val view = ViewPoint(10f, 10f)
            return ToolPointerEvent(phase, view, app.croptop.core.model.DocPoint(10f, 10f), 1f, modifiers, view)
        }

        tool.onPointer(event(PointerPhase.DOWN), ctx)
        tool.onPointer(event(PointerPhase.UP, Modifiers(alt = true)), ctx)

        assertEquals(blue, foreground)
        assertEquals(blue, background)
        assertEquals(blue to green, tool.ringPreview)
    }

    @Test
    fun `ring previous color follows foreground or background target and clears on cancel`() {
        val red = app.croptop.core.model.RgbaColor(1f, 0f, 0f)
        val green = app.croptop.core.model.RgbaColor(0f, 1f, 0f)
        val blue = app.croptop.core.model.RgbaColor(0f, 0f, 1f)
        var fg: app.croptop.core.model.RgbaColor? = null
        var bg: app.croptop.core.model.RgbaColor? = null
        val tool = EyedropperTool({ _, _, _ -> blue }, { fg = it }, { bg = it }, {}) { background -> if (background) green else red }
        val ctx = object : ToolContext {
            override val viewport = ViewportState(zoom = 1f, viewWidth = 100, viewHeight = 100)
            override val document = DocSize(100, 100)
            override fun updateViewport(state: ViewportState) = Unit
        }
        val at = ViewPoint(10f, 10f)
        fun ev(phase: PointerPhase, modifiers: Modifiers = Modifiers.None) =
            ToolPointerEvent(phase, at, app.croptop.core.model.DocPoint(10f, 10f), 1f, modifiers, at)

        tool.onPointer(ev(PointerPhase.DOWN), ctx)
        assertEquals(blue to red, tool.ringPreview)
        tool.onPointer(ev(PointerPhase.MOVE, Modifiers(alt = true)), ctx)
        assertEquals(blue to green, tool.ringPreview)
        assertEquals(blue, fg)
        assertEquals(blue, bg)
        tool.onPointer(ev(PointerPhase.UP, Modifiers(alt = true)), ctx)
        assertEquals(blue to green, tool.ringPreview) // The preview stays visible until leave or cancel.

        tool.onPointer(ev(PointerPhase.CANCEL), ctx)
        assertEquals(null, tool.ringPreview)

        tool.onPointer(ev(PointerPhase.DOWN), ctx)
        tool.onPointer(ev(PointerPhase.UP), ctx)
        assertEquals(blue to red, tool.ringPreview)
        tool.clearRing() // Canvas pointer-leave callback.
        assertEquals(null, tool.ringPreview)
    }
}
