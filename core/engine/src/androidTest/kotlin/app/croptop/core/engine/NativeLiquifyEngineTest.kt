package app.croptop.core.engine

import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.LiquifyDab
import app.croptop.core.engine.api.LiquifyStroke
import app.croptop.core.engine.api.LiquifyTool
import app.croptop.core.engine.api.SampleMode
import app.croptop.core.model.BackgroundContents
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocRectF
import app.croptop.core.model.DocSize
import app.croptop.core.model.FillSource
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.RgbaColor
import app.croptop.core.model.SelectionShape
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeLiquifyEngineTest {
    @Test
    fun forwardWarpIsOneUndoableSelectionBoundedEdit() = runBlocking {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec("Liquify API", DocSize(48, 32), background = BackgroundContents.TRANSPARENT),
        )
        try {
            val layer = checkNotNull(document.layers.value.primary)
            assertTrue(document.select(SelectionShape.Rect(DocRectF(8f, 8f, 24f, 24f)), antiAlias = false) is CommandResult.Accepted)
            assertTrue(document.fill(FillSource.Solid(RgbaColor(1f, 0.1f, 0.05f, 1f))) is CommandResult.Accepted)
            assertTrue(document.deselect() is CommandResult.Accepted)
            assertTrue(document.select(SelectionShape.Rect(DocRectF(8f, 8f, 24f, 24f)), antiAlias = false) is CommandResult.Accepted)
            val inside = (8 until 24 step 2).flatMap { y -> (8 until 24 step 2).map { x -> DocPoint(x + 0.5f, y + 0.5f) } }
            val outside = listOf(DocPoint(7.5f, 16.5f), DocPoint(24.5f, 16.5f), DocPoint(16.5f, 7.5f), DocPoint(16.5f, 24.5f))
            val before = inside.map { document.sample(it, SampleMode.ACTIVE_LAYER).color }
            val outsideBefore = outside.map { document.sample(it, SampleMode.ACTIVE_LAYER).color }
            val historyBefore = document.history.value.current
            val result = document.liquify(
                layer,
                LiquifyStroke(
                    tool = LiquifyTool.FORWARD_WARP,
                    dabs = listOf(LiquifyDab(x = 16.0, y = 16.0, size = 20.0, density = 1f, rate = 1f, dx = -8.0)),
                ),
            )
            assertTrue("Liquify should commit: $result", result is CommandResult.Accepted)
            assertEquals(historyBefore + 1, document.history.value.current)
            val after = inside.map { document.sample(it, SampleMode.ACTIVE_LAYER).color }
            val outsideAfter = outside.map { document.sample(it, SampleMode.ACTIVE_LAYER).color }
            assertTrue("forward warp must alter the raster", before.zip(after).count { (a, b) -> kotlin.math.abs(a.a - b.a) > 0.02f } > 0)
            outsideBefore.zip(outsideAfter).forEachIndexed { index, (beforePixel, afterPixel) ->
                assertEquals("selection freezes outside sample $index", beforePixel.r, afterPixel.r, 0.005f)
                assertEquals(beforePixel.a, afterPixel.a, 0.005f)
            }
            assertTrue(document.undo() is CommandResult.Accepted)
            val undone = document.sample(DocPoint(16.5f, 16.5f), SampleMode.ACTIVE_LAYER).color
            assertEquals(before[inside.indexOf(DocPoint(16.5f, 16.5f))].r, undone.r, 0.005f)
            assertTrue(document.redo() is CommandResult.Accepted)
            assertEquals(
                after[inside.indexOf(DocPoint(16.5f, 16.5f))].r,
                document.sample(DocPoint(16.5f, 16.5f), SampleMode.ACTIVE_LAYER).color.r,
                0.005f,
            )
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun tinySelectionStillWarpsAndClipsEveryPixelOutsideSelection() = runBlocking {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec("High-resolution tiny Liquify selection", DocSize(8000, 6000), background = BackgroundContents.TRANSPARENT),
        )
        try {
            val layer = checkNotNull(document.layers.value.primary)
            assertTrue(document.select(SelectionShape.Rect(DocRectF(8f, 8f, 24f, 24f)), antiAlias = false) is CommandResult.Accepted)
            assertTrue(document.fill(FillSource.Solid(RgbaColor(1f, 0.1f, 0.05f, 1f))) is CommandResult.Accepted)
            assertTrue(document.deselect() is CommandResult.Accepted)
            assertTrue(document.select(SelectionShape.Rect(DocRectF(22f, 15f, 24f, 17f)), antiAlias = false) is CommandResult.Accepted)
            val before = (14..18).flatMap { y -> (21..25).map { x -> DocPoint(x + 0.5f, y + 0.5f) } }
                .map { document.sample(it, SampleMode.ACTIVE_LAYER).color }
            val result = document.liquify(
                layer,
                LiquifyStroke(
                    tool = LiquifyTool.FORWARD_WARP,
                    meshSpacing = 4,
                    dabs = listOf(LiquifyDab(x = 23.0, y = 16.0, size = 16.0, density = 1f, rate = 1f, dx = -7.0)),
                ),
            )
            assertTrue("tiny selection should still receive a liquify warp: $result", result is CommandResult.Accepted)
            val after = before.indices.map { index ->
                val x = 21 + index % 5
                val y = 14 + index / 5
                document.sample(DocPoint(x + 0.5f, y + 0.5f), SampleMode.ACTIVE_LAYER).color
            }
            assertTrue(
                "warp should alter at least one selected edge pixel",
                listOf(6, 7, 11, 12).any {
                    kotlin.math.abs(before[it].a - after[it].a) >
                        0.02f
                },
            )
            before.zip(after).forEachIndexed { index, (a, b) ->
                val x = 21 + index % 5
                val y = 14 + index / 5
                if (x !in 22..23 || y !in 15..16) {
                    assertEquals("pixel ($x,$y) outside exact selection", a.a, b.a, 0.005f)
                    assertEquals(a.r, b.r, 0.005f)
                }
            }
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun unselectedFiveHundredMegapixelMeshSpacingStaysWithinFieldBudget() {
        val spacing = liquifyMeshSpacingForBudget(width = 32_768, height = 15_258, requestedSpacing = 1)
        val columns = (32_768L + spacing - 1) / spacing + 1
        val rows = (15_258L + spacing - 1) / spacing + 1

        assertTrue("spacing 1 must be increased for an unselected 500 MP canvas", spacing > 1)
        assertTrue("the resulting field must stay under 16 million floats", columns * rows * 2 <= 16_000_000L)
    }
}
