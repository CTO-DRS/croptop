package app.croptop.feature.editor.tools

import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.StrokeSession
import app.croptop.core.input.Modifiers
import app.croptop.core.input.PointerKind
import app.croptop.core.input.PointerSample
import app.croptop.core.model.BrushDabs
import app.croptop.core.model.BrushSample
import app.croptop.core.model.BrushSettings
import app.croptop.core.model.Curve
import app.croptop.core.model.CurvePoint
import app.croptop.core.model.Dab
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocSize
import app.croptop.core.model.RgbaColor
import app.croptop.core.model.SmoothingMode
import app.croptop.core.model.ToolId
import app.croptop.core.model.ViewPoint
import app.croptop.core.model.ViewportState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BrushToolTest {
    private val context = object : ToolContext {
        override val viewport = ViewportState(zoom = 1f, viewWidth = 200, viewHeight = 200)
        override val document = DocSize(200, 200)
        override fun updateViewport(state: ViewportState) = Unit
    }

    private fun event(phase: PointerPhase, x: Float, y: Float) = ToolPointerEvent(
        phase = phase,
        view = ViewPoint(x, y),
        doc = DocPoint(x, y),
        pressure = 1f,
        modifiers = Modifiers.None,
        downAt = ViewPoint(1f, 2f),
    )

    @Test
    fun `rope preview holds small movements then follows and catches up on commit`() {
        val settings = BrushSettings(size = 10f, smoothing = 0.5f, smoothingMode = SmoothingMode.ROPE)
        val samples = listOf(0f, 3f, 8f, 9f).map { BrushSample(x = it, y = 0f) }

        assertEquals(listOf(0f, 3f, 4f), stabilizedBrushPreviewSamples(settings, samples).map { it.x })
        assertEquals(listOf(0f, 3f, 4f, 9f), stabilizedBrushPreviewSamples(settings, samples, finish = true).map { it.x })
    }

    @Test
    fun `average preview applies native triangular window and flushes its tail`() {
        val settings = BrushSettings(smoothing = 0.5f, smoothingMode = SmoothingMode.AVERAGE)
        val samples = listOf(0f, 8f, 0f).map { BrushSample(x = 0f, y = it) }
        val active = stabilizedBrushPreviewSamples(settings, samples)

        assertEquals(3, active.size)
        assertEquals(listOf(0f, 16f / 3f, 16f / 6f), active.map { it.y })
        val finished = stabilizedBrushPreviewSamples(settings, samples, finish = true)
        assertTrue(finished.size > active.size)
        assertEquals(0f, finished.last().y, 0.5f)
    }

    @Test
    fun `canceled or replaced stroke invalidates an in flight preview generation`() {
        val generation = BrushPreviewGeneration()
        val firstStroke = generation.next()

        assertTrue(generation.isCurrent(firstStroke))
        generation.next()
        assertFalse(generation.isCurrent(firstStroke))

        val nextStroke = generation.next()
        assertTrue(generation.isCurrent(nextStroke))
    }

    @Test
    fun `release coordinate is streamed before stroke commits`() {
        val records = mutableListOf<FloatArray>()
        var ended = false
        val session = object : StrokeSession {
            override fun add(samples: FloatArray) {
                records += samples
            }

            override fun end(): CommandResult {
                ended = true
                return CommandResult.Accepted(1)
            }

            override fun cancel() = Unit
        }
        val results = mutableListOf<CommandResult>()
        val tool = BrushTool(
            ToolId.BRUSH,
            false,
            { BrushSettings() },
            { RgbaColor.Black },
            { _, _ -> session },
            onResult = { results += it },
        )

        tool.onPointer(event(PointerPhase.DOWN, 1f, 2f), context)
        tool.onPointer(event(PointerPhase.MOVE, 8f, 9f), context)
        tool.onPointer(event(PointerPhase.UP, 15f, 16f), context)

        assertEquals(listOf(1f, 8f, 15f), records.map { it[0] })
        assertEquals(listOf(2f, 9f, 16f), records.map { it[1] })
        assertEquals(3, records.size)
        assertEquals(7, records.first().size)
        assertTrue(ended)
        assertIs<CommandResult.Accepted>(results.single())
        assertEquals(1L, (results.single() as CommandResult.Accepted).sequence)
    }

    @Test
    fun `batched stylus history is calibrated and retains tilt orientation and time`() {
        val chunks = mutableListOf<FloatArray>()
        val session = object : StrokeSession {
            override fun add(samples: FloatArray) {
                chunks += samples
            }
            override fun end() = CommandResult.Accepted(1)
            override fun cancel() = Unit
        }
        val pressure = Curve(listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.2f), CurvePoint(1f, 1f)))
        val tool = BrushTool(
            ToolId.BRUSH,
            false,
            { BrushSettings() },
            { RgbaColor.Black },
            { _, _ -> session },
            onResult = {},
            pressureCurve = { pressure },
        )
        val down = event(PointerPhase.DOWN, 1f, 2f).copy(
            pointerKind = PointerKind.STYLUS,
            eventTimeMillis = 1_000,
            samples = listOf(PointerSample(1f, 2f, 0.25f, 0f, 0f, 1_000)),
        )
        val move = event(PointerPhase.MOVE, 20f, 30f).copy(
            pointerKind = PointerKind.STYLUS,
            eventTimeMillis = 1_020,
            samples = listOf(
                PointerSample(10f, 12f, 0.5f, 0.4f, 0.6f, 1_010),
                PointerSample(20f, 30f, 0.75f, 0.8f, 1.2f, 1_020),
            ),
        )

        tool.onPointer(down, context)
        tool.onPointer(move, context)

        assertEquals(2, chunks.size)
        assertEquals(14, chunks[1].size)
        assertEquals(
            listOf(10f, 12f, 20f, 30f),
            listOf(chunks[1][0], chunks[1][1], chunks[1][7], chunks[1][8]),
        )
        assertEquals(0.2f, chunks[1][2])
        assertEquals(0.6f, chunks[1][5])
        assertEquals(0.01f, chunks[1][6], 0.0001f)
        assertEquals(0.02f, chunks[1][13], 0.0001f)
        assertEquals(pressure.evaluate(0.75f), chunks[1][9], 0.0001f)
        assertEquals(1.2f, chunks[1][12])
        assertEquals(0.02f, chunks[1][13], 0.0001f)
    }

    @Test
    fun `predicted stylus samples appear in overlay preview but never reach stroke session`() {
        val actualChunks = mutableListOf<FloatArray>()
        val session = object : StrokeSession {
            override fun add(samples: FloatArray) {
                actualChunks += samples
            }

            override fun end() = CommandResult.Accepted(1)
            override fun cancel() = Unit
        }
        val tool = BrushTool(
            ToolId.BRUSH,
            false,
            { BrushSettings() },
            { RgbaColor.Black },
            { _, _ -> session },
            onResult = {},
        )
        val down = event(PointerPhase.DOWN, 1f, 1f).copy(
            pointerKind = PointerKind.STYLUS,
            samples = listOf(PointerSample(1f, 1f, 0.5f, 0f, 0f, 100)),
        )
        val move = event(PointerPhase.MOVE, 2f, 2f).copy(
            pointerKind = PointerKind.STYLUS,
            samples = listOf(PointerSample(2f, 2f, 0.6f, 0f, 0f, 110)),
            predictedSamples = listOf(PointerSample(3f, 3f, 0.7f, 0f, 0f, 120)),
        )

        tool.onPointer(down, context)
        tool.onPointer(move, context)

        assertEquals(listOf(1f, 2f, 3f), tool.samplesForOverlay().map { it.x })
        assertEquals(listOf(1f, 2f), actualChunks.map { it[0] })

        tool.onPointer(event(PointerPhase.UP, 4f, 4f), context)

        assertEquals(listOf(1f, 2f, 4f), actualChunks.map { it[0] })
        assertFalse(actualChunks.any { it[0] == 3f })
        assertTrue(tool.samplesForOverlay().isEmpty())
    }

    @Test
    fun `commit preview frame excludes provisional dabs`() {
        val settings = BrushSettings(size = 8f)
        val actual = listOf(BrushSample(x = 1f, y = 1f))
        val predicted = listOf(BrushSample(x = 41f, y = 1f))

        val frame = brushPreviewFrame(settings, actual, predicted)

        assertEquals(BrushDabs.dabs(settings, actual), frame.actual)
        assertTrue(frame.visible.size > frame.actual.size)
        assertTrue(frame.actual.all { it.x < 8f })
    }

    @Test
    fun `preview refreshes are coalesced to display cadence but release is forced`() {
        val gate = BrushPreviewRefreshGate()
        val refreshesAt240Hz = (0 until 240).count { index -> gate.shouldRefresh(index * 4_166_667L, force = index == 0) }
        assertTrue(refreshesAt240Hz in 59..61)
        assertTrue(gate.shouldRefresh(1_000_000_000L, force = true))

        val gateAt60Hz = BrushPreviewRefreshGate()
        val refreshesAt60Hz = (0 until 60).count { index -> gateAt60Hz.shouldRefresh(index * 16_666_667L, force = index == 0) }
        assertEquals(60, refreshesAt60Hz)
    }

    @Test
    fun `pending commit preview is seeded immediately and ids remain unique`() {
        val handoff = PendingBrushPreviewDabs()
        val lastVisibleFrame = listOf(Dab(10f, 12f, 8f, 0f, 1f, 0.6f))
        val first = handoff.begin(lastVisibleFrame)
        val second = handoff.begin(emptyList())

        assertTrue(first != second)
        assertEquals(lastVisibleFrame, handoff.values[first])
        assertEquals(emptyList(), handoff.values[second])

        val finalDabs = lastVisibleFrame + Dab(14f, 12f, 8f, 0f, 1f, 0.6f)
        handoff.update(first, finalDabs)
        assertEquals(finalDabs, handoff.values[first])
        handoff.finish(first)
        assertFalse(first in handoff.values)
        assertEquals(emptyList(), handoff.values[second])
    }

    @Test
    fun `active brush cancellation is reported once and never commits`() {
        var cancelled = false
        var cancelReports = 0
        var results = 0
        val session = object : StrokeSession {
            override fun add(samples: FloatArray) = Unit
            override fun end(): CommandResult {
                results++
                return CommandResult.Accepted(1)
            }
            override fun cancel() {
                cancelled = true
            }
        }
        val tool = BrushTool(
            ToolId.BRUSH,
            false,
            { BrushSettings() },
            { RgbaColor.Black },
            { _, _ -> session },
            onResult = { results++ },
            onCancel = { cancelReports++ },
        )

        tool.onPointer(event(PointerPhase.DOWN, 1f, 1f), context)
        tool.onPointer(event(PointerPhase.CANCEL, 1f, 1f), context)
        tool.onPointer(event(PointerPhase.CANCEL, 1f, 1f), context)

        assertTrue(cancelled)
        assertEquals(1, cancelReports)
        assertEquals(0, results)
    }
}
