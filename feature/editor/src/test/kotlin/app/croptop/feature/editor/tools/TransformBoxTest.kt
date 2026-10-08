package app.croptop.feature.editor.tools

import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocRectF
import app.croptop.core.model.DocSize
import app.croptop.core.model.Guide
import app.croptop.core.model.GuideAxis
import kotlin.test.Test
import kotlin.test.assertEquals

class TransformBoxTest {
    private val orig = DocRectF(0f, 0f, 200f, 100f)

    private fun near(a: DocPoint, b: DocPoint) {
        assertEquals(a.x, b.x, 0.01f)
        assertEquals(a.y, b.y, 0.01f)
    }

    @Test
    fun `transform again repeats scale offset and rotation relative to the new box`() {
        // Last transform: moved by (10, 5), scaled to 150% × 50%, rotated 30°.
        val last = TransformBox(orig, local = DocRectF(10f, 5f, 310f, 55f), angle = 30f)
        val again = last.repeatOn(DocRectF(100f, 100f, 140f, 120f))
        assertEquals(DocRectF(110f, 105f, 170f, 115f), again.local)
        assertEquals(30f, again.angle)
        assertEquals(150f, again.widthPercent)
        assertEquals(50f, again.heightPercent)
    }

    @Test
    fun `flips mirror about the original center and count as a change`() {
        val f = TransformBox(orig, flipX = true)
        near(DocPoint(200f, 0f), f.matrix.map(DocPoint(0f, 0f)))
        near(DocPoint(0f, 100f), f.matrix.map(DocPoint(200f, 100f)))
        assertEquals(false, f.isIdentity)
        val v = TransformBox(orig, flipY = true)
        near(DocPoint(0f, 100f), v.matrix.map(DocPoint(0f, 0f)))
        // Transform Again keeps the flip.
        assertEquals(true, f.repeatOn(DocRectF(0f, 0f, 10f, 10f)).flipX)
    }

    @Test
    fun `rotate ninety wraps into range`() {
        assertEquals(90f, TransformBox(orig).rotated(90f).angle)
        assertEquals(-90f, TransformBox(orig, angle = 180f).rotated(90f).angle)
        assertEquals(-180f, TransformBox(orig, angle = -90f).rotated(-90f).angle)
    }

    @Test
    fun `movable reference point stays fixed during rotation`() {
        val pivot = DocPoint(25f, 40f)
        val rotated = TransformBox(orig, angle = 90f, referencePoint = pivot)
        near(pivot, rotated.matrix.map(pivot))
        near(DocPoint(65f, 215f), rotated.matrix.map(DocPoint(200f, 0f)))
    }

    @Test
    fun `alt drag scales about the center`() {
        val b = TransformBox(orig)
        // Dragging the right edge out by 50 grows the left edge by 50 too.
        val free = b.scaledFromCenter(DocRectF(0f, 0f, 250f, 100f), CropHandle.RIGHT, keepRatio = false)
        assertEquals(DocRectF(-50f, 0f, 250f, 100f), free)
        // A corner with the ratio kept scales both axes by the larger factor (1.5×).
        val corner = b.scaledFromCenter(DocRectF(0f, 0f, 250f, 110f), CropHandle.BOTTOM_RIGHT, keepRatio = true)
        assertEquals(DocRectF(-50f, -25f, 250f, 125f), corner)
    }

    @Test
    fun `distort maps each corner onto the dragged quad`() {
        val d = TransformBox(orig).distorted(2, 30f, 20f, perspective = false)
        assertEquals(DocPoint(230f, 120f), d.corners[2])
        // The projective matrix sends every original corner to its quad corner, and the center inside.
        near(DocPoint(0f, 0f), d.matrix.map(DocPoint(0f, 0f)))
        near(DocPoint(200f, 0f), d.matrix.map(DocPoint(200f, 0f)))
        near(DocPoint(230f, 120f), d.matrix.map(DocPoint(200f, 100f)))
        near(DocPoint(0f, 100f), d.matrix.map(DocPoint(0f, 100f)))
        assertEquals(false, d.isIdentity)
        assertEquals(true, d.copy(quad = null).isIdentity)
    }

    @Test
    fun `perspective mirrors the partner corner and transform again keeps the distortion`() {
        val p = TransformBox(orig).distorted(0, 20f, 0f, perspective = true)
        assertEquals(DocPoint(20f, 0f), p.corners[0])
        assertEquals(DocPoint(180f, 0f), p.corners[1])
        near(DocPoint(180f, 0f), p.matrix.map(DocPoint(200f, 0f)))
        val again = p.repeatOn(DocRectF(0f, 0f, 100f, 50f))
        assertEquals(listOf(DocPoint(10f, 0f), DocPoint(90f, 0f), DocPoint(100f, 50f), DocPoint(0f, 50f)), again.corners)
    }

    @Test
    fun `skew slides an edge along its own direction`() {
        // Top edge (0 → 1) dragged right by 30 and down by 40: only the along-edge part applies.
        val s = TransformBox(orig).skewed(0, 30f, 40f)
        assertEquals(listOf(DocPoint(30f, 0f), DocPoint(230f, 0f), DocPoint(200f, 100f), DocPoint(0f, 100f)), s.corners)
    }

    @Test
    fun `identity maps the box onto itself`() {
        val b = TransformBox(orig)
        near(DocPoint(200f, 100f), b.matrix.map(DocPoint(200f, 100f)))
        assertEquals(100f, b.widthPercent)
    }

    @Test
    fun `transform move snaps its bounds to enabled guides`() {
        val matrices = mutableListOf<app.croptop.core.model.Matrix3>()
        val session = object : app.croptop.core.engine.api.TransformSession {
            override fun update(matrix: app.croptop.core.model.Matrix3) {
                matrices += matrix
            }

            override fun commit() = app.croptop.core.engine.api.CommandResult.Accepted(1)

            override fun cancel() = Unit
        }
        val tool = TransformTool(session, DocRectF(100f, 100f, 600f, 600f), "Transform", { true }, {})
        tool.viewAidsForSnap = {
            app.croptop.core.model.ViewAids(
                snapTo = app.croptop.core.model.SnapTo(guides = true, grid = false, documentBounds = false, layers = false),
                guides = listOf(Guide(GuideAxis.VERTICAL, 610f)),
            )
        }
        val ctx = object : ToolContext {
            override val viewport = app.croptop.core.model.ViewportState(zoom = 1f, viewWidth = 1000, viewHeight = 800)
            override val document = DocSize(1000, 800)
            override fun updateViewport(state: app.croptop.core.model.ViewportState) = Unit
        }
        fun event(phase: PointerPhase, x: Float, y: Float) = ToolPointerEvent(
            phase = phase,
            view = app.croptop.core.model.ViewPoint(x, y),
            doc = DocPoint(x, y),
            pressure = 1f,
            modifiers = app.croptop.core.input.Modifiers.None,
            downAt = app.croptop.core.model.ViewPoint(150f, 350f),
        )

        tool.onPointer(event(PointerPhase.DOWN, 150f, 350f), ctx)
        tool.onPointer(event(PointerPhase.MOVE, 158f, 350f), ctx)

        assertEquals(610f, matrices.last().map(DocPoint(600f, 350f)).x, 0.01f)
        assertEquals(350f, matrices.last().map(DocPoint(600f, 350f)).y, 0.01f)
    }

    @Test
    fun `transform move snaps its bounds to the active selection edge`() {
        val matrices = mutableListOf<app.croptop.core.model.Matrix3>()
        val session = object : app.croptop.core.engine.api.TransformSession {
            override fun update(matrix: app.croptop.core.model.Matrix3) {
                matrices += matrix
            }

            override fun commit() = app.croptop.core.engine.api.CommandResult.Accepted(1)

            override fun cancel() = Unit
        }
        val tool = TransformTool(session, DocRectF(100f, 100f, 600f, 600f), "Transform", { true }, {})
        tool.viewAidsForSnap = {
            app.croptop.core.model.ViewAids(
                snapTo = app.croptop.core.model.SnapTo(guides = false, grid = false, documentBounds = false, layers = true),
            )
        }
        tool.selectionBoundsForSnap = { app.croptop.core.model.DocRect(610, 100, 710, 600) }
        val ctx = object : ToolContext {
            override val viewport = app.croptop.core.model.ViewportState(zoom = 1f, viewWidth = 1000, viewHeight = 800)
            override val document = DocSize(1000, 800)
            override fun updateViewport(state: app.croptop.core.model.ViewportState) = Unit
        }
        fun event(phase: PointerPhase, x: Float, y: Float) = ToolPointerEvent(
            phase = phase,
            view = app.croptop.core.model.ViewPoint(x, y),
            doc = DocPoint(x, y),
            pressure = 1f,
            modifiers = app.croptop.core.input.Modifiers.None,
            downAt = app.croptop.core.model.ViewPoint(150f, 350f),
        )

        tool.onPointer(event(PointerPhase.DOWN, 150f, 350f), ctx)
        tool.onPointer(event(PointerPhase.MOVE, 158f, 350f), ctx)

        assertEquals(610f, matrices.last().map(DocPoint(600f, 350f)).x, 0.01f)
        assertEquals(350f, matrices.last().map(DocPoint(600f, 350f)).y, 0.01f)
    }

    @Test
    fun `scaling into a new local box then rotating about its center`() {
        val b = TransformBox(orig, local = DocRectF(100f, 100f, 500f, 300f))
        near(DocPoint(100f, 100f), b.matrix.map(DocPoint(0f, 0f)))
        near(DocPoint(500f, 300f), b.matrix.map(DocPoint(200f, 100f)))
        assertEquals(200f, b.widthPercent)
        val r = b.copy(angle = 90f)
        // The center stays put; the top-left corner swings to the top-right of the center.
        near(DocPoint(300f, 200f), r.matrix.map(DocPoint(100f, 50f)))
        near(DocPoint(400f, 0f), r.matrix.map(DocPoint(0f, 0f)))
        // toLocal undoes the rotation.
        near(DocPoint(100f, 100f), r.toLocal(DocPoint(400f, 0f)))
    }
}
