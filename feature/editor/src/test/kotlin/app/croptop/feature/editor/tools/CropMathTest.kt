package app.croptop.feature.editor.tools

import app.croptop.core.input.Modifiers
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocRectF
import app.croptop.core.model.DocSize
import app.croptop.core.model.PerspectiveCropGeometry
import app.croptop.core.model.ViewPoint
import app.croptop.core.model.ViewportState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CropMathTest {
    @Test
    fun cropOrientationSwapInvertsSelectedAspectRatio() {
        val tool = CropTool({ DocSize(4000, 3000) }, { _, _ -> true })
        tool.chooseAspect(CropAspect.PORTRAIT_4_5)

        assertEquals(4f / 5f, tool.ratioFor(DocSize(4000, 3000)))
        tool.swapOrientation()
        assertEquals(5f / 4f, tool.ratioFor(DocSize(4000, 3000)))
        tool.swapOrientation()
        assertEquals(4f / 5f, tool.ratioFor(DocSize(4000, 3000)))
    }

    @Test
    fun customAspectRatioUpdatesCropRatioAndBounds() {
        val size = DocSize(4000, 3000)
        val tool = CropTool({ size }, { _, _ -> true })
        tool.chooseAspect(CropAspect.CUSTOM)

        assertEquals(16f / 9f, tool.ratioFor(size))
        tool.updateCustomWidth(4f)
        tool.updateCustomHeight(5f)
        assertEquals(4f / 5f, tool.ratioFor(size))
        tool.swapOrientation()
        assertEquals(5f / 4f, tool.ratioFor(size))
    }

    @Test
    fun cropOverlayCyclesThroughCompositionGuides() {
        val tool = CropTool({ DocSize(4000, 3000) }, { _, _ -> true })

        assertEquals(CropOverlay.THIRDS, tool.overlay)
        tool.cycleOverlay()
        assertEquals(CropOverlay.GOLDEN_RATIO, tool.overlay)
        tool.cycleOverlay()
        assertEquals(CropOverlay.DIAGONAL, tool.overlay)
        tool.cycleOverlay()
        assertEquals(CropOverlay.THIRDS, tool.overlay)
    }

    @Test
    fun perspectiveCropCommitsTheSelectedAspectQuad() {
        var committed: app.croptop.core.model.CropQuad? = null
        val tool = CropTool(
            { DocSize(400, 300) },
            { _, _ -> error("rectangular callback should not be used") },
            { quad, delete ->
                assertTrue(delete)
                committed = quad
                true
            },
        )
        tool.chooseAspect(CropAspect.SQUARE)
        tool.setPerspectiveEnabled(true)

        assertTrue(tool.apply())
        assertEquals(DocSize(300, 300), PerspectiveCropGeometry.outputSize(checkNotNull(committed)))
    }

    @Test
    fun dedicatedPerspectiveToolStartsInPerspectiveModeAndCannotLeaveIt() {
        var committed: app.croptop.core.model.CropQuad? = null
        val tool = CropTool(
            { DocSize(400, 300) },
            { _, _ -> error("the perspective tool never commits a plain rectangle") },
            { quad, _ ->
                committed = quad
                true
            },
            tool = app.croptop.core.model.ToolId.PERSPECTIVE_CROP,
        )
        assertEquals(app.croptop.core.model.ToolId.PERSPECTIVE_CROP, tool.tool)
        assertTrue(tool.perspective)
        tool.setPerspectiveEnabled(false)
        assertTrue(tool.perspective, "it is always perspective")
        assertTrue(tool.apply())
        assertEquals(DocSize(400, 300), PerspectiveCropGeometry.outputSize(checkNotNull(committed)), "the untouched frame keeps the whole image")
        assertEquals(app.croptop.core.model.ToolId.CROP, CropTool({ null }, { _, _ -> false }).tool)
        assertEquals(false, CropTool({ null }, { _, _ -> false }).perspective, "the regular crop tool still starts as a plain rectangle")
    }

    @Test
    fun straightenLineProducesShortestImageRotation() {
        assertEquals(0f, CropMath.straightenAngle(DocPoint(0f, 10f), DocPoint(200f, 10f)), 1e-4f)
        val tiltedEnd = DocPoint(100f, kotlin.math.tan(Math.toRadians(10.0)).toFloat() * 100f)
        assertEquals(-10f, CropMath.straightenAngle(DocPoint(0f, 0f), tiltedEnd), 0.02f)
        assertEquals(-45f, CropMath.straightenAngle(DocPoint(100f, 100f), DocPoint(0f, 0f)), 1e-4f)
        assertEquals(0f, CropMath.straightenAngle(DocPoint(4f, 4f), DocPoint(4f, 4f)))
    }

    @Test
    fun straightenGestureCommitsItsCalculatedAngle() {
        var committedAngle: Float? = null
        val size = DocSize(400, 300)
        val tool = CropTool(
            document = { size },
            commit = { _, _ -> error("angled callback should be used") },
            commitAngled = { _, angle, _ ->
                committedAngle = angle
                true
            },
        )
        tool.setStraightenMode(true)
        val context = object : ToolContext {
            override val viewport = ViewportState(zoom = 1f)
            override val document = size
            override fun updateViewport(state: ViewportState) = Unit
        }
        fun event(phase: PointerPhase, point: DocPoint) = ToolPointerEvent(
            phase = phase,
            view = ViewPoint(point.x, point.y),
            doc = point,
            pressure = 1f,
            modifiers = Modifiers(),
            downAt = ViewPoint(10f, 10f),
        )

        tool.onPointer(event(PointerPhase.DOWN, DocPoint(0f, 0f)), context)
        tool.onPointer(event(PointerPhase.MOVE, DocPoint(100f, 10f)), context)
        tool.onPointer(event(PointerPhase.UP, DocPoint(100f, 10f)), context)
        val expectedAngle = -Math.toDegrees(kotlin.math.atan2(10.0, 100.0)).toFloat()
        assertEquals(expectedAngle, tool.straightenAngle, 1e-4f)
        assertTrue(tool.apply())
        assertEquals(expectedAngle, committedAngle)
        assertEquals(0f, tool.straightenAngle)
    }

    private val box = DocRectF(100f, 100f, 500f, 400f)

    @Test
    fun `free corner drag follows the pointer and never inverts`() {
        assertEquals(DocRectF(100f, 100f, 600f, 450f), CropMath.drag(box, CropHandle.BOTTOM_RIGHT, DocPoint(600f, 450f), null))
        val squashed = CropMath.drag(box, CropHandle.RIGHT, DocPoint(50f, 0f), null)
        assertEquals(CropMath.MIN_SIZE, squashed.width)
    }

    @Test
    fun `ratio drags keep proportions`() {
        val r = CropMath.drag(box, CropHandle.BOTTOM_RIGHT, DocPoint(700f, 999f), 1f)
        assertEquals(r.width, r.height)
        val edge = CropMath.drag(box, CropHandle.BOTTOM, DocPoint(0f, 500f), 2f)
        assertEquals(2f, edge.width / edge.height, 1e-4f)
        assertEquals(300f, edge.center.x, 1e-3f) // centered on the old box
    }

    @Test
    fun `presets fit the canvas and handles are found`() {
        assertEquals(DocRectF(500f, 0f, 3500f, 3000f), CropMath.fit(DocSize(4000, 3000), 1f))
        assertEquals(DocRectF(0f, 0f, 4000f, 3000f), CropMath.fit(DocSize(4000, 3000), null))
        assertEquals(CropHandle.TOP_LEFT, CropMath.handleAt(box, DocPoint(103f, 98f), 8f))
        assertEquals(CropHandle.RIGHT, CropMath.handleAt(box, DocPoint(505f, 250f), 8f))
        assertEquals(CropHandle.MOVE, CropMath.handleAt(box, DocPoint(300f, 250f), 8f))
        assertEquals(null, CropMath.handleAt(box, DocPoint(900f, 900f), 8f))
    }
}
