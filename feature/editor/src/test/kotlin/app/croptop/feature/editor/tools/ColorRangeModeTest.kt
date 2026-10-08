package app.croptop.feature.editor.tools

import app.croptop.core.engine.api.ColorRangeSession
import app.croptop.core.engine.api.ColorRangeSpec
import app.croptop.core.engine.api.ColorRangeTarget
import app.croptop.core.engine.api.CommandResult
import app.croptop.core.input.Modifiers
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocSize
import app.croptop.core.model.RgbaColor
import app.croptop.core.model.ViewPoint
import app.croptop.core.model.ViewportState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ColorRangeModeTest {
    private val ctx = object : ToolContext {
        override val viewport = ViewportState(zoom = 1f, viewWidth = 100, viewHeight = 100)
        override val document = DocSize(100, 100)
        override fun updateViewport(state: ViewportState) = Unit
    }

    private fun click(x: Float, y: Float, m: Modifiers = Modifiers.None) =
        ToolPointerEvent(PointerPhase.DOWN, ViewPoint(x, y), DocPoint(x, y), 1f, m, ViewPoint(x, y))

    private class Recording : ColorRangeSession {
        val updates = mutableListOf<ColorRangeSpec>()
        var committed = 0
        var cancelled = 0

        override fun update(spec: ColorRangeSpec) {
            updates += spec
        }

        override fun commit(): CommandResult {
            committed++
            return CommandResult.Accepted(1)
        }

        override fun cancel() {
            cancelled++
        }
    }

    /** Left half red, right half blue. */
    private val sampler: (DocPoint) -> RgbaColor = { p -> if (p.x < 50f) RgbaColor(1f, 0f, 0f) else RgbaColor(0f, 0f, 1f) }

    @Test fun clicksReplaceShiftAddsAltRemovesAndOutsideIsIgnored() {
        val session = Recording()
        var ended = 0
        val mode = ColorRangeMode(session, sampler, { it.commit() is CommandResult.Accepted }) { ended++ }
        mode.onPointer(click(10f, 10f), ctx)
        mode.onPointer(click(90f, 10f, Modifiers(shift = true)), ctx)
        mode.onPointer(click(20f, 10f, Modifiers(shift = true)), ctx) // red again: no duplicate
        assertEquals(listOf(RgbaColor(1f, 0f, 0f), RgbaColor(0f, 0f, 1f)), mode.samples)
        mode.onPointer(click(15f, 10f, Modifiers(alt = true)), ctx)
        assertEquals(listOf(RgbaColor(0f, 0f, 1f)), mode.samples)
        mode.onPointer(click(-5f, 10f), ctx)
        assertEquals(4, session.updates.size)

        mode.chooseFuzziness(500)
        assertEquals(ColorRangeSpec.MAX_FUZZINESS, session.updates.last().fuzziness)
        assertTrue(mode.onKey(enter = true))
        assertEquals(1, session.committed)
        assertEquals(1, ended)
    }

    @Test fun choosingANamedRangePreviewsAndCommittingUntouchedJustCloses() {
        val session = Recording()
        val mode = ColorRangeMode(session, sampler, { it.commit() is CommandResult.Accepted }) {}
        assertTrue(mode.commit())
        assertEquals(0, session.committed)
        assertEquals(1, session.cancelled)

        val other = Recording()
        val shadows = ColorRangeMode(other, sampler, { it.commit() is CommandResult.Accepted }) {}
        val completed = mutableListOf<String>()
        shadows.onActionCompleted = completed::add
        shadows.chooseTarget(ColorRangeTarget.SHADOWS)
        shadows.chooseInvert(true)
        assertEquals(ColorRangeSpec(ColorRangeTarget.SHADOWS, invert = true), other.updates.last())
        assertEquals(listOf("Set color range target to shadows", "Invert color range"), completed)
        shadows.onKey(enter = false)
        assertEquals(1, other.cancelled)
    }
}
