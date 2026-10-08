package app.croptop.core.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.croptop.core.engine.api.CommandResult
import app.croptop.core.engine.api.EditCommand
import app.croptop.core.engine.api.ExportFormat
import app.croptop.core.engine.api.ExportOptions
import app.croptop.core.engine.api.ExportTarget
import app.croptop.core.engine.api.LayerProperties
import app.croptop.core.engine.api.OpenOptions
import app.croptop.core.engine.api.SampleMode
import app.croptop.core.engine.api.SaveOptions
import app.croptop.core.engine.api.SaveTarget
import app.croptop.core.model.BackgroundContents
import app.croptop.core.model.BlendMode
import app.croptop.core.model.ColorProfileRef
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocSize
import app.croptop.core.model.FillSource
import app.croptop.core.model.LayerId
import app.croptop.core.model.LayerKind
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.RgbaColor
import java.util.Random
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeMergeExportTest {
    private fun accepted(result: CommandResult) = assertTrue(result.toString(), result is CommandResult.Accepted)

    @Test
    fun mergeDownBakesBothOpacitiesAndPreservesUndo() = mergeDownWithOpacity(null)

    @Test
    fun mergeDownInsideOpaqueGroupPreservesComposite() = mergeDownWithOpacity(1f)

    @Test
    fun reducedOpacityGroupMergeRejectsWithoutChangingPixels() = mergeDownWithOpacity(0.5f)

    private fun mergeDownWithOpacity(groupOpacity: Float?) {
        val engine = NativeEngine()
        val document = engine.newDocument(NewDocumentSpec("Merge opacity", DocSize(16, 16), background = BackgroundContents.TRANSPARENT))
        try {
            val bottom = checkNotNull(document.layers.value.primary)
            val parent = groupOpacity?.let { opacity ->
                val group = LayerId(81005)
                accepted(document.execute(EditCommand.AddLayer(group, "Parent", LayerKind.GROUP)))
                accepted(document.execute(EditCommand.SetLayerProps(group, LayerProperties(opacity = opacity))))
                accepted(document.execute(EditCommand.MoveLayer(bottom, parentId = group, index = 0)))
                accepted(document.setActiveLayers(setOf(bottom), bottom))
                group
            }
            accepted(document.fill(FillSource.Solid(RgbaColor(0f, 0f, 1f, 1f))))
            accepted(document.execute(EditCommand.SetLayerProps(bottom, LayerProperties(opacity = 0.5f))))
            val top = LayerId(81001)
            accepted(document.execute(EditCommand.AddLayer(top, "Top", LayerKind.PIXEL, parentId = parent)))
            accepted(document.fill(FillSource.Solid(RgbaColor(1f, 0f, 0f, 1f))))
            accepted(document.execute(EditCommand.SetLayerProps(top, LayerProperties(opacity = 0.5f))))
            val point = DocPoint(8f, 8f)
            val before = document.sample(point, SampleMode.MERGED).color
            val history = document.history.value.current
            if (groupOpacity != null && groupOpacity != 1f) {
                assertTrue(document.mergeDown(top) is CommandResult.Rejected)
                assertEquals(history, document.history.value.current)
                assertEquals(before, document.sample(point, SampleMode.MERGED).color)
                return
            }
            accepted(document.mergeDown(top))
            assertEquals(history + 1, document.history.value.current)
            val after = document.sample(point, SampleMode.MERGED).color
            assertEquals("merge preserves alpha", before.a, after.a, 0.01f)
            assertEquals("merge preserves red", before.r, after.r, 0.01f)
            assertEquals("merge preserves blue", before.b, after.b, 0.01f)
            accepted(document.undo())
            assertEquals(2, document.layers.value.flatten().count { it.kind == LayerKind.PIXEL })
            accepted(document.redo())
            assertEquals(1, document.layers.value.flatten().count { it.kind == LayerKind.PIXEL })
            assertEquals(after, document.sample(point, SampleMode.MERGED).color)
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun multiLayerRasterExportsMatchMergedPixels() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = NativeEngine(context)
        val document = engine.newDocument(
            NewDocumentSpec(
                "Export composite",
                DocSize(16, 16),
                profile = ColorProfileRef.Srgb,
                background = BackgroundContents.TRANSPARENT,
            ),
        )
        val targets = listOf(
            ExportFormat.PNG to "png",
            ExportFormat.JPEG to "jpg",
            ExportFormat.WEBP to "webp",
        ).associate { (format, extension) ->
            format to context.cacheDir.resolve("root-merge-export-${format.name.lowercase()}-${System.nanoTime()}.$extension")
        }
        try {
            accepted(document.fill(FillSource.Solid(RgbaColor(0f, 0f, 1f, 1f))))
            val top = LayerId(81006)
            accepted(document.execute(EditCommand.AddLayer(top, "Top", LayerKind.PIXEL)))
            accepted(document.fill(FillSource.Solid(RgbaColor(1f, 0f, 0f, 1f))))
            accepted(document.execute(EditCommand.SetLayerProps(top, LayerProperties(opacity = 0.5f))))
            val expected = document.sample(DocPoint(8f, 8f), SampleMode.MERGED).color
            targets.forEach { (format, target) ->
                document.export(ExportTarget.AppFile(target.absolutePath), ExportOptions(format = format, quality = 100))
                val bitmap = checkNotNull(BitmapFactory.decodeFile(target.absolutePath))
                try {
                    val actual = bitmap.getPixel(8, 8)
                    val tolerance = when (format) {
                        ExportFormat.PNG -> 2f
                        ExportFormat.JPEG -> 24f
                        ExportFormat.WEBP -> 12f
                        else -> error("Unexpected raster export format: $format")
                    }
                    assertEquals("$format red", expected.r * 255f, Color.red(actual).toFloat(), tolerance)
                    assertEquals("$format green", expected.g * 255f, Color.green(actual).toFloat(), tolerance)
                    assertEquals("$format blue", expected.b * 255f, Color.blue(actual).toFloat(), tolerance)
                    assertEquals("$format alpha", expected.a * 255f, Color.alpha(actual).toFloat(), tolerance)
                } finally {
                    bitmap.recycle()
                }
            }
        } finally {
            targets.values.forEach { it.delete() }
            document.close()
            engine.close()
        }
    }

    @Test
    fun rasterExportRunsOutsideDocumentLockAndCloseWaitsForSnapshotReaders() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = context.cacheDir.resolve("root-export-snapshot-${System.nanoTime()}.png")
        val width = 512
        val height = 512
        val sourcePixels = IntArray(width * height).also { pixels ->
            val random = Random(0x43524f50L)
            pixels.indices.forEach { index ->
                pixels[index] = Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256))
            }
        }
        val sourceBitmap = Bitmap.createBitmap(sourcePixels, width, height, Bitmap.Config.ARGB_8888)
        try {
            source.outputStream().use { check(sourceBitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            sourceBitmap.recycle()
        }

        val engine = NativeEngine(context)
        val document = engine.open(app.croptop.core.engine.api.DocumentSource.AppFile(source.absolutePath), OpenOptions())
        val exportUri = Uri.parse("content://${context.packageName}.blocking-export-test/result.png")
        BlockingExportPipe.reset()
        var exported: kotlinx.coroutines.Deferred<app.croptop.core.engine.api.ExportResult>? = null
        var close: kotlinx.coroutines.Deferred<Unit>? = null
        try {
            val overlay = LayerId(81031)
            accepted(document.execute(EditCommand.AddLayer(overlay, "Concurrent edit", LayerKind.PIXEL)))
            accepted(document.setActiveLayers(setOf(overlay), overlay))
            val exporting = async(Dispatchers.IO) {
                document.export(ExportTarget.ContentUri(exportUri.toString()), ExportOptions(format = ExportFormat.PNG))
            }
            exported = exporting
            assertTrue(
                "export did not acquire its output stream",
                withContext(Dispatchers.IO) { BlockingExportPipe.awaitOpen(10, TimeUnit.SECONDS) },
            )
            // The provider deliberately does not read. Noisy pixels produce enough PNG output to fill
            // the kernel pipe and suspend native encoding at a deterministic point.
            delay(500)

            val edit = async(Dispatchers.Default) {
                document.fill(FillSource.Solid(RgbaColor(0f, 0f, 1f, 1f)))
            }
            accepted(withTimeout(2_000) { edit.await() })

            val closing = async(Dispatchers.IO) { document.close() }
            close = closing
            delay(100)
            assertFalse("close must wait while export owns native snapshots", closing.isCompleted)

            val output = async(Dispatchers.IO) { BlockingExportPipe.drain() }
            withTimeout(30_000) { exporting.await() }
            val encoded = withTimeout(30_000) { output.await() }
            val exportedBitmap = checkNotNull(BitmapFactory.decodeByteArray(encoded, 0, encoded.size))
            try {
                val expected = sourcePixels[128 * width + 128]
                val actual = exportedBitmap.getPixel(128, 128)
                assertTrue("export reflects its immutable pre-edit snapshot", kotlin.math.abs(Color.blue(expected) - Color.blue(actual)) <= 1)
                assertTrue("export retains the source red channel", kotlin.math.abs(Color.red(expected) - Color.red(actual)) <= 1)
                assertTrue("export retains the source green channel", kotlin.math.abs(Color.green(expected) - Color.green(actual)) <= 1)
            } finally {
                exportedBitmap.recycle()
            }
            withTimeout(10_000) { closing.await() }
        } finally {
            if (exported?.isActive == true) {
                runCatching { withContext(Dispatchers.IO) { BlockingExportPipe.drain() } }
                runCatching { withTimeout(30_000) { exported.await() } }
            }
            runCatching { withTimeout(10_000) { close?.await() } }
            document.close()
            engine.close()
            source.delete()
        }
    }

    @Test
    fun saveKeepsItsNativeSnapshotAliveUntilArchiveWritingFinishes() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = context.cacheDir.resolve("root-save-snapshot-${System.nanoTime()}.png")
        val width = 512
        val height = 512
        val sourcePixels = IntArray(width * height).also { pixels ->
            val random = Random(0x53415645L)
            pixels.indices.forEach { index ->
                pixels[index] = Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256))
            }
        }
        val sourceBitmap = Bitmap.createBitmap(sourcePixels, width, height, Bitmap.Config.ARGB_8888)
        try {
            source.outputStream().use { check(sourceBitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            sourceBitmap.recycle()
        }

        val engine = NativeEngine(context)
        val document = engine.open(app.croptop.core.engine.api.DocumentSource.AppFile(source.absolutePath), OpenOptions())
        val saveUri = Uri.parse("content://${context.packageName}.blocking-export-test/archive.croptop")
        BlockingExportPipe.reset()
        var saving: kotlinx.coroutines.Deferred<Unit>? = null
        var closing: kotlinx.coroutines.Deferred<Unit>? = null
        try {
            val save = async(Dispatchers.IO) { document.save(SaveTarget.ContentUri(saveUri.toString()), SaveOptions()) }
            saving = save
            assertTrue(
                "save did not acquire its output stream",
                withContext(Dispatchers.IO) { BlockingExportPipe.awaitOpen(10, TimeUnit.SECONDS) },
            )
            delay(500)
            val close = async(Dispatchers.IO) { document.close() }
            closing = close
            delay(100)
            assertFalse("close must wait while save owns native snapshots", close.isCompleted)

            val output = async(Dispatchers.IO) { BlockingExportPipe.drain() }
            withTimeout(30_000) { save.await() }
            assertTrue("serialized archive is non-empty", withTimeout(30_000) { output.await() }.isNotEmpty())
            withTimeout(10_000) { close.await() }
        } finally {
            if (saving?.isActive == true) {
                runCatching { withContext(Dispatchers.IO) { BlockingExportPipe.drain() } }
                runCatching { withTimeout(30_000) { saving.await() } }
            }
            runCatching { withTimeout(10_000) { closing?.await() } }
            document.close()
            engine.close()
            source.delete()
        }
    }

    @Test
    fun stampInsideGroupIsPlacedAtRootWithoutReapplyingGroupOpacity() {
        val engine = NativeEngine()
        val document = engine.newDocument(NewDocumentSpec("Stamp group", DocSize(16, 16), background = BackgroundContents.TRANSPARENT))
        try {
            val group = LayerId(81002)
            val child = LayerId(81003)
            val stamp = LayerId(81004)
            accepted(document.execute(EditCommand.AddLayer(group, "Group", LayerKind.GROUP)))
            accepted(document.execute(EditCommand.SetLayerProps(group, LayerProperties(opacity = 0.5f))))
            accepted(document.execute(EditCommand.AddLayer(child, "Child", LayerKind.PIXEL, parentId = group)))
            accepted(document.fill(FillSource.Solid(RgbaColor(1f, 0f, 0f, 1f))))
            val before = document.sample(DocPoint(8f, 8f), SampleMode.MERGED).color
            accepted(document.stampVisible(stamp))
            assertEquals(null, document.layers.value.parentOf(stamp))
            val stamped = document.sample(DocPoint(8f, 8f), SampleMode.ACTIVE_LAYER).color
            assertEquals(before.a, stamped.a, 0.01f)
            accepted(document.execute(EditCommand.SetLayerProps(group, LayerProperties(visible = false))))
            val isolated = document.sample(DocPoint(8f, 8f), SampleMode.MERGED).color
            assertEquals(before.a, isolated.a, 0.01f)
            assertEquals(before.r, isolated.r, 0.01f)
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun isolatedGroupBlendCompositesItsChildrenBeforeApplyingGroupMode() {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec("Isolated group blend", DocSize(16, 16), background = BackgroundContents.TRANSPARENT),
        )
        try {
            val background = checkNotNull(document.layers.value.primary)
            accepted(document.fill(FillSource.Solid(RgbaColor(0f, 0f, 1f, 1f))))
            val group = LayerId(81020)
            val nested = LayerId(81022)
            val child = LayerId(81021)
            accepted(document.execute(EditCommand.AddLayer(group, "Isolated", LayerKind.GROUP, index = 0)))
            accepted(document.execute(EditCommand.AddLayer(nested, "Nested Pass Through", LayerKind.GROUP, parentId = group)))
            accepted(document.execute(EditCommand.SetLayerProps(nested, LayerProperties(blendMode = BlendMode.PASS_THROUGH))))
            accepted(document.execute(EditCommand.AddLayer(child, "Multiply red", LayerKind.PIXEL, parentId = nested)))
            accepted(document.fill(FillSource.Solid(RgbaColor(1f, 0f, 0f, 1f))))
            accepted(document.execute(EditCommand.SetLayerProps(child, LayerProperties(blendMode = BlendMode.MULTIPLY))))

            accepted(document.execute(EditCommand.SetLayerProps(group, LayerProperties(blendMode = BlendMode.NORMAL))))
            val isolated = document.sample(DocPoint(8f, 8f), SampleMode.MERGED).color
            assertTrue("isolated group composites Multiply over transparency", isolated.r > 0.98f)
            assertTrue("normal group result remains opaque red", isolated.b < 0.02f && isolated.a > 0.98f)
            val isolatedBelow = document.sampleBelow(child, DocPoint(8f, 8f)).color
            assertTrue("an isolated group's external backdrop is not inside its child stack", isolatedBelow.a < 0.02f)

            accepted(document.execute(EditCommand.SetLayerProps(group, LayerProperties(opacity = 0.5f))))
            val halfOpacity = document.sample(DocPoint(8f, 8f), SampleMode.MERGED).color
            assertTrue("group opacity applies once to the isolated result", halfOpacity.r in 0.68f..0.79f)
            assertTrue("group opacity reveals the blue backdrop", halfOpacity.b in 0.68f..0.79f)
            accepted(document.execute(EditCommand.SetLayerProps(group, LayerProperties(opacity = 1f))))

            accepted(document.execute(EditCommand.SetLayerProps(group, LayerProperties(blendMode = BlendMode.PASS_THROUGH))))
            val passThrough = document.sample(DocPoint(8f, 8f), SampleMode.MERGED).color
            assertTrue("Pass Through child Multiply interacts with the blue backdrop", passThrough.r < 0.02f)
            assertTrue(passThrough.b < 0.02f && passThrough.a > 0.98f)
            assertTrue("isolated and Pass Through group results differ", kotlin.math.abs(isolated.r - passThrough.r) > 0.9f)
            val passThroughBelow = document.sampleBelow(child, DocPoint(8f, 8f)).color
            assertTrue("Pass Through exposes the external blue backdrop below its child", passThroughBelow.b > 0.98f)
            assertTrue(passThroughBelow.r < 0.02f && passThroughBelow.a > 0.98f)
            assertTrue(document.layers.value.find(background) != null)
        } finally {
            document.close()
            engine.close()
        }
    }
}
