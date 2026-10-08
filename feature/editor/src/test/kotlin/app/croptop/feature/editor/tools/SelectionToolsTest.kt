package app.croptop.feature.editor.tools

import app.croptop.core.input.Modifiers
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocRect
import app.croptop.core.model.DocSize
import app.croptop.core.model.Guide
import app.croptop.core.model.GuideAxis
import app.croptop.core.model.LayerId
import app.croptop.core.model.SelectionOp
import app.croptop.core.model.SelectionShape
import app.croptop.core.model.SnapTo
import app.croptop.core.model.ToolId
import app.croptop.core.model.ViewAids
import app.croptop.core.model.ViewPoint
import app.croptop.core.model.ViewportState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SelectionToolsTest {
    private val ctx = object : ToolContext {
        override val viewport = ViewportState(zoom = 1f, viewWidth = 1000, viewHeight = 800)
        override val document = DocSize(4000, 3000)

        override fun updateViewport(state: ViewportState) = Unit
    }

    /** Document point == view point at zoom 1 with no pan. */
    private fun ev(phase: PointerPhase, x: Float, y: Float, m: Modifiers = Modifiers.None, clicks: Int = 1) =
        ToolPointerEvent(phase, ViewPoint(x, y), DocPoint(x, y), 1f, m, ViewPoint(0f, 0f), clicks)

    @Test
    fun `move commits fractional offsets and skips true no-ops`() {
        val commits = mutableListOf<Pair<Float, Float>>()
        val t = MoveTool({ LayerId(7) }, {}, { null }, { dx, dy ->
            commits += dx to dy
            true
        })
        t.onPointer(ev(PointerPhase.DOWN, 10f, 10f), ctx)
        t.onPointer(ev(PointerPhase.MOVE, 10.5f, 10.25f), ctx)
        t.onPointer(ev(PointerPhase.UP, 10.5f, 10.25f), ctx)
        t.onPointer(ev(PointerPhase.DOWN, 10f, 10f), ctx)
        t.onPointer(ev(PointerPhase.UP, 10f, 10f), ctx)
        assertEquals(listOf(0.5f to 0.25f), commits)
    }

    @Test
    fun `move shift keeps the drag on one axis and auto-selects under the pointer`() {
        val picked = mutableListOf<LayerId>()
        val commits = mutableListOf<Pair<Float, Float>>()
        val t = MoveTool({ LayerId(3) }, { picked += it }, { null }, { dx, dy ->
            commits += dx to dy
            true
        })
        t.onPointer(ev(PointerPhase.DOWN, 0f, 0f), ctx)
        t.onPointer(ev(PointerPhase.MOVE, 40f, 9f, Modifiers(shift = true)), ctx)
        t.onPointer(ev(PointerPhase.UP, 40f, 9f, Modifiers(shift = true)), ctx)
        assertEquals(listOf(LayerId(3)), picked)
        assertEquals(listOf(40f to 0f), commits)
    }

    @Test
    fun `move Ctrl temporarily auto-selects while the option is disabled`() {
        val picked = mutableListOf<LayerId>()
        val t = MoveTool({ LayerId(9) }, { picked += it }, { null }, { _, _ -> true }).apply {
            autoSelect = false
        }

        t.onPointer(ev(PointerPhase.DOWN, 20f, 30f, Modifiers(ctrl = true)), ctx)
        t.onPointer(ev(PointerPhase.UP, 20f, 30f, Modifiers(ctrl = true)), ctx)

        assertEquals(listOf(LayerId(9)), picked)
    }

    @Test
    fun `move snaps the selected bounds to enabled guides`() {
        val commits = mutableListOf<Pair<Float, Float>>()
        val t = MoveTool(
            layerAt = { LayerId(1) },
            selectLayer = {},
            selectedBounds = { DocRect(100, 100, 200, 200) },
            commit = { dx, dy ->
                commits += dx to dy
                true
            },
            viewAids = {
                ViewAids(
                    snapTo = SnapTo(guides = true, grid = false, documentBounds = false, layers = false),
                    guides = listOf(Guide(GuideAxis.VERTICAL, 210f)),
                )
            },
        )

        t.onPointer(ev(PointerPhase.DOWN, 150f, 150f), ctx)
        t.onPointer(ev(PointerPhase.MOVE, 158f, 150f), ctx)
        t.onPointer(ev(PointerPhase.UP, 158f, 150f), ctx)

        assertEquals(listOf(10f to 0f), commits)
    }

    @Test
    fun `move snaps to another layer edge when layer snapping is enabled`() {
        val commits = mutableListOf<Pair<Float, Float>>()
        val t = MoveTool(
            layerAt = { LayerId(1) },
            selectLayer = {},
            selectedBounds = { DocRect(100, 100, 200, 200) },
            commit = { dx, dy ->
                commits += dx to dy
                true
            },
            viewAids = {
                ViewAids(snapTo = SnapTo(guides = false, grid = false, documentBounds = false, layers = true))
            },
            otherLayerBounds = { listOf(DocRect(210, 100, 310, 200)) },
        )

        t.onPointer(ev(PointerPhase.DOWN, 150f, 150f), ctx)
        t.onPointer(ev(PointerPhase.MOVE, 158f, 150f), ctx)
        t.onPointer(ev(PointerPhase.UP, 158f, 150f), ctx)

        assertEquals(listOf(10f to 0f), commits)
    }

    @Test
    fun `move snaps layer bounds to the active selection edge`() {
        val commits = mutableListOf<Pair<Float, Float>>()
        val t = MoveTool(
            layerAt = { LayerId(1) },
            selectLayer = {},
            selectedBounds = { DocRect(100, 100, 200, 200) },
            commit = { dx, dy ->
                commits += dx to dy
                true
            },
            viewAids = {
                ViewAids(snapTo = SnapTo(guides = false, grid = false, documentBounds = false, layers = true))
            },
            selectionBounds = { DocRect(210, 100, 310, 200) },
        )

        t.onPointer(ev(PointerPhase.DOWN, 150f, 150f), ctx)
        t.onPointer(ev(PointerPhase.MOVE, 158f, 150f), ctx)
        t.onPointer(ev(PointerPhase.UP, 158f, 150f), ctx)

        assertEquals(listOf(10f to 0f), commits)
    }

    @Test
    fun `move shift axis lock takes precedence over snapping on locked axis`() {
        val commits = mutableListOf<Pair<Float, Float>>()
        val t = MoveTool(
            layerAt = { LayerId(1) },
            selectLayer = {},
            selectedBounds = { DocRect(100, 100, 200, 200) },
            commit = { dx, dy ->
                commits += dx to dy
                true
            },
            viewAids = {
                ViewAids(
                    snapTo = SnapTo(guides = true, grid = false, documentBounds = false, layers = false),
                    guides = listOf(Guide(GuideAxis.HORIZONTAL, 107f)),
                )
            },
        )

        t.onPointer(ev(PointerPhase.DOWN, 150f, 150f), ctx)
        t.onPointer(ev(PointerPhase.MOVE, 190f, 159f, Modifiers(shift = true)), ctx)
        t.onPointer(ev(PointerPhase.UP, 190f, 159f, Modifiers(shift = true)), ctx)

        assertEquals(listOf(40f to 0f), commits)
    }

    @Test
    fun `marquee modifiers pick the combine op and shift squares a fresh box`() {
        val applied = mutableListOf<Pair<SelectionShape, SelectionOp>>()
        val t = MarqueeTool(ToolId.RECT_MARQUEE, ellipse = false, { true }) { s, op, _ ->
            applied += s to op
            true
        }
        t.onPointer(ev(PointerPhase.DOWN, 0f, 0f), ctx)
        t.onPointer(ev(PointerPhase.UP, 30f, 10f, Modifiers(shift = true)), ctx)
        val square = (applied.single().first as SelectionShape.Rect).rect
        assertEquals(30f, square.width)
        assertEquals(30f, square.height)
        t.onPointer(ev(PointerPhase.DOWN, 0f, 0f, Modifiers(shift = true, alt = true)), ctx)
        t.onPointer(ev(PointerPhase.UP, 20f, 20f), ctx)
        assertEquals(SelectionOp.INTERSECT, applied.last().second)
        // A click (no drag) with a selection deselects via an empty REPLACE.
        t.onPointer(ev(PointerPhase.DOWN, 5f, 5f), ctx)
        t.onPointer(ev(PointerPhase.UP, 5f, 5f), ctx)
        assertTrue(applied.last().first.bounds.isEmpty && applied.last().second == SelectionOp.REPLACE)
    }

    @Test
    fun `selection cursor operation follows the modifier matrix`() {
        assertEquals(SelectionOp.REPLACE, selectionOp(Modifiers.None))
        assertEquals(SelectionOp.ADD, selectionOp(Modifiers(shift = true)))
        assertEquals(SelectionOp.SUBTRACT, selectionOp(Modifiers(alt = true)))
        assertEquals(SelectionOp.INTERSECT, selectionOp(Modifiers(shift = true, alt = true)))
    }

    @Test
    fun `lasso selection mode is captured at gesture start`() {
        val applied = mutableListOf<SelectionOp>()
        val t = LassoTool(ToolId.LASSO, polygonal = false) { _, op, _ ->
            applied += op
            true
        }

        t.onPointer(ev(PointerPhase.DOWN, 0f, 0f, Modifiers(shift = true, alt = true)), ctx)
        t.onPointer(ev(PointerPhase.MOVE, 20f, 0f), ctx)
        t.onPointer(ev(PointerPhase.MOVE, 20f, 20f), ctx)
        t.onPointer(ev(PointerPhase.UP, 0f, 20f), ctx)

        assertEquals(listOf(SelectionOp.INTERSECT), applied)
    }

    @Test
    fun `ellipse marquee sends an ellipse selection callback with the drag bounds`() {
        val applied = mutableListOf<Pair<SelectionShape, SelectionOp>>()
        val t = MarqueeTool(ToolId.ELLIPSE_MARQUEE, ellipse = true, { false }) { shape, op, _ ->
            applied += shape to op
            true
        }

        t.onPointer(ev(PointerPhase.DOWN, 12f, 18f), ctx)
        t.onPointer(ev(PointerPhase.MOVE, 52f, 58f), ctx)
        t.onPointer(ev(PointerPhase.UP, 52f, 58f), ctx)

        assertEquals(SelectionShape.Ellipse(app.croptop.core.model.DocRectF(12f, 18f, 52f, 58f)), applied.single().first)
        assertEquals(SelectionOp.REPLACE, applied.single().second)
    }

    @Test
    fun `polygonal lasso closes on double click and needs three points`() {
        val applied = mutableListOf<SelectionShape>()
        val t = LassoTool(ToolId.POLYGONAL_LASSO, polygonal = true) { s, _, _ ->
            applied += s
            true
        }
        t.onPointer(ev(PointerPhase.DOWN, 0f, 0f), ctx)
        t.onPointer(ev(PointerPhase.DOWN, 50f, 0f), ctx)
        t.onPointer(ev(PointerPhase.DOWN, 50f, 50f), ctx)
        t.onPointer(ev(PointerPhase.DOWN, 50f, 50f, clicks = 2), ctx)
        assertEquals(3, (applied.single() as SelectionShape.Polygon).points.size)
        t.onPointer(ev(PointerPhase.DOWN, 0f, 0f), ctx)
        assertTrue(t.onKey(enter = false)) // Esc abandons
        assertEquals(1, applied.size)
    }

    private fun marquee(shapes: MutableList<SelectionShape>) =
        MarqueeTool(ToolId.RECT_MARQUEE, ellipse = false, { false }, { shape, _, _ ->
            shapes += shape
            true
        })

    @Test
    fun `fixed ratio follows the longer axis and fixed size places a box on click`() {
        val shapes = mutableListOf<SelectionShape>()
        val t = marquee(shapes)
        t.style = MarqueeStyle.FIXED_RATIO
        t.fixedWidth = 16f
        t.fixedHeight = 9f
        t.onPointer(ev(PointerPhase.DOWN, 100f, 100f), ctx)
        t.onPointer(ev(PointerPhase.UP, 260f, 120f), ctx)
        assertEquals(app.croptop.core.model.DocRectF(100f, 100f, 260f, 190f), shapes.last().bounds)

        t.style = MarqueeStyle.FIXED_SIZE
        t.fixedWidth = 50f
        t.fixedHeight = 30f
        t.onPointer(ev(PointerPhase.DOWN, 10f, 10f), ctx)
        t.onPointer(ev(PointerPhase.UP, 10f, 10f), ctx)
        assertEquals(app.croptop.core.model.DocRectF(10f, 10f, 60f, 40f), shapes.last().bounds)
    }

    @Test
    fun `alt marquee centers geometry while keeping subtract mode and fixed dimensions`() {
        val applied = mutableListOf<Pair<SelectionShape, SelectionOp>>()
        val t = MarqueeTool(ToolId.RECT_MARQUEE, ellipse = false, { false }) { shape, op, _ ->
            applied += shape to op
            true
        }

        t.onPointer(ev(PointerPhase.DOWN, 10f, 10f, Modifiers(alt = true)), ctx)
        t.onPointer(ev(PointerPhase.UP, 30f, 20f, Modifiers(alt = true)), ctx)
        assertEquals(SelectionOp.SUBTRACT, applied.last().second)
        assertEquals(app.croptop.core.model.DocRectF(-10f, 0f, 30f, 20f), applied.last().first.bounds)

        t.style = MarqueeStyle.FIXED_SIZE
        t.fixedWidth = 50f
        t.fixedHeight = 30f
        t.onPointer(ev(PointerPhase.DOWN, 10f, 20f, Modifiers(alt = true)), ctx)
        t.onPointer(ev(PointerPhase.UP, 10f, 20f, Modifiers(alt = true)), ctx)
        assertEquals(SelectionOp.SUBTRACT, applied.last().second)
        assertEquals(app.croptop.core.model.DocRectF(-15f, 5f, 35f, 35f), applied.last().first.bounds)
    }

    @Test
    fun `space during a drag moves the box without resizing it`() {
        val shapes = mutableListOf<SelectionShape>()
        val t = marquee(shapes)
        t.onPointer(ev(PointerPhase.DOWN, 0f, 0f), ctx)
        t.onPointer(ev(PointerPhase.MOVE, 40f, 20f), ctx)
        assertTrue(t.dragging)
        t.repositioning = true
        t.onPointer(ev(PointerPhase.MOVE, 140f, 120f), ctx)
        t.repositioning = false
        t.onPointer(ev(PointerPhase.UP, 150f, 120f), ctx)
        // Moved by (100, 100), then widened by 10 after Space was released.
        assertEquals(app.croptop.core.model.DocRectF(100f, 100f, 150f, 120f), shapes.single().bounds)
        assertTrue(!t.dragging)
    }

    @Test
    fun `canvas focus loss releases temporary marquee reposition modifier`() {
        val t = marquee(mutableListOf())
        t.repositioning = true

        t.onFocusLost()

        assertFalse(t.repositioning)
    }

    @Test
    fun `cancelled marquee and lasso gestures do not apply partial selections`() {
        val marqueeShapes = mutableListOf<SelectionShape>()
        val marquee = marquee(marqueeShapes)
        marquee.onPointer(ev(PointerPhase.DOWN, 0f, 0f), ctx)
        marquee.onPointer(ev(PointerPhase.MOVE, 30f, 20f), ctx)
        marquee.onPointer(ev(PointerPhase.CANCEL, 30f, 20f), ctx)
        marquee.onPointer(ev(PointerPhase.UP, 30f, 20f), ctx)
        assertTrue(marqueeShapes.isEmpty())
        assertTrue(!marquee.dragging)

        val lassoShapes = mutableListOf<SelectionShape>()
        val lasso = LassoTool(ToolId.LASSO, polygonal = false) { shape, _, _ ->
            lassoShapes += shape
            true
        }
        lasso.onPointer(ev(PointerPhase.DOWN, 0f, 0f), ctx)
        lasso.onPointer(ev(PointerPhase.MOVE, 30f, 0f), ctx)
        lasso.onPointer(ev(PointerPhase.MOVE, 30f, 30f), ctx)
        lasso.onPointer(ev(PointerPhase.CANCEL, 0f, 30f), ctx)
        assertTrue(lassoShapes.isEmpty())
    }

    @Test
    fun `backspace removes the last polygon point`() {
        val shapes = mutableListOf<SelectionShape>()
        val t = LassoTool(ToolId.POLYGONAL_LASSO, polygonal = true) { shape, _, _ ->
            shapes += shape
            true
        }
        listOf(0f to 0f, 100f to 0f, 500f to 500f).forEach { (x, y) -> t.onPointer(ev(PointerPhase.DOWN, x, y), ctx) }
        assertTrue(t.removeLastPoint())
        t.onPointer(ev(PointerPhase.DOWN, 100f, 100f), ctx)
        assertTrue(t.onKey(enter = true))
        assertEquals(listOf(DocPoint(0f, 0f), DocPoint(100f, 0f), DocPoint(100f, 100f)), (shapes.single() as SelectionShape.Polygon).points)
        assertTrue(!t.removeLastPoint())
    }

    @Test
    fun `polygon backspace to empty resets the next gesture mode and undersized Enter discards`() {
        val appliedOps = mutableListOf<SelectionOp>()
        val t = LassoTool(ToolId.POLYGONAL_LASSO, polygonal = true) { _, op, _ ->
            appliedOps += op
            true
        }

        t.onPointer(ev(PointerPhase.DOWN, 0f, 0f, Modifiers(alt = true)), ctx)
        assertTrue(t.removeLastPoint())
        t.onPointer(ev(PointerPhase.DOWN, 0f, 0f, Modifiers(shift = true)), ctx)
        t.onPointer(ev(PointerPhase.DOWN, 50f, 0f), ctx)
        t.onPointer(ev(PointerPhase.DOWN, 50f, 50f), ctx)
        assertTrue(t.onKey(enter = true))
        assertEquals(listOf(SelectionOp.ADD), appliedOps)

        t.onPointer(ev(PointerPhase.DOWN, 10f, 10f), ctx)
        t.onPointer(ev(PointerPhase.DOWN, 30f, 10f), ctx)
        assertTrue(t.onKey(enter = true))
        assertEquals(listOf(SelectionOp.ADD), appliedOps)
        assertTrue(!t.onKey(enter = false))
    }

    @Test
    fun `bracket steps are finer for small brushes and stay in range`() {
        assertEquals(6f, stepBrushSize(5f, up = true))
        assertEquals(9f, stepBrushSize(10f, up = false))
        assertEquals(20f, stepBrushSize(10f, up = true))
        assertEquals(90f, stepBrushSize(100f, up = false))
        assertEquals(125f, stepBrushSize(100f, up = true))
        assertEquals(1f, stepBrushSize(1f, up = false))
        assertEquals(5000f, stepBrushSize(4950f, up = true))
    }

    @Test
    fun `number keys map to opacity with quick two-digit entry`() {
        val k = PercentKeys(windowMs = 600)
        assertEquals(40, k.press(4, 0))
        assertEquals(45, k.press(5, 300)) // 4 then 5 quickly
        assertEquals(100, k.press(0, 2000))
        assertEquals(5, k.press(5, 2100)) // 0 then 5 → 5%
        assertEquals(70, k.press(7, 5000))
        assertEquals(30, k.press(3, 6000)) // too slow: a fresh single digit
    }
}
