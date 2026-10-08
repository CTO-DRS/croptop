package app.croptop.core.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.croptop.core.engine.api.ColorRangeSpec
import app.croptop.core.engine.api.EditCommand
import app.croptop.core.engine.api.HistogramChannel
import app.croptop.core.engine.api.HistogramScope
import app.croptop.core.engine.api.SampleMode
import app.croptop.core.engine.api.StrokeSpec
import app.croptop.core.model.BackgroundContents
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocRectF
import app.croptop.core.model.DocSize
import app.croptop.core.model.LayerId
import app.croptop.core.model.LayerKind
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.RgbaColor
import app.croptop.core.model.SelectionOp
import app.croptop.core.model.SelectionShape
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeHistogramTest {
    @Test
    fun selectionHistogramRefreshesWhenColorRangePreviewChangesWithinSameBounds() = runBlocking {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec(name = "Histogram selection preview", size = DocSize(32, 32), background = BackgroundContents.TRANSPARENT),
        )
        val updates = Channel<app.croptop.core.engine.api.Histogram>(Channel.UNLIMITED)
        val collector = launch {
            document.histogram(HistogramScope.SELECTION).take(3).collect { updates.send(it) }
        }
        try {
            val layer = checkNotNull(document.layers.value.primary)
            suspend fun paint(point: Float, color: RgbaColor) {
                val stroke = document.beginStroke(StrokeSpec(target = layer, color = color, diameter = 6f))
                stroke.add(floatArrayOf(point, point, 1f, 0f, 0f, 0f, 0f))
                assertTrue(stroke.end() is app.croptop.core.engine.api.CommandResult.Accepted)
            }
            val red = RgbaColor(1f, 0f, 0f)
            val blue = RgbaColor(0f, 0f, 1f)
            paint(4f, red)
            paint(28f, red)
            paint(16f, blue)

            val initial = withTimeout(5_000) { updates.receive() }
            assertTrue(initial.statistics.getValue(HistogramChannel.RGB).weightedPixels > 0.0)
            val preview = document.beginColorRange()
            preview.update(ColorRangeSpec(samples = listOf(red), fuzziness = 0))
            val redOnly = withTimeout(5_000) { updates.receive() }
            val redBounds = document.selection.value

            preview.update(ColorRangeSpec(samples = listOf(red, blue), fuzziness = 0))
            val redAndBlue = withTimeout(5_000) { updates.receive() }
            assertEquals("preview adds the blue region without changing selection bounds", redBounds, document.selection.value)
            assertTrue(
                "new selection mask revision refreshes histogram although SelectionInfo is unchanged",
                redAndBlue.statistics.getValue(HistogramChannel.RGB).weightedPixels >
                    redOnly.statistics.getValue(HistogramChannel.RGB).weightedPixels,
            )
            preview.cancel()
            withTimeout(5_000) { collector.join() }
        } finally {
            collector.cancel()
            updates.close()
            document.close()
            engine.close()
        }
    }

    @Test
    fun longLivedFlowPublishesUpdatesAndClearsForNonPixelActiveLayer() = runBlocking {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec(name = "Histogram updates", size = DocSize(32, 32), background = BackgroundContents.TRANSPARENT),
        )
        val updates = Channel<app.croptop.core.engine.api.Histogram>(Channel.UNLIMITED)
        val collector = launch {
            document.histogram(HistogramScope.ACTIVE_LAYER).take(3).collect { updates.send(it) }
        }
        try {
            val layer = checkNotNull(document.layers.value.primary)
            val initial = withTimeout(5_000) { updates.receive() }
            assertEquals(0.0, initial.red.sum(), 0.0)

            val stroke = document.beginStroke(StrokeSpec(target = layer, color = RgbaColor(1f, 0f, 0f), diameter = 12f))
            stroke.add(floatArrayOf(16f, 16f, 1f, 0f, 0f, 0f, 0f))
            assertTrue(stroke.end() is app.croptop.core.engine.api.CommandResult.Accepted)
            val painted = withTimeout(5_000) { updates.receive() }
            assertTrue(painted.red[255] > 0.0)

            val groupId = LayerId(987_654_391L)
            assertTrue(
                document.execute(EditCommand.AddLayer(groupId, "Group", LayerKind.GROUP)) is app.croptop.core.engine.api.CommandResult.Accepted,
            )
            assertTrue(document.setActiveLayers(setOf(groupId), groupId) is app.croptop.core.engine.api.CommandResult.Accepted)
            val empty = withTimeout(5_000) { updates.receive() }
            assertEquals(256, empty.luminosity.size)
            assertEquals(0.0, empty.luminosity.sum(), 0.0)
            assertEquals(5, empty.statistics.size)
            assertTrue(empty.statistics.values.all { it.weightedPixels == 0.0 && it.mean == 0.0 && it.standardDeviation == 0.0 && it.median == 0 })
            withTimeout(5_000) { collector.join() }
        } finally {
            collector.cancel()
            updates.close()
            document.close()
            engine.close()
        }
    }

    @Test
    fun activeLayerFlowPublishesRgbAndLuminosityBinsAfterEdits() = runBlocking {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec(name = "Histogram", size = DocSize(32, 32), background = BackgroundContents.TRANSPARENT),
        )
        try {
            val layer = checkNotNull(document.layers.value.primary)
            val stroke = document.beginStroke(StrokeSpec(target = layer, color = RgbaColor(1f, 0f, 0f), diameter = 12f))
            stroke.add(floatArrayOf(16f, 16f, 1f, 0f, 0f, 0f, 0f))
            assertTrue(stroke.end() is app.croptop.core.engine.api.CommandResult.Accepted)

            val histogram = document.histogram(HistogramScope.ACTIVE_LAYER).first()
            assertSame("unchanged document state reuses the cached histogram", histogram, document.histogram(HistogramScope.ACTIVE_LAYER).first())
            assertEquals(256, histogram.red.size)
            assertEquals(256, histogram.luminosity.size)
            assertTrue("red samples reach the full-intensity bin", histogram.red[255] > 0.0)
            assertTrue("green channel records opaque red pixels in its zero bin", histogram.green[0] > 0.0)
            assertEquals("red pixels do not enter the full-intensity green bin", 0.0, histogram.green[255], 0.0)
            assertTrue("red contributes to the expected luminosity bin", histogram.luminosity[77] > 0.0)
            val redStats = histogram.statistics.getValue(HistogramChannel.RED)
            assertTrue("statistics carry alpha-weighted sample counts", redStats.weightedPixels > 0.0)
            assertEquals(255.0, redStats.mean, 0.0)
            assertEquals(255, redStats.median)
            assertEquals(0.0, redStats.standardDeviation, 0.0)
            assertEquals(77.0, histogram.statistics.getValue(HistogramChannel.LUMINOSITY).mean, 0.0)
            assertEquals(redStats.weightedPixels, histogram.statistics.getValue(HistogramChannel.RGB).weightedPixels, 0.000001)
            assertEquals(5, histogram.statistics.size)
            assertTrue("sampling remains usable", document.sample(DocPoint(16f, 16f), SampleMode.ACTIVE_LAYER).color.r > 0.9f)

            assertTrue(
                document.execute(EditCommand.AddLayer(LayerId(987_654_392L), "Unrelated group", LayerKind.GROUP)) is
                    app.croptop.core.engine.api.CommandResult.Accepted,
            )
            assertTrue(document.setActiveLayers(setOf(layer), layer) is app.croptop.core.engine.api.CommandResult.Accepted)
            assertSame(
                "an unrelated layer-tree edit preserves the active pixel layer histogram",
                histogram,
                document.histogram(HistogramScope.ACTIVE_LAYER).first(),
            )

            val merged = document.histogram(HistogramScope.MERGED).first()
            assertEquals(histogram.red, merged.red)
            assertEquals(histogram.luminosity, merged.luminosity)

            assertTrue(
                document.select(
                    SelectionShape.Rect(DocRectF(14f, 14f, 18f, 18f)),
                    SelectionOp.REPLACE,
                ) is app.croptop.core.engine.api.CommandResult.Accepted,
            )
            assertSame("selection changes do not invalidate the unmasked merged histogram", merged, document.histogram(HistogramScope.MERGED).first())
            assertSame(
                "selection changes do not invalidate the active-layer histogram",
                histogram,
                document.histogram(HistogramScope.ACTIVE_LAYER).first(),
            )
            val selected = document.histogram(HistogramScope.SELECTION).first()
            assertTrue("selection scope masks out pixels outside the selection", selected.red.sum() < histogram.red.sum())
            assertTrue("selected painted pixels remain represented", selected.red[255] > 0.0)
        } finally {
            document.close()
            engine.close()
        }
    }
}
