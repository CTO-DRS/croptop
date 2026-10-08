package app.croptop.feature.editor.tools

import app.croptop.core.input.Modifiers
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocSize
import app.croptop.core.model.RgbaColor
import app.croptop.core.model.SelectionOp
import app.croptop.core.model.ViewPoint
import app.croptop.core.model.ViewportState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FillSelectionToolsTest {
    private val ctx = object : ToolContext {
        override val viewport = ViewportState(zoom = 1f, viewWidth = 100, viewHeight = 100)
        override val document = DocSize(100, 100)
        override fun updateViewport(state: ViewportState) = Unit
    }

    private fun ev(phase: PointerPhase, x: Float, y: Float, modifiers: Modifiers = Modifiers.None) =
        ToolPointerEvent(phase, ViewPoint(x, y), DocPoint(x, y), 1f, modifiers, ViewPoint(x, y))

    @Test fun wandCombinesOnClickOnlyAndIgnoresOutsideCanvas() {
        val calls = mutableListOf<SelectionOp>()
        val tool = MagicWandTool { _, _, _, _, op ->
            calls += op
            true
        }
        tool.onPointer(ev(PointerPhase.DOWN, 10f, 10f, Modifiers(shift = true, alt = true)), ctx)
        tool.onPointer(ev(PointerPhase.UP, 10f, 10f), ctx)
        tool.onPointer(ev(PointerPhase.DOWN, -1f, 10f), ctx)
        assertEquals(listOf(SelectionOp.INTERSECT), calls)
    }

    @Test fun bucketUsesCurrentForegroundAndCommitsOnce() {
        val colors = mutableListOf<RgbaColor>()
        var foreground = RgbaColor.Black
        val tool = PaintBucketTool({ foreground }) { _, color, _, _, _, _ ->
            colors += color
            true
        }
        tool.onPointer(ev(PointerPhase.DOWN, 10f, 10f), ctx)
        foreground = RgbaColor.White
        tool.onPointer(ev(PointerPhase.DOWN, 20f, 20f), ctx)
        tool.onPointer(ev(PointerPhase.UP, 20f, 20f), ctx)
        assertEquals(listOf(RgbaColor.Black, RgbaColor.White), colors)
    }

    @Test fun bucketForwardsConfiguredSamplingAndFillOptions() {
        data class BucketCall(
            val point: DocPoint,
            val color: RgbaColor,
            val tolerance: Int,
            val contiguous: Boolean,
            val sampleAll: Boolean,
            val opacity: Float,
        )

        val calls = mutableListOf<BucketCall>()
        val foreground = RgbaColor(0.2f, 0.4f, 0.8f, 0.75f)
        val tool = PaintBucketTool({ foreground }) { point, color, tolerance, contiguous, sampleAll, opacity ->
            calls += BucketCall(point, color, tolerance, contiguous, sampleAll, opacity)
            true
        }
        tool.tolerance = 73
        tool.contiguous = false
        tool.sampleAll = true
        tool.opacity = 0.35f

        tool.onPointer(ev(PointerPhase.DOWN, 12f, 18f), ctx)

        assertEquals(
            listOf(BucketCall(DocPoint(12f, 18f), foreground, 73, contiguous = false, sampleAll = true, opacity = 0.35f)),
            calls,
        )
    }

    @Test fun gradientSnapsToFortyFiveDegreesAndCancelDoesNotCommit() {
        val ends = mutableListOf<DocPoint>()
        val tool = GradientTool(
            colors = { RgbaColor.Black to RgbaColor.White },
            apply = { _, end, _, _, _, _, _ ->
                ends += end
                true
            },
        )
        tool.onPointer(ev(PointerPhase.DOWN, 10f, 10f), ctx)
        tool.onPointer(ev(PointerPhase.MOVE, 50f, 20f, Modifiers(shift = true)), ctx)
        tool.onPointer(ev(PointerPhase.UP, 50f, 20f, Modifiers(shift = true)), ctx)
        assertEquals(10f, ends.single().y, 0.001f)
        assertTrue(ends.single().x > 50f)
        tool.onPointer(ev(PointerPhase.DOWN, 10f, 10f), ctx)
        tool.onPointer(ev(PointerPhase.CANCEL, 20f, 20f), ctx)
        assertEquals(1, ends.size)
    }
}
