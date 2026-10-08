package app.croptop.core.engine

import android.content.Context
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.EditCommand
import app.croptop.core.engine.api.ExportFormat
import app.croptop.core.engine.api.ExportOptions
import app.croptop.core.engine.api.ExportTarget
import app.croptop.core.engine.api.StrokeSpec
import app.croptop.core.model.BackgroundContents
import app.croptop.core.model.DocSize
import app.croptop.core.model.LayerId
import app.croptop.core.model.LayerKind
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.RgbaColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeParallelRenderTest {
    @Test
    fun canceledCompositePreviewStopsBeforeRenderingTiles() {
        val engine = NativeEngine()
        val store = engine.createTileStore(width = 1024, height = 768, format = 0)
        val cancellationId = engine.createRenderCancellation()
        try {
            assertTrue("native cancellation token was created", cancellationId != 0L)
            engine.cancelRender(cancellationId)
            val preview = engine.renderCompositePreview(
                width = 1024,
                height = 768,
                layers = listOf(NativeRenderLayer(store, 1L, 0, 255, 255, true)),
                maxDimension = 1024,
                cancellationId = cancellationId,
            )
            assertNull("canceled native composition does not publish a partial bitmap", preview)
        } finally {
            engine.destroyRenderCancellation(cancellationId)
            engine.destroyTileStore(store)
            engine.close()
        }
    }

    @Test
    fun multiTileViewportPreviewUsesTheNativeRenderPool() = runBlocking {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec("Parallel viewport", DocSize(4000, 3000), background = BackgroundContents.TRANSPARENT),
        ) as NativeDocument
        try {
            val layer = checkNotNull(document.layers.value.primary)
            val stroke = document.beginStroke(
                StrokeSpec(target = layer, color = RgbaColor(0.1f, 0.9f, 0.25f, 1f), diameter = 96f, hardness = 1f),
            )
            stroke.add(floatArrayOf(1940f, 1450f, 1f, 0f, 0f, 0f, 0f))
            stroke.add(floatArrayOf(2020f, 1530f, 1f, 0f, 0f, 0f, 0.01f))
            val result = stroke.end()
            assertTrue(result.toString(), result is CommandResult.Accepted)

            withTimeout(15_000L) { document.awaitViewportPreview() }
            assertTrue("composite preview should be the completed result", document.hasCompositeViewportPreview)
            val preview = checkNotNull(document.viewportPreview)
            assertEquals(500, preview.width)
            assertEquals(375, preview.height)
            val center = preview.getPixel(248, 186)
            assertTrue("rendered brush mark has alpha", android.graphics.Color.alpha(center) > 240)
            assertTrue("rendered brush mark keeps its green channel", android.graphics.Color.green(center) > 180)
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun supersedingViewportRenderPublishesNewestDocumentPixels() = runBlocking {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec("Superseded viewport", DocSize(6000, 4000), background = BackgroundContents.TRANSPARENT),
        ) as NativeDocument
        try {
            val layer = checkNotNull(document.layers.value.primary)
            repeat(24) { index ->
                val command = EditCommand.AddLayer(LayerId(91000L + index), "Render load $index", LayerKind.PIXEL)
                val result = document.execute(command)
                assertTrue(result.toString(), result is CommandResult.Accepted)
            }
            suspend fun paint(x: Float, y: Float, color: RgbaColor) {
                val stroke = document.beginStroke(
                    StrokeSpec(target = layer, color = color, diameter = 128f, hardness = 1f),
                )
                stroke.add(floatArrayOf(x, y, 1f, 0f, 0f, 0f, 0f))
                val result = stroke.end()
                assertTrue(result.toString(), result is CommandResult.Accepted)
            }

            paint(600f, 600f, RgbaColor(0.95f, 0.1f, 0.1f, 1f))
            // Give the first 24 MP, 25-layer composition time to enter native tile work before
            // the next stroke replaces its generation and cancellation token.
            delay(25L)
            paint(5400f, 3400f, RgbaColor(0.1f, 0.95f, 0.1f, 1f))
            withTimeout(20_000L) { document.awaitViewportPreview() }

            assertTrue(
                "the latest generation owns the published composite",
                document.hasCompositeViewportPreview,
            )
            val preview = checkNotNull(document.viewportPreview)
            val xScale = preview.width.toFloat() / 6000f
            val yScale = preview.height.toFloat() / 4000f
            val first = preview.getPixel((600f * xScale).toInt(), (600f * yScale).toInt())
            val second = preview.getPixel((5400f * xScale).toInt(), (3400f * yScale).toInt())
            assertTrue(
                "the earlier stroke remains in the newest composite",
                android.graphics.Color.red(first) > 180,
            )
            assertTrue(
                "the superseding stroke appears in the newest composite",
                android.graphics.Color.green(second) > 180,
            )
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun repeatedViewportChurnPublishesEveryCommittedPixelAndSettles() = runBlocking {
        val engine = NativeEngine()
        val width = 4000
        val height = 3000
        val document = engine.newDocument(
            NewDocumentSpec("Repeated viewport churn", DocSize(width, height), background = BackgroundContents.TRANSPARENT),
        ) as NativeDocument
        try {
            val target = checkNotNull(document.layers.value.primary)
            repeat(8) { index ->
                val result = document.execute(EditCommand.AddLayer(LayerId(92000L + index), "Churn load $index", LayerKind.PIXEL))
                assertTrue(result.toString(), result is CommandResult.Accepted)
            }
            suspend fun paint(x: Float, y: Float, color: RgbaColor) {
                val stroke = document.beginStroke(
                    StrokeSpec(target = target, color = color, diameter = 128f, hardness = 1f),
                )
                stroke.add(floatArrayOf(x, y, 1f, 0f, 0f, 0f, 0f))
                val result = stroke.end()
                assertTrue(result.toString(), result is CommandResult.Accepted)
            }

            val edits = listOf(
                Triple(350f, 300f, RgbaColor(0.95f, 0.08f, 0.08f, 1f)),
                Triple(1200f, 900f, RgbaColor(0.08f, 0.95f, 0.08f, 1f)),
                Triple(2600f, 1800f, RgbaColor(0.08f, 0.08f, 0.95f, 1f)),
                Triple(3800f, 2800f, RgbaColor(0.95f, 0.85f, 0.08f, 1f)),
            )
            edits.forEachIndexed { index, (x, y, color) ->
                paint(x, y, color)
                if (index != edits.lastIndex) delay(25L)
            }

            val startedAt = android.os.SystemClock.elapsedRealtime()
            withTimeout(20_000L) { document.awaitViewportPreview() }
            val settleMillis = android.os.SystemClock.elapsedRealtime() - startedAt
            assertTrue("the newest preview generation was committed", document.hasCompositeViewportPreview)
            val preview = checkNotNull(document.viewportPreview)
            val scaleX = preview.width.toFloat() / width
            val scaleY = preview.height.toFloat() / height
            edits.forEach { (x, y, color) ->
                val pixel = preview.getPixel((x * scaleX).toInt(), (y * scaleY).toInt())
                assertTrue("mark at ($x, $y) has alpha", android.graphics.Color.alpha(pixel) > 240)
                assertTrue(
                    "mark at ($x, $y) retains its red channel",
                    kotlin.math.abs(android.graphics.Color.red(pixel) - (color.r * 255f).toInt()) < 55,
                )
                assertTrue(
                    "mark at ($x, $y) retains its green channel",
                    kotlin.math.abs(android.graphics.Color.green(pixel) - (color.g * 255f).toInt()) < 55,
                )
                assertTrue(
                    "mark at ($x, $y) retains its blue channel",
                    kotlin.math.abs(android.graphics.Color.blue(pixel) - (color.b * 255f).toInt()) < 55,
                )
            }
            android.util.Log.i("CropTopRenderTest", "four superseding 12 MP viewport edits settled in ${settleMillis}ms")
            Unit
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun multiTilePngFlattenPreservesSparsePixels() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = NativeEngine(context)
        val document = engine.newDocument(
            NewDocumentSpec("Parallel flatten", DocSize(4000, 3000), background = BackgroundContents.TRANSPARENT),
        ) as NativeDocument
        val target = context.cacheDir.resolve("root-parallel-flatten-${System.nanoTime()}.png")
        try {
            val bottom = checkNotNull(document.layers.value.primary)
            val bottomStroke = document.beginStroke(
                StrokeSpec(target = bottom, color = RgbaColor(0.05f, 0.2f, 0.9f, 1f), diameter = 96f, hardness = 1f),
            )
            bottomStroke.add(floatArrayOf(240f, 240f, 1f, 0f, 0f, 0f, 0f))
            bottomStroke.add(floatArrayOf(320f, 320f, 1f, 0f, 0f, 0f, 0.01f))
            val bottomResult = bottomStroke.end()
            assertTrue(bottomResult.toString(), bottomResult is CommandResult.Accepted)
            val top = LayerId(89001)
            val addLayer = document.execute(EditCommand.AddLayer(top, "Top", LayerKind.PIXEL))
            assertTrue(addLayer.toString(), addLayer is CommandResult.Accepted)
            val activeResult = document.setActiveLayers(setOf(top), top)
            assertTrue(activeResult.toString(), activeResult is CommandResult.Accepted)
            val stroke = document.beginStroke(
                StrokeSpec(target = top, color = RgbaColor(0.1f, 0.9f, 0.25f, 1f), diameter = 96f, hardness = 1f),
            )
            stroke.add(floatArrayOf(1940f, 1450f, 1f, 0f, 0f, 0f, 0f))
            stroke.add(floatArrayOf(2020f, 1530f, 1f, 0f, 0f, 0f, 0.01f))
            val strokeResult = stroke.end()
            assertTrue(strokeResult.toString(), strokeResult is CommandResult.Accepted)

            document.export(ExportTarget.AppFile(target.absolutePath), ExportOptions(format = ExportFormat.PNG))
            val bitmap = checkNotNull(BitmapFactory.decodeFile(target.absolutePath))
            try {
                assertEquals(4000, bitmap.width)
                assertEquals(3000, bitmap.height)
                val painted = bitmap.getPixel(1980, 1490)
                assertTrue("flattened brush mark has alpha", android.graphics.Color.alpha(painted) > 240)
                assertTrue("flattened brush mark preserves green", android.graphics.Color.green(painted) > 180)
                val lowerTile = bitmap.getPixel(280, 280)
                assertTrue("flattened lower tile preserves blue", android.graphics.Color.blue(lowerTile) > 180)
                assertEquals("unpainted transparent canvas stays sparse", 0, android.graphics.Color.alpha(bitmap.getPixel(1000, 1000)))
            } finally {
                bitmap.recycle()
            }
        } finally {
            target.delete()
            document.close()
            engine.close()
        }
    }
}
