package app.croptop.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ViewAidsTest {
    private val doc = DocSize(1000, 600)

    @Test
    fun `snaps to guide, bounds, center and grid within threshold`() {
        val aids = ViewAids(grid = true, gridSettings = GridSettings(100f, 4)).addGuide(Guide(GuideAxis.VERTICAL, 333f))
        val t = aids.snapTargets(GuideAxis.VERTICAL, doc, near = 330f)
        assertEquals(Snapped(333f, SnapTarget(333f, SnapKind.GUIDE)), Snapping.snap(330f, t, 5f))
        assertEquals(500f, Snapping.snap(497f, aids.snapTargets(GuideAxis.VERTICAL, doc, 497f), 5f).value)
        assertEquals(SnapKind.GRID, Snapping.snap(126f, aids.snapTargets(GuideAxis.VERTICAL, doc, 126f), 2f).target?.kind)
        assertNull(Snapping.snap(410f, aids.snapTargets(GuideAxis.VERTICAL, doc, 410f), 5f).target?.takeIf { it.kind != SnapKind.GRID })
    }

    @Test
    fun `guide wins over grid on the same line, snapping off disables targets`() {
        val aids = ViewAids(grid = true).addGuide(Guide(GuideAxis.HORIZONTAL, 200f))
        assertEquals(SnapKind.GUIDE, Snapping.snap(201f, aids.snapTargets(GuideAxis.HORIZONTAL, doc, 201f), 4f).target?.kind)
        assertEquals(emptyList(), aids.copy(snap = false).snapTargets(GuideAxis.HORIZONTAL, doc, 201f))
    }

    @Test
    fun `span snaps by its nearest edge`() {
        val targets = listOf(SnapTarget(500f, SnapKind.CENTER))
        val (delta, hit) = Snapping.snapSpan(listOf(100f, 298f, 496f), targets, 6f)
        assertEquals(4f, delta)
        assertEquals(SnapKind.CENTER, hit?.kind)
    }

    @Test
    fun `guide editing and hit test`() {
        val a = ViewAids().addGuide(Guide(GuideAxis.VERTICAL, 10f)).addGuide(Guide(GuideAxis.HORIZONTAL, 50f))
        assertEquals(1, a.guideAt(DocPoint(300f, 52f), 3f))
        assertNull(a.guideAt(DocPoint(300f, 60f), 3f))
        assertEquals(20f, a.moveGuide(0, 20f).guides[0].position)
        assertEquals(1, a.removeGuide(0).guides.size)
    }

    @Test
    fun `ruler steps are 1-2-5 and keep labels apart`() {
        assertEquals(100f, RulerScale.majorStep(1f))
        assertEquals(50f, RulerScale.majorStep(2f))
        assertEquals(1000f, RulerScale.majorStep(0.1f))
        assertEquals(2f, RulerScale.majorStep(32f))
        assertEquals(listOf(0f, 100f, 200f), RulerScale.ticks(-20f, 250f, 100f))
        assertEquals("150", RulerScale.label(150f))
    }

    @Test
    fun `guide layout splits columns with gutters inside margins`() {
        val g = GuideLayout(columns = 2, columnGutter = 20f, margin = 40f).guides(doc)
        assertEquals(listOf(40f, 490f, 510f, 960f), g.map { it.position })
        assertEquals(emptyList(), GuideLayout(columns = 3, margin = 600f).guides(doc))
        val rows = GuideLayout(rows = 2).guides(doc)
        assertEquals(listOf(0f, 300f, 600f), rows.map { it.position })
    }
}
