package app.croptop.feature.editor.tools

import app.croptop.core.input.Modifiers
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocRectF
import app.croptop.core.model.DocSize
import app.croptop.core.model.ViewPoint
import app.croptop.core.model.ViewportState
import kotlin.test.Test
import kotlin.test.assertEquals

class RetouchSpecialToolsTest {
    private val ctx = object : ToolContext {
        override val viewport = ViewportState(zoom = 1f, viewWidth = 100, viewHeight = 100)
        override val document = DocSize(100, 100)
        override fun updateViewport(state: ViewportState) = Unit
    }

    private fun event(phase: PointerPhase, x: Float, y: Float) =
        ToolPointerEvent(phase, ViewPoint(x, y), DocPoint(x, y), 1f, Modifiers.None, ViewPoint(x, y))

    @Test
    fun redEyeClickBuildsPupilRegionWithConfiguredOptions() {
        val calls = mutableListOf<Triple<DocRectF, Float, Float>>()
        val tool = RedEyeTool { region, pupilSize, darken ->
            calls += Triple(region, pupilSize, darken)
            true
        }
        tool.pupilSize = 64f
        tool.darken = 82f

        tool.onPointer(event(PointerPhase.DOWN, 40f, 50f), ctx)
        tool.onPointer(event(PointerPhase.UP, 40f, 50f), ctx)

        assertEquals(listOf(Triple(DocRectF(20f, 30f, 60f, 70f), 0.64f, 0.82f)), calls)
    }

    @Test
    fun redEyeDragUsesTheSelectedRegionAndCancelDiscardsIt() {
        val regions = mutableListOf<DocRectF>()
        val tool = RedEyeTool { region, _, _ ->
            regions += region
            true
        }
        tool.onPointer(event(PointerPhase.DOWN, 10f, 12f), ctx)
        tool.onPointer(event(PointerPhase.MOVE, 24f, 31f), ctx)
        tool.onPointer(event(PointerPhase.UP, 24f, 31f), ctx)
        tool.onPointer(event(PointerPhase.DOWN, 30f, 32f), ctx)
        tool.onPointer(event(PointerPhase.CANCEL, 36f, 38f), ctx)
        tool.onPointer(event(PointerPhase.UP, 36f, 38f), ctx)

        assertEquals(listOf(DocRectF(10f, 12f, 24f, 31f)), regions)
    }

    @Test
    fun patchRequiresSelectionAndAppliesDragOffsetAndMode() {
        var hasSelection = false
        var selectionPromptCount = 0
        val calls = mutableListOf<Pair<DocPoint, Boolean>>()
        val tool = PatchTool(
            hasSelection = { hasSelection },
            apply = { offset, contentAware ->
                calls += offset to contentAware
                true
            },
            onNeedsSelection = { selectionPromptCount++ },
        )
        tool.contentAware = true
        tool.onPointer(event(PointerPhase.DOWN, 10f, 11f), ctx)
        tool.onPointer(event(PointerPhase.UP, 30f, 31f), ctx)
        hasSelection = true
        tool.onPointer(event(PointerPhase.DOWN, 10f, 11f), ctx)
        tool.onPointer(event(PointerPhase.MOVE, 25f, 29f), ctx)
        tool.onPointer(event(PointerPhase.UP, 25f, 29f), ctx)

        assertEquals(1, selectionPromptCount)
        assertEquals(listOf(DocPoint(15f, 18f) to true), calls)
    }

    @Test
    fun patchCancelDoesNotCommit() {
        var calls = 0
        val tool = PatchTool({ true }, { _, _ ->
            calls++
            true
        }, {})
        tool.onPointer(event(PointerPhase.DOWN, 10f, 11f), ctx)
        tool.onPointer(event(PointerPhase.CANCEL, 25f, 29f), ctx)
        tool.onPointer(event(PointerPhase.UP, 25f, 29f), ctx)

        assertEquals(0, calls)
    }
}
