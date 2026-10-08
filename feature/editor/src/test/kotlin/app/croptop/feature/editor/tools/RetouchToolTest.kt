package app.croptop.feature.editor.tools

import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.RetouchEffect
import app.croptop.core.engine.api.StrokeSampleLayout
import app.croptop.core.engine.api.StrokeSession
import app.croptop.core.input.Modifiers
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocSize
import app.croptop.core.model.ToolId
import app.croptop.core.model.ViewPoint
import app.croptop.core.model.ViewportState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RetouchToolTest {
    private val ctx = object : ToolContext {
        override val viewport = ViewportState(zoom = 1f, viewWidth = 200, viewHeight = 200)
        override val document = DocSize(200, 200)
        override fun updateViewport(state: ViewportState) = Unit
    }

    private fun ev(phase: PointerPhase, x: Float, y: Float, modifiers: Modifiers = Modifiers.None) =
        ToolPointerEvent(phase, ViewPoint(x, y), DocPoint(x, y), 1f, modifiers, ViewPoint(x, y))

    private val effects = mutableListOf<RetouchEffect>()
    private val messages = mutableListOf<String>()
    private val unsupportedActions = mutableListOf<Pair<String, String>>()
    private var samples = 0

    private fun tool(id: ToolId, history: List<String> = emptyList(), onCancel: () -> Unit = {}) = RetouchTool(
        id,
        begin = { _, _, effect ->
            effects += effect
            object : StrokeSession {
                override fun add(samples: FloatArray) {
                    this@RetouchToolTest.samples += samples.size / StrokeSampleLayout.STRIDE
                }

                override fun end(): CommandResult = CommandResult.Accepted(1)

                override fun cancel() = Unit
            }
        },
        onResult = {},
        notify = { messages += it },
        onCancel = onCancel,
        historyLabels = { history },
        foreground = { app.croptop.core.model.RgbaColor(0.1f, 0.2f, 0.3f) },
        background = { app.croptop.core.model.RgbaColor(0.9f, 0.8f, 0.7f) },
    ).apply {
        onActionUnsupported = { action, reason -> unsupportedActions += action to reason }
    }

    private fun RetouchTool.stroke(x: Float, y: Float, modifiers: Modifiers = Modifiers.None) {
        onPointer(ev(PointerPhase.DOWN, x, y, modifiers), ctx)
        onPointer(ev(PointerPhase.MOVE, x + 5f, y), ctx)
        onPointer(ev(PointerPhase.UP, x + 5f, y), ctx)
    }

    @Test fun cloneNeedsASourceAndAlignedKeepsTheFirstStrokeOffset() {
        val clone = tool(ToolId.CLONE_STAMP)
        clone.stroke(50f, 50f)
        assertTrue(effects.isEmpty())
        assertEquals(listOf("Alt-click to set the Clone Stamp source first"), messages)

        clone.onPointer(ev(PointerPhase.DOWN, 10f, 20f, Modifiers(alt = true)), ctx)
        assertEquals(DocPoint(10f, 20f), clone.source)
        clone.stroke(50f, 50f)
        clone.stroke(80f, 60f)
        // Offset (-40, -30) from the first stroke carries over to the second.
        assertEquals(listOf(DocPoint(10f, 20f), DocPoint(40f, 30f)), effects.map { (it as RetouchEffect.Clone).source })
        assertEquals(4, samples)
    }

    @Test fun unalignedHealingRestartsFromTheSourceEveryStroke() {
        val heal = tool(ToolId.HEALING)
        heal.aligned = false
        heal.onPointer(ev(PointerPhase.DOWN, 10f, 20f, Modifiers(alt = true)), ctx)
        heal.stroke(50f, 50f)
        heal.stroke(80f, 60f)
        assertEquals(listOf(DocPoint(10f, 20f), DocPoint(10f, 20f)), effects.map { (it as RetouchEffect.Heal).source })
    }

    @Test fun eachToolSendsItsOwnEffectFromTheOptions() {
        val burn = tool(ToolId.BURN).apply { strength = 0.2f }
        val sponge = tool(ToolId.SPONGE).apply { saturate = true }
        val sharpen = tool(ToolId.SHARPEN)
        listOf(burn, sponge, sharpen, tool(ToolId.SMUDGE), tool(ToolId.SPOT_HEALING)).forEach { it.stroke(30f, 30f) }
        assertEquals(RetouchEffect.Tone(burn = true, exposure = 0.2f), effects[0])
        assertEquals(RetouchEffect.Sponge(saturate = true), effects[1])
        assertEquals(RetouchEffect.Focus(sharpen = true), effects[2])
        assertIs<RetouchEffect.Smudge>(effects[3])
        assertIs<RetouchEffect.SpotHeal>(effects[4])
        // Alt means nothing special outside Clone/Heal: it still paints.
        tool(ToolId.DODGE).stroke(10f, 10f, Modifiers(alt = true))
        assertEquals("Dodge", effects.last().label)
    }

    @Test fun patchAndRedEyeExplainMissingEngineSupportWithoutStartingAStroke() {
        listOf(ToolId.PATCH, ToolId.RED_EYE).forEach { id ->
            tool(id).stroke(30f, 30f)
        }

        assertTrue(effects.isEmpty())
        assertEquals(listOf("Patch", "Red Eye"), unsupportedActions.map { it.first })
        assertTrue(unsupportedActions.all { it.second.contains("does not expose yet") })
    }

    @Test fun activeRetouchCancellationIsReportedOnlyForAnOpenSession() {
        var cancelReports = 0
        val retouch = tool(ToolId.DODGE) { cancelReports++ }
        retouch.onPointer(ev(PointerPhase.DOWN, 20f, 20f), ctx)
        retouch.onPointer(ev(PointerPhase.CANCEL, 20f, 20f), ctx)
        retouch.onPointer(ev(PointerPhase.CANCEL, 20f, 20f), ctx)

        assertEquals(1, cancelReports)
    }

    @Test fun historyBrushPaintsFromTheChosenStateAndClampsToTheStatesThatExist() {
        val brush = tool(ToolId.HISTORY_BRUSH, history = listOf("Open", "Brush Tool", "Blur"))
        brush.stroke(20f, 20f)
        assertEquals(RetouchEffect.HistoryPaint(0), effects.last(), "the first state (Open) is the default source")
        brush.historyIndex = 2
        brush.stroke(30f, 30f)
        assertEquals(RetouchEffect.HistoryPaint(2), effects.last())
        brush.historyIndex = 40
        brush.stroke(40f, 40f)
        assertEquals(RetouchEffect.HistoryPaint(2), effects.last(), "a state that is gone falls back to the latest one")
        assertTrue(messages.isEmpty(), "no source has to be set first")
    }

    @Test fun backgroundEraserBuildsItsEffectFromTheOptionsAndTheSwatches() {
        val eraser = tool(ToolId.BACKGROUND_ERASER)
        eraser.stroke(20f, 20f)
        assertEquals(
            RetouchEffect.BackgroundErase(0.3f, true, app.croptop.core.engine.api.BackgroundSampling.CONTINUOUS, null, null),
            effects.last(),
            "continuous, contiguous, nothing protected by default",
        )
        eraser.eraserTolerance = 0.5f
        eraser.eraserContiguous = false
        eraser.eraserSampling = app.croptop.core.engine.api.BackgroundSampling.SWATCH
        eraser.protectForeground = true
        eraser.stroke(40f, 40f)
        val effect = effects.last() as RetouchEffect.BackgroundErase
        assertEquals(0.5f, effect.tolerance)
        assertEquals(false, effect.contiguous)
        assertEquals(app.croptop.core.model.RgbaColor(0.9f, 0.8f, 0.7f), effect.swatch, "the background swatch is the colour to remove")
        assertEquals(app.croptop.core.model.RgbaColor(0.1f, 0.2f, 0.3f), effect.protect, "the foreground swatch is protected")
        assertTrue(messages.isEmpty())
    }
}
