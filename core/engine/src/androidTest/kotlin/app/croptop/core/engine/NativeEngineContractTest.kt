package app.croptop.core.engine

import android.content.ComponentCallbacks2
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Debug
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.croptop.core.engine.api.DocumentSource
import app.croptop.core.engine.api.EditCommand
import app.croptop.core.engine.api.Engine
import app.croptop.core.engine.api.EngineContractTest
import app.croptop.core.engine.api.EngineEvent
import app.croptop.core.engine.api.ExportFormat
import app.croptop.core.engine.api.ExportOptions
import app.croptop.core.engine.api.ExportTarget
import app.croptop.core.engine.api.LayerProperties
import app.croptop.core.engine.api.OpenOptions
import app.croptop.core.engine.api.SampleMode
import app.croptop.core.engine.api.SaveTarget
import app.croptop.core.engine.api.StrokeSpec
import app.croptop.core.engine.api.TransformTarget
import app.croptop.core.io.ImageFormat
import app.croptop.core.io.RasterMetadataReader
import app.croptop.core.model.BackgroundContents
import app.croptop.core.model.BrushSettings
import app.croptop.core.model.ColorDepth
import app.croptop.core.model.DocPoint
import app.croptop.core.model.DocRectF
import app.croptop.core.model.DocSize
import app.croptop.core.model.FillSource
import app.croptop.core.model.LayerId
import app.croptop.core.model.LayerKind
import app.croptop.core.model.Matrix3
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.RgbaColor
import app.croptop.core.model.SelectionShape
import app.croptop.core.model.ViewportState
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeEngineContractTest : EngineContractTest() {
    override fun createEngine(): Engine = NativeEngine()

    @Test
    fun nativeTiffAndPsdExportOpenRoundTripPixels() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = NativeEngine(context)
        val document = engine.newDocument(
            NewDocumentSpec(name = "Codec extra round trip", size = DocSize(32, 32), background = BackgroundContents.TRANSPARENT),
        )
        try {
            val layer = checkNotNull(document.layers.value.primary)
            val stroke = document.beginStroke(StrokeSpec(target = layer, color = RgbaColor(0.9f, 0.2f, 0.1f), diameter = 12f))
            stroke.add(floatArrayOf(16f, 16f, 1f, 0f, 0f, 0f, 0f))
            assertTrue(stroke.end() is app.croptop.core.engine.api.CommandResult.Accepted)
            document.awaitViewportPreview()
            val expected = document.sample(DocPoint(16f, 16f), SampleMode.ACTIVE_LAYER).color
            for ((format, extension) in listOf(ExportFormat.TIFF to "tif", ExportFormat.PSD to "psd")) {
                val path = context.cacheDir.resolve("croptop-roundtrip-${format.name.lowercase()}-${System.nanoTime()}.$extension")
                try {
                    document.export(ExportTarget.AppFile(path.absolutePath), ExportOptions(format = format))
                    val reopened = engine.open(DocumentSource.AppFile(path.absolutePath), OpenOptions())
                    try {
                        val actual = reopened.sample(DocPoint(16f, 16f), SampleMode.MERGED).color
                        assertTrue("$format red channel", kotlin.math.abs(expected.r - actual.r) < 0.02f)
                        assertTrue("$format green channel", kotlin.math.abs(expected.g - actual.g) < 0.02f)
                        assertTrue("$format blue channel", kotlin.math.abs(expected.b - actual.b) < 0.02f)
                        assertTrue("$format alpha channel", kotlin.math.abs(expected.a - actual.a) < 0.02f)
                    } finally {
                        reopened.close()
                    }
                } finally {
                    path.delete()
                }
            }
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun nativeBrushStrokeWritesPixelsAndUndoRedoRestoresOneHistoryStep() {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec(
                name = "Brush integration",
                size = DocSize(32, 32),
                background = BackgroundContents.TRANSPARENT,
            ),
        ) as NativeDocument
        try {
            val layer = checkNotNull(document.layers.value.primary)
            val baseline = document.history.value.current
            val stroke = document.beginStroke(
                StrokeSpec(
                    target = layer,
                    color = RgbaColor(1f, 0f, 0f, 1f),
                    diameter = 8f,
                    hardness = 1f,
                ),
            )
            // Split the samples across calls to exercise the actual JNI streaming path.
            stroke.add(floatArrayOf(8f, 16f, 1f, 0f, 0f, 0f, 0f))
            stroke.add(floatArrayOf(16f, 16f, 1f, 0f, 0f, 0f, 0.01f))
            assertTrue(stroke.end() is app.croptop.core.engine.api.CommandResult.Accepted)
            runBlocking { document.awaitViewportPreview() }
            assertTrue("await returns after the first committed preview is generated", document.hasCompositeViewportPreview)

            val painted = document.sample(DocPoint(12f, 16f), SampleMode.ACTIVE_LAYER).color
            assertTrue("JNI brush should paint visible alpha", painted.a > 0.99f)
            assertTrue("JNI brush should preserve source color", painted.r > 0.99f && painted.g < 0.01f && painted.b < 0.01f)
            assertEquals("a stroke commits exactly one undo entry", baseline + 1, document.history.value.current)

            assertTrue(document.undo() is app.croptop.core.engine.api.CommandResult.Accepted)
            assertEquals(0f, document.sample(DocPoint(12f, 16f), SampleMode.ACTIVE_LAYER).color.a, 0f)
            assertEquals(baseline, document.history.value.current)

            assertTrue(document.redo() is app.croptop.core.engine.api.CommandResult.Accepted)
            assertEquals(painted, document.sample(DocPoint(12f, 16f), SampleMode.ACTIVE_LAYER).color)
            assertEquals(baseline + 1, document.history.value.current)
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun nativeBrushUsesSelectionCapturedAtStrokeStartAndClipsPixels() {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec(
                name = "Brush selection",
                size = DocSize(32, 32),
                background = BackgroundContents.TRANSPARENT,
            ),
        ) as NativeDocument
        try {
            val layer = checkNotNull(document.layers.value.primary)
            assertTrue(
                document.select(SelectionShape.Rect(DocRectF(4f, 4f, 20f, 20f)), antiAlias = false) is
                    app.croptop.core.engine.api.CommandResult.Accepted,
            )
            val stroke = document.beginStroke(
                StrokeSpec(
                    target = layer,
                    color = RgbaColor(1f, 0f, 0f, 1f),
                    diameter = 8f,
                    hardness = 1f,
                ),
            )
            stroke.add(floatArrayOf(19.5f, 12.5f, 1f, 0f, 0f, 0f, 0f))

            // A selection change while samples are queued must not widen this stroke.
            assertTrue(document.deselect() is app.croptop.core.engine.api.CommandResult.Accepted)
            assertTrue(stroke.end() is app.croptop.core.engine.api.CommandResult.Accepted)
            val inside = document.sample(DocPoint(18f, 12f), SampleMode.ACTIVE_LAYER).color
            val outside = document.sample(DocPoint(22f, 12f), SampleMode.ACTIVE_LAYER).color
            assertTrue("pixels inside the stroke-start selection are painted", inside.a > 0.99f && inside.r > 0.99f)
            assertEquals("pixels beyond the stroke-start selection remain transparent", 0f, outside.a, 0.001f)
            assertEquals("the later deselection remains current", app.croptop.core.model.SelectionInfo.None, document.selection.value)
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun nativeEraserPreservesPixelsOutsideSelection() {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec(
                name = "Eraser selection",
                size = DocSize(32, 32),
                background = BackgroundContents.TRANSPARENT,
            ),
        ) as NativeDocument
        try {
            val layer = checkNotNull(document.layers.value.primary)
            assertTrue(
                document.fill(
                    FillSource.Solid(RgbaColor(1f, 0f, 0f, 1f)),
                    opacity = 1f,
                    blendMode = app.croptop.core.model.BlendMode.NORMAL,
                ) is app.croptop.core.engine.api.CommandResult.Accepted,
            )
            assertTrue(
                document.select(SelectionShape.Rect(DocRectF(4f, 4f, 20f, 20f)), antiAlias = false) is
                    app.croptop.core.engine.api.CommandResult.Accepted,
            )
            val eraser = document.beginStroke(
                StrokeSpec(target = layer, color = RgbaColor.Black, diameter = 8f, hardness = 1f, erase = true),
            )
            eraser.add(floatArrayOf(19.5f, 12.5f, 1f, 0f, 0f, 0f, 0f))
            assertTrue(eraser.end() is app.croptop.core.engine.api.CommandResult.Accepted)
            val inside = document.sample(DocPoint(18f, 12f), SampleMode.ACTIVE_LAYER).color
            val outside = document.sample(DocPoint(22f, 12f), SampleMode.ACTIVE_LAYER).color
            assertTrue("eraser removes pixels inside selection", inside.a < 0.01f)
            assertTrue("eraser leaves outside pixels intact", outside.a > 0.99f && outside.r > 0.99f)
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun nativeTransformPreviewsWithoutMutationAndCommitsAsOneUndoStep() = runBlocking {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec(
                name = "Transform integration",
                size = DocSize(64, 64),
                background = BackgroundContents.TRANSPARENT,
            ),
        ) as NativeDocument
        try {
            val layer = checkNotNull(document.layers.value.primary)
            val stroke = document.beginStroke(
                StrokeSpec(target = layer, color = RgbaColor(1f, 0f, 0f), diameter = 8f),
            )
            stroke.add(floatArrayOf(16f, 16f, 1f, 0f, 0f, 0f, 0f))
            assertTrue(stroke.end() is app.croptop.core.engine.api.CommandResult.Accepted)
            document.awaitViewportPreview()

            val beforeTransform = document.history.value.current
            val previewSession = document.beginTransform(TransformTarget.Layers(setOf(layer)))
            previewSession.update(Matrix3.translate(20f, 8f))
            withTimeout(5_000L) { document.awaitViewportPreview() }
            assertTrue("transform publishes a composite viewport preview", document.hasCompositeViewportPreview)
            val transformPreview = checkNotNull(document.viewportPreview)
            assertTrue("preview shows the translated stroke", (transformPreview.getPixel(36, 24) ushr 24) > 240)
            assertTrue("preview clears the old stroke location", (transformPreview.getPixel(16, 16) ushr 24) < 5)
            assertTrue("preview does not mutate document pixels", document.sample(DocPoint(16f, 16f), SampleMode.ACTIVE_LAYER).color.a > 0.99f)
            assertEquals(0f, document.sample(DocPoint(36f, 24f), SampleMode.ACTIVE_LAYER).color.a, 0f)
            previewSession.cancel()
            document.awaitViewportPreview()
            assertTrue("cancel leaves the source pixel", document.sample(DocPoint(16f, 16f), SampleMode.ACTIVE_LAYER).color.a > 0.99f)

            val session = document.beginTransform(TransformTarget.Layers(setOf(layer)))
            session.update(Matrix3.translate(20f, 8f))
            withTimeout(5_000L) { document.awaitViewportPreview() }
            assertTrue(session.commit() is app.croptop.core.engine.api.CommandResult.Accepted)
            assertEquals("commit is one history entry", beforeTransform + 1, document.history.value.current)
            assertEquals(0f, document.sample(DocPoint(16f, 16f), SampleMode.ACTIVE_LAYER).color.a, 0f)
            assertTrue(document.sample(DocPoint(36f, 24f), SampleMode.ACTIVE_LAYER).color.a > 0.99f)

            assertTrue(document.undo() is app.croptop.core.engine.api.CommandResult.Accepted)
            assertTrue(document.sample(DocPoint(16f, 16f), SampleMode.ACTIVE_LAYER).color.a > 0.99f)
            assertEquals(0f, document.sample(DocPoint(36f, 24f), SampleMode.ACTIVE_LAYER).color.a, 0f)
            assertTrue(document.redo() is app.croptop.core.engine.api.CommandResult.Accepted)
            assertTrue(document.sample(DocPoint(36f, 24f), SampleMode.ACTIVE_LAYER).color.a > 0.99f)
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun nativeBrushAppliesStrokeOpacityOnceUnlessBuildUpIsEnabled() {
        val engine = NativeEngine()
        fun paint(buildUp: Boolean): Float {
            val document = engine.newDocument(
                NewDocumentSpec(
                    name = "Brush opacity",
                    size = DocSize(32, 32),
                    background = BackgroundContents.TRANSPARENT,
                ),
            )
            try {
                val layer = checkNotNull(document.layers.value.primary)
                val stroke = document.beginStroke(
                    StrokeSpec(
                        target = layer,
                        color = RgbaColor(1f, 0f, 0f, 1f),
                        diameter = 8f,
                        hardness = 1f,
                        brush = BrushSettings(
                            size = 8f,
                            hardness = 1f,
                            opacity = 0.25f,
                            flow = 0.5f,
                            count = 2,
                            buildUp = buildUp,
                        ),
                    ),
                )
                stroke.add(floatArrayOf(16.5f, 16.5f, 1f, 0f, 0f, 0f, 0f))
                assertTrue(stroke.end() is app.croptop.core.engine.api.CommandResult.Accepted)
                return document.sample(DocPoint(16f, 16f), SampleMode.ACTIVE_LAYER).color.a
            } finally {
                document.close()
            }
        }

        try {
            // Two half-flow dabs combine to .75 coverage, then brush opacity caps it at .25.
            assertEquals(0.1875f, paint(buildUp = false), 2f / 255f)
            // Build-up mode deliberately retains per-dab source-over: .125 + .125 × .875.
            assertEquals(0.234375f, paint(buildUp = true), 2f / 255f)
        } finally {
            engine.close()
        }
    }

    @Test
    fun nativeBrushSmoothingModesChangeTheCommittedStroke() {
        val engine = NativeEngine()
        fun render(smoothing: Float, mode: app.croptop.core.model.SmoothingMode): List<Float> {
            val document = engine.newDocument(
                NewDocumentSpec("Brush smoothing", DocSize(40, 40), background = BackgroundContents.TRANSPARENT),
            )
            try {
                val layer = checkNotNull(document.layers.value.primary)
                val stroke = document.beginStroke(
                    StrokeSpec(
                        target = layer,
                        color = RgbaColor.Black,
                        diameter = 3f,
                        brush = BrushSettings(
                            size = 3f,
                            hardness = 1f,
                            spacing = 0.3f,
                            smoothing = smoothing,
                            smoothingMode = mode,
                        ),
                    ),
                )
                stroke.add(
                    floatArrayOf(
                        4f, 5f, 1f, 0f, 0f, 0f, 0f,
                        10f, 18f, 1f, 0f, 0f, 0f, 0.1f,
                        16f, 5f, 1f, 0f, 0f, 0f, 0.2f,
                        22f, 18f, 1f, 0f, 0f, 0f, 0.3f,
                        28f, 5f, 1f, 0f, 0f, 0f, 0.4f,
                        34f, 18f, 1f, 0f, 0f, 0f, 0.5f,
                    ),
                )
                val result = stroke.end()
                assertTrue("stroke rejected: $result", result is app.croptop.core.engine.api.CommandResult.Accepted)
                return (0 until 20).flatMap { y ->
                    (0 until 20).map { x -> document.sample(DocPoint(x * 2f + 0.5f, y * 2f + 0.5f), SampleMode.ACTIVE_LAYER).color.a }
                }
            } finally {
                document.close()
            }
        }
        try {
            val raw = render(0f, app.croptop.core.model.SmoothingMode.AVERAGE)
            val averaged = render(0.8f, app.croptop.core.model.SmoothingMode.AVERAGE)
            val roped = render(0.8f, app.croptop.core.model.SmoothingMode.ROPE)
            assertTrue("average smoothing must affect the painted pixels", raw.zip(averaged).count { (a, b) -> kotlin.math.abs(a - b) > 0.02f } > 5)
            assertTrue("rope smoothing must affect the painted pixels", raw.zip(roped).count { (a, b) -> kotlin.math.abs(a - b) > 0.02f } > 5)
        } finally {
            engine.close()
        }
    }

    @Test
    fun nativeBrushRejectsAStrokeThatExceedsTheDabQueueBudgetWithoutCommittingPixels() {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec(
                name = "Brush queue bound",
                size = DocSize(3_008, 16),
                background = BackgroundContents.TRANSPARENT,
            ),
        ) as NativeDocument
        try {
            val layer = checkNotNull(document.layers.value.primary)
            val baseline = document.history.value.current
            val stroke = document.beginStroke(
                StrokeSpec(
                    target = layer,
                    color = RgbaColor(1f, 0f, 0f, 1f),
                    diameter = 2f,
                    brush = BrushSettings(size = 2f, spacing = 0.25f, hardness = 1f),
                ),
            )
            // A single long segment generates more than 4,096 dabs in one native sample chunk.
            stroke.add(floatArrayOf(1f, 8f, 1f, 0f, 0f, 0f, 0f, 3_000f, 8f, 1f, 0f, 0f, 0f, 1f))

            val result = stroke.end()
            assertTrue("over-capacity stroke should be rejected: $result", result is app.croptop.core.engine.api.CommandResult.Rejected)
            assertTrue(
                "rejection should report the native bounded queue",
                (result as app.croptop.core.engine.api.CommandResult.Rejected).message.contains("bounded capacity"),
            )
            assertEquals("rejected stroke must not create a history entry", baseline, document.history.value.current)
            assertEquals(0f, document.sample(DocPoint(1_000f, 8f), SampleMode.ACTIVE_LAYER).color.a, 0f)
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun nativeTileCompositePreviewStacksVisibleLayersInBottomToTopOrder() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = NativeEngine(context)
        val bottom = engine.createTileStore(2, 2, 0)
        val top = engine.createTileStore(2, 2, 0)
        fun encoded(color: Int): ByteArray = ByteArrayOutputStream().use { output ->
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply {
                eraseColor(color)
                check(compress(Bitmap.CompressFormat.PNG, 100, output))
                recycle()
            }
            output.toByteArray()
        }
        try {
            assertTrue(bottom != 0L && top != 0L)
            val blue = encoded(android.graphics.Color.BLUE)
            assertEquals(null, engine.decodeToTileStore(blue, blue.size, bottom, 1))
            val red = encoded(android.graphics.Color.RED)
            assertEquals(null, engine.decodeToTileStore(red, red.size, top, 1))

            val colorBlend = checkNotNull(
                engine.renderCompositePreview(
                    2,
                    2,
                    listOf(
                        NativeRenderLayer(bottom, 1, 0, 255, 255, true),
                        NativeRenderLayer(top, 2, 25, 255, 255, true),
                    ),
                ),
            )
            assertTrue(
                "Color mode should retain the red source hue",
                android.graphics.Color.red(colorBlend.getPixel(0, 0)) > android.graphics.Color.blue(colorBlend.getPixel(0, 0)),
            )
            colorBlend.recycle()

            val luminosityBlend = checkNotNull(
                engine.renderCompositePreview(
                    2,
                    2,
                    listOf(
                        NativeRenderLayer(bottom, 1, 0, 255, 255, true),
                        NativeRenderLayer(top, 2, 26, 255, 255, true),
                    ),
                ),
            )
            assertTrue(
                "Luminosity mode should retain the blue backdrop hue",
                android.graphics.Color.blue(luminosityBlend.getPixel(0, 0)) >
                    android.graphics.Color.red(luminosityBlend.getPixel(0, 0)),
            )
            luminosityBlend.recycle()

            val stacked = checkNotNull(
                engine.renderCompositePreview(
                    2,
                    2,
                    listOf(
                        NativeRenderLayer(bottom, 1, blendMode = 0, opacity = 255, fillOpacity = 255, visible = true),
                        NativeRenderLayer(top, 2, blendMode = 0, opacity = 255, fillOpacity = 255, visible = true),
                    ),
                ),
            )
            assertEquals(android.graphics.Color.RED, stacked.getPixel(0, 0))
            stacked.recycle()

            val hiddenTop = checkNotNull(
                engine.renderCompositePreview(
                    2,
                    2,
                    listOf(
                        NativeRenderLayer(bottom, 1, blendMode = 0, opacity = 255, fillOpacity = 255, visible = true),
                        NativeRenderLayer(top, 2, blendMode = 0, opacity = 255, fillOpacity = 255, visible = false),
                    ),
                ),
            )
            assertEquals(android.graphics.Color.BLUE, hiddenTop.getPixel(0, 0))
            hiddenTop.recycle()
        } finally {
            engine.destroyTileStore(bottom)
            engine.destroyTileStore(top)
            engine.close()
        }
    }

    @Test
    fun newDocumentSavesCroptopManifestAndPixelLayerArchive() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = NativeEngine(context)
        val source = java.io.File(context.filesDir, "native-save-roundtrip.png")
        val target = java.io.File(context.filesDir, "native-save-roundtrip.croptop")
        Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.MAGENTA)
            source.outputStream().use { output -> check(compress(Bitmap.CompressFormat.PNG, 100, output)) }
            recycle()
        }
        val document = engine.open(DocumentSource.AppFile(source.absolutePath))
        try {
            document.save(SaveTarget.AppFile(target.absolutePath))
            assertTrue(target.isFile && target.length() > 0L)
            val entries = ZipInputStream(target.inputStream()).use { zip ->
                buildMap {
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        put(entry.name, zip.readBytes())
                        zip.closeEntry()
                    }
                }
            }
            assertEquals("application/vnd.croptop", entries["mimetype"]?.decodeToString())
            val manifest = checkNotNull(entries["manifest.json"]).decodeToString()
            assertTrue(manifest.contains("\"name\":\"native-save-roundtrip.png\""))
            assertTrue(manifest.contains("\"width\":32"))
            assertTrue(entries.keys.any { it.matches(Regex("tiles/[A-Za-z0-9_-]+/info")) })
            assertTrue(entries.keys.any { it.matches(Regex("tiles/[A-Za-z0-9_-]+/0/0_0\\.zst")) })
            assertFalse(document.info.value.dirty)
        } finally {
            document.close()
            engine.close()
            source.delete()
            target.delete()
        }
    }

    @Test
    fun croptopSaveOpenPreservesFlattenedPixelHash() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = NativeEngine(context)
        val project = java.io.File(context.filesDir, "native-pixel-hash-roundtrip.croptop")
        val before = java.io.File(context.cacheDir, "native-pixel-hash-before.png")
        val after = java.io.File(context.cacheDir, "native-pixel-hash-after.png")
        project.delete()
        before.delete()
        after.delete()
        val original = engine.newDocument(
            NewDocumentSpec(
                name = "Pixel hash archive round trip",
                size = DocSize(48, 32),
                background = BackgroundContents.TRANSPARENT,
            ),
        ) as NativeDocument
        var reopened: NativeDocument? = null
        try {
            val layer = checkNotNull(original.layers.value.primary)
            val stroke = original.beginStroke(
                StrokeSpec(
                    target = layer,
                    color = RgbaColor(0.17f, 0.63f, 0.91f, 0.82f),
                    diameter = 7f,
                    brush = BrushSettings(size = 7f, spacing = 0.2f, hardness = 0.8f),
                ),
            )
            stroke.add(floatArrayOf(8f, 9f, 1f, 0f, 0f, 0f, 0f, 23f, 17f, 0.7f, 0f, 0f, 0f, 0.1f, 39f, 24f, 1f, 0f, 0f, 0f, 0.2f))
            assertTrue("pixel edit should commit", stroke.end() is app.croptop.core.engine.api.CommandResult.Accepted)
            original.export(ExportTarget.AppFile(before.absolutePath), ExportOptions(format = ExportFormat.PNG))
            original.save(SaveTarget.AppFile(project.absolutePath))

            reopened = engine.open(DocumentSource.AppFile(project.absolutePath)) as NativeDocument
            reopened.export(ExportTarget.AppFile(after.absolutePath), ExportOptions(format = ExportFormat.PNG))

            val sha256 = java.security.MessageDigest.getInstance("SHA-256")
            val beforeHash = sha256.digest(before.readBytes())
            val afterHash = java.security.MessageDigest.getInstance("SHA-256").digest(after.readBytes())
            assertArrayEquals("save/open must preserve the rendered pixels", beforeHash, afterHash)
        } finally {
            reopened?.close()
            original.close()
            engine.close()
            project.delete()
            before.delete()
            after.delete()
        }
    }

    @Test
    fun croptopOpenEditSaveCarriesUnknownManifestFields() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = NativeEngine(context)
        val original = java.io.File(context.cacheDir, "native-unknown-fields-original.croptop")
        val augmented = java.io.File(context.cacheDir, "native-unknown-fields-augmented.croptop")
        val rewritten = java.io.File(context.cacheDir, "native-unknown-fields-rewritten.croptop")
        val source = engine.newDocument(
            NewDocumentSpec("Unknown fields", DocSize(24, 16), background = BackgroundContents.TRANSPARENT),
        )
        var reopened: NativeDocument? = null
        try {
            source.save(SaveTarget.AppFile(original.absolutePath))
            val entries = ZipInputStream(original.inputStream()).use { zip ->
                buildMap {
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        put(entry.name, zip.readBytes())
                        zip.closeEntry()
                    }
                }
            }
            val manifest = checkNotNull(entries["manifest.json"]).decodeToString()
                .replaceFirst("\"formatVersion\":1,", "\"formatVersion\":1,\"futureRoot\":{\"keep\":true},")
                .replaceFirst("\"canvas\":{", "\"canvas\":{\"futureCanvas\":{\"v\":7},")
                .replaceFirst("\"layers\":[{", "\"layers\":[{\"futureLayer\":{\"keep\":\"yes\"},")
            check(manifest.contains("futureRoot") && manifest.contains("futureCanvas") && manifest.contains("futureLayer"))
            val augmentedEntries = entries + ("manifest.json" to manifest.encodeToByteArray())
            ZipOutputStream(augmented.outputStream()).use { zip ->
                augmentedEntries.forEach { (name, bytes) ->
                    val crc = CRC32().apply { update(bytes) }.value
                    zip.putNextEntry(
                        ZipEntry(name).apply {
                            method = ZipEntry.STORED
                            size = bytes.size.toLong()
                            compressedSize = size
                            this.crc = crc
                        },
                    )
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }

            reopened = engine.open(DocumentSource.AppFile(augmented.absolutePath)) as NativeDocument
            val layer = reopened.layers.value.flatten().first { it.kind == LayerKind.PIXEL }
            val edit = reopened.execute(EditCommand.SetLayerProps(layer.id, LayerProperties(name = "Edited layer")))
            assertTrue(edit.toString(), edit is app.croptop.core.engine.api.CommandResult.Accepted)
            reopened.save(SaveTarget.AppFile(rewritten.absolutePath))

            val savedManifest = ZipInputStream(rewritten.inputStream()).use { zip ->
                generateSequence { zip.nextEntry }.first { it.name == "manifest.json" }.let { zip.readBytes().decodeToString() }
            }
            assertTrue("unknown root key survives", savedManifest.contains("\"futureRoot\":{\"keep\":true}"))
            assertTrue("unknown canvas key survives", savedManifest.contains("\"futureCanvas\":{\"v\":7}"))
            assertTrue("unknown layer key survives UUID-matched edit", savedManifest.contains("\"futureLayer\":{\"keep\":\"yes\"}"))
            assertTrue("known layer edit is saved", savedManifest.contains("\"name\":\"Edited layer\""))
        } finally {
            reopened?.close()
            source.close()
            engine.close()
            original.delete()
            augmented.delete()
            rewritten.delete()
        }
    }

    @Test
    fun frozenV1CroptopFixtureOpensWithLosslessAlphaLayerPromotion() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fixture = java.io.File(context.cacheDir, "croptop-v1-frozen-fixture.croptop")
        context.assets.open("croptop_v1_fixture.croptop").use { input ->
            fixture.outputStream().use { output -> input.copyTo(output) }
        }
        val engine = NativeEngine(context)
        val document = try {
            engine.open(DocumentSource.AppFile(fixture.absolutePath)) as NativeDocument
        } catch (failure: Throwable) {
            engine.close()
            fixture.delete()
            throw failure
        }
        try {
            assertEquals(ColorDepth.U16, document.info.value.depth)
            val layers = document.layers.value.flatten()
            val pixelsLayer = layers.single { it.name == "Pixels" }
            val maskLayer = layers.single { it.name == "Mask" }
            val pixels = checkNotNull(
                engine.renderCompositePreview(
                    260,
                    12,
                    listOf(
                        NativeRenderLayer(
                            checkNotNull(document.pixelStore(pixelsLayer.id)),
                            pixelsLayer.id.value,
                            blendMode = 0,
                            opacity = 255,
                            fillOpacity = 255,
                            visible = true,
                        ),
                    ),
                    maxDimension = 260,
                ),
            )
            val color = pixels.getPixel(100, 5)
            assertEquals(98, android.graphics.Color.red(color))
            assertEquals(100, android.graphics.Color.green(color))
            assertEquals(128, android.graphics.Color.blue(color))
            assertEquals(255, android.graphics.Color.alpha(color))
            pixels.recycle()

            val mask = checkNotNull(
                engine.renderCompositePreview(
                    260,
                    12,
                    listOf(
                        NativeRenderLayer(
                            checkNotNull(document.pixelStore(maskLayer.id)),
                            maskLayer.id.value,
                            blendMode = 0,
                            opacity = 255,
                            fillOpacity = 255,
                            visible = true,
                        ),
                    ),
                    maxDimension = 260,
                ),
            )
            val coverage = (19 * 700 + 9 * 311) and 0xffff
            val expectedAlpha = (coverage * 255 + 32767) / 65535
            val alphaPixel = mask.getPixel(19, 9)
            assertEquals(255, android.graphics.Color.red(alphaPixel))
            assertEquals(expectedAlpha, android.graphics.Color.alpha(alphaPixel))
            mask.recycle()
        } finally {
            document.close()
            engine.close()
            fixture.delete()
        }
    }

    @Test
    fun croptopContentUriSaveAndOpenRoundTripsPixels() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resolver = context.contentResolver
        val uri = checkNotNull(
            resolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "croptop-saf-${System.nanoTime()}.croptop")
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/x-croptop")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/CropTopTests")
                },
            ),
        )
        val engine = NativeEngine(context)
        val document = engine.newDocument(
            NewDocumentSpec(name = "SAF round trip", size = DocSize(32, 24), background = BackgroundContents.TRANSPARENT),
        )
        try {
            val layer = checkNotNull(document.layers.value.primary)
            val stroke = document.beginStroke(
                StrokeSpec(target = layer, color = RgbaColor(0.85f, 0.2f, 0.1f), diameter = 9f),
            )
            stroke.add(floatArrayOf(12f, 11f, 1f, 0f, 0f, 0f, 0f))
            assertTrue(stroke.end() is app.croptop.core.engine.api.CommandResult.Accepted)
            val expected = document.sample(DocPoint(12f, 11f), SampleMode.ACTIVE_LAYER).color

            document.save(SaveTarget.ContentUri(uri.toString()))
            val bytes = resolver.openInputStream(uri).use { checkNotNull(it).readBytes() }
            assertTrue("SAF project archive is empty", bytes.isNotEmpty())
            assertEquals('P'.code.toByte(), bytes[0])
            assertEquals('K'.code.toByte(), bytes[1])

            val reopened = engine.open(DocumentSource.ContentUri(uri.toString()))
            try {
                val actual = reopened.sample(DocPoint(12f, 11f), SampleMode.ACTIVE_LAYER).color
                assertTrue(kotlin.math.abs(expected.r - actual.r) < 0.001f)
                assertTrue(kotlin.math.abs(expected.g - actual.g) < 0.001f)
                assertTrue(kotlin.math.abs(expected.b - actual.b) < 0.001f)
                assertTrue(kotlin.math.abs(expected.a - actual.a) < 0.001f)
            } finally {
                reopened.close()
            }
        } finally {
            document.close()
            engine.close()
            resolver.delete(uri, null, null)
        }
    }

    @Test
    fun embeddedWorkingProfileSurvivesCroptopReopenAndPngExport() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = NativeEngine(context)
        val project = java.io.File(context.filesDir, "native-profile-roundtrip.croptop")
        val exported = java.io.File(context.filesDir, "native-profile-roundtrip.png")
        val icc = NativeColorManagementBridge.Profile.builtIn(NativeColorManagementBridge.BuiltInSpace.DISPLAY_P3).use { it.iccBytes() }
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(icc)
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
        val profile = app.croptop.core.model.ColorProfileRef.Embedded("Display P3", hash)
        val document = engine.newDocument(
            NewDocumentSpec(
                name = "Embedded profile round trip",
                size = DocSize(16, 12),
                profile = profile,
                background = BackgroundContents.TRANSPARENT,
            ),
        ) as NativeDocument
        try {
            document.importedRasterMetadata = app.croptop.core.io.RasterMetadata(iccProfile = icc)
            document.save(SaveTarget.AppFile(project.absolutePath))

            val reopened = engine.open(DocumentSource.AppFile(project.absolutePath)) as NativeDocument
            try {
                assertEquals(profile, reopened.info.value.profile)
                assertArrayEquals(icc, reopened.importedRasterMetadata?.iccProfile)
                reopened.export(ExportTarget.AppFile(exported.absolutePath), ExportOptions(format = ExportFormat.PNG))
                val metadata = RasterMetadataReader.read(exported.readBytes(), format = ImageFormat.PNG)
                assertArrayEquals("raster export retains the working ICC profile", icc, metadata.iccProfile)
            } finally {
                reopened.close()
            }
        } finally {
            document.close()
            engine.close()
            project.delete()
            exported.delete()
        }
    }

    @Test
    fun builtInWorkingProfileIsEmbeddedInPngAndCroptopSave() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = NativeEngine(context)
        val project = java.io.File(context.filesDir, "builtin-working-profile.croptop")
        val exported = java.io.File(context.filesDir, "builtin-working-profile.png")
        project.delete()
        exported.delete()
        val profile = app.croptop.core.model.ColorProfileRef.BuiltIn(app.croptop.core.model.WorkingSpace.DISPLAY_P3)
        val expectedIcc = NativeColorManagementBridge.Profile.builtIn(NativeColorManagementBridge.BuiltInSpace.DISPLAY_P3).use { it.iccBytes() }
        val document = engine.newDocument(
            NewDocumentSpec(
                name = "Built-in Display P3",
                size = DocSize(12, 10),
                profile = profile,
                background = BackgroundContents.WHITE,
            ),
        ) as NativeDocument
        var reopened: NativeDocument? = null
        try {
            document.export(ExportTarget.AppFile(exported.absolutePath), ExportOptions(format = ExportFormat.PNG))
            assertArrayEquals(
                "new documents export the ICC profile named by their working space",
                expectedIcc,
                RasterMetadataReader.read(exported.readBytes(), format = ImageFormat.PNG).iccProfile,
            )
            document.save(SaveTarget.AppFile(project.absolutePath))
            reopened = engine.open(DocumentSource.AppFile(project.absolutePath)) as NativeDocument
            assertEquals(profile, reopened.info.value.profile)
            assertArrayEquals(expectedIcc, reopened.importedRasterMetadata?.iccProfile)
        } finally {
            reopened?.close()
            document.close()
            engine.close()
            project.delete()
            exported.delete()
        }
    }

    @Test
    fun saveRejectsMissingOrMismatchedEmbeddedWorkingProfile() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = NativeEngine(context)
        val target = java.io.File(context.filesDir, "invalid-embedded-profile.croptop")
        target.delete()
        val icc = NativeColorManagementBridge.Profile.builtIn(NativeColorManagementBridge.BuiltInSpace.DISPLAY_P3).use { it.iccBytes() }
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(icc)
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
        val document = engine.newDocument(
            NewDocumentSpec(
                name = "Invalid embedded profile",
                size = DocSize(8, 8),
                profile = app.croptop.core.model.ColorProfileRef.Embedded("Display P3", hash),
                background = BackgroundContents.TRANSPARENT,
            ),
        ) as NativeDocument
        try {
            val missing = runCatching { document.save(SaveTarget.AppFile(target.absolutePath)) }.exceptionOrNull()
            assertTrue("missing ICC should fail before writing", missing is java.io.IOException)
            document.importedRasterMetadata = app.croptop.core.io.RasterMetadata(iccProfile = byteArrayOf(1, 2, 3))
            val mismatched = runCatching { document.save(SaveTarget.AppFile(target.absolutePath)) }.exceptionOrNull()
            assertTrue("mismatched ICC should fail before writing", mismatched is java.io.IOException)
            assertFalse("failed save must not publish an archive", target.exists())
        } finally {
            document.close()
            engine.close()
            target.delete()
        }
    }

    @Test
    fun embeddedWorkingProfileSurvivesCrashRecoveryAndSubsequentProjectSave() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val originalEngine = NativeEngine(context)
        val recoveryEngine = NativeEngine(context)
        val project = java.io.File(context.filesDir, "recovered-embedded-profile.croptop")
        val exported = java.io.File(context.filesDir, "recovered-embedded-profile.png")
        project.delete()
        exported.delete()
        val icc = NativeColorManagementBridge.Profile.builtIn(NativeColorManagementBridge.BuiltInSpace.DISPLAY_P3).use { it.iccBytes() }
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(icc)
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
        val profile = app.croptop.core.model.ColorProfileRef.Embedded("Display P3", hash)
        val original = originalEngine.newDocument(
            NewDocumentSpec(
                name = "Recover embedded profile",
                size = DocSize(12, 10),
                profile = profile,
                background = BackgroundContents.TRANSPARENT,
            ),
        ) as NativeDocument
        var recovered: NativeDocument? = null
        var reopened: NativeDocument? = null
        try {
            original.importedRasterMetadata = app.croptop.core.io.RasterMetadata(iccProfile = icc)
            val layer = checkNotNull(original.layers.value.primary)
            val edit = original.beginStroke(StrokeSpec(target = layer, color = RgbaColor(0.2f, 0.4f, 0.8f), diameter = 3f))
            edit.add(floatArrayOf(6f, 5f, 1f, 0f, 0f, 0f, 0f))
            assertTrue(edit.end() is app.croptop.core.engine.api.CommandResult.Accepted)
            val journalsBefore = originalEngine.recoveryRoot?.listFiles()?.map { it.name }?.toSet().orEmpty()
            assertTrue("autosave snapshot must become durable", original.autosaveNow(flushTimeoutMs = 30_000L))
            val journal = checkNotNull(
                originalEngine.recoveryRoot?.listFiles()?.singleOrNull { it.isDirectory && it.name !in journalsBefore },
            )

            recovered = recoveryEngine.recover(journal.name) as NativeDocument
            assertEquals(profile, recovered.info.value.profile)
            assertArrayEquals(icc, recovered.importedRasterMetadata?.iccProfile)
            recovered.export(ExportTarget.AppFile(exported.absolutePath), ExportOptions(format = ExportFormat.PNG))
            assertArrayEquals(
                "recovered document export keeps the exact ICC profile",
                icc,
                RasterMetadataReader.read(exported.readBytes(), format = ImageFormat.PNG).iccProfile,
            )
            recovered.save(SaveTarget.AppFile(project.absolutePath))

            reopened = recoveryEngine.open(DocumentSource.AppFile(project.absolutePath)) as NativeDocument
            assertEquals(profile, reopened.info.value.profile)
            assertArrayEquals(icc, reopened.importedRasterMetadata?.iccProfile)
        } finally {
            reopened?.close()
            recovered?.close()
            original.close()
            recoveryEngine.close()
            originalEngine.close()
            project.delete()
            exported.delete()
        }
    }

    @Test
    fun importedRasterSamplingAveragesInLinearLightAndRejectsUnsupportedSizes() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = NativeEngine(context)
        val source = java.io.File(context.filesDir, "native-sample-box.png")
        Bitmap.createBitmap(3, 3, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.BLACK)
            setPixel(0, 0, android.graphics.Color.argb(0, 255, 0, 0))
            setPixel(2, 2, android.graphics.Color.WHITE)
            source.outputStream().use { output -> check(compress(Bitmap.CompressFormat.PNG, 100, output)) }
            recycle()
        }
        val document = engine.open(DocumentSource.AppFile(source.absolutePath))
        try {
            val sourceLayer = checkNotNull(document.layers.value.primary)
            val belowSource = document.sampleBelow(sourceLayer, DocPoint(1f, 1f))
            assertEquals("the target layer is excluded", 0f, belowSource.color.a, 1e-6f)
            assertEquals(SampleMode.MERGED, belowSource.mode)
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                document.sampleBelow(LayerId(Long.MAX_VALUE), DocPoint(1f, 1f))
            }

            val single = document.sample(DocPoint(1f, 1f), SampleMode.ACTIVE_LAYER)
            assertEquals(0f, single.color.r, 1e-6f)
            assertEquals(1f, single.color.a, 1e-6f)

            val averaged = document.sample(DocPoint(1f, 1f), SampleMode.MERGED, size = 3)
            // Transparent red contributes no RGB. White / 8 opaque samples is 0.125 linear, encoded to sRGB.
            assertEquals(0.389f, averaged.color.r, 0.002f)
            assertEquals(8f / 9f, averaged.color.a, 1e-6f)
            assertEquals(SampleMode.MERGED, averaged.mode)
            for (size in listOf(1, 3, 5, 11, 31, 51, 101)) {
                val clipped = document.sample(DocPoint(0f, 0f), SampleMode.MERGED, size)
                // The top-left window clips to the canvas; transparent red contributes no RGB.
                val expectedAlpha = when (size) {
                    1 -> 0f
                    3 -> 0.75f
                    else -> 8f / 9f
                }
                val expectedRgb = if (size >= 5) 0.389f else 0f
                assertEquals("size=$size", expectedRgb, clipped.color.r, 0.002f)
                assertEquals("size=$size", expectedRgb, clipped.color.g, 0.002f)
                assertEquals("size=$size", expectedRgb, clipped.color.b, 0.002f)
                assertEquals("size=$size", expectedAlpha, clipped.color.a, 1e-6f)
            }
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                document.sample(DocPoint(1f, 1f), SampleMode.MERGED, size = 7)
            }
        } finally {
            document.close()
            engine.close()
            source.delete()
        }
    }

    @Test
    fun importedSixteenBitPngSamplingDecodesSrgbBeforeAveraging() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = NativeEngine(context)
        val source = java.io.File(context.filesDir, "native-sample-16bit.png")
        source.writeBytes(rgba16Png(0x8000, 0x8000, 0x8000, 0xffff))
        val document = engine.open(DocumentSource.AppFile(source.absolutePath))
        try {
            val sampled = document.sample(DocPoint(0f, 0f), SampleMode.ACTIVE_LAYER)
            assertEquals(32768f / 65535f, sampled.color.r, 0.002f)
            assertEquals(32768f / 65535f, sampled.color.g, 0.002f)
            assertEquals(32768f / 65535f, sampled.color.b, 0.002f)
            assertEquals(1f, sampled.color.a, 1e-6f)
        } finally {
            document.close()
            engine.close()
            source.delete()
        }
    }

    private fun rgba16Png(red: Int, green: Int, blue: Int, alpha: Int): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))
        fun chunk(type: String, data: ByteArray) {
            val typeBytes = type.encodeToByteArray()
            val length = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(data.size).array()
            output.write(length)
            output.write(typeBytes)
            output.write(data)
            val crc = CRC32().apply {
                update(typeBytes)
                update(data)
            }.value
            output.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(crc.toInt()).array())
        }
        val header = ByteBuffer.allocate(13).order(ByteOrder.BIG_ENDIAN)
            .putInt(1).putInt(1).put(16).put(6).put(0).put(0).put(0).array()
        chunk("IHDR", header)
        val scanline = ByteBuffer.allocate(9).order(ByteOrder.BIG_ENDIAN)
            .put(0).putShort(red.toShort()).putShort(green.toShort()).putShort(blue.toShort())
            .putShort(alpha.toShort()).array()
        val compressed = ByteArrayOutputStream().use { bytes ->
            DeflaterOutputStream(bytes).use { it.write(scanline) }
            bytes.toByteArray()
        }
        chunk("IDAT", compressed)
        chunk("IEND", byteArrayOf())
        return output.toByteArray()
    }

    @Test
    fun nativeLayerThumbnailsRenderPixelsAndRefreshAfterEdits() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = NativeEngine(context)
        val source = java.io.File(context.filesDir, "native-layer-thumbnail.png")
        Bitmap.createBitmap(4, 2, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.MAGENTA)
            source.outputStream().use { output -> check(compress(Bitmap.CompressFormat.PNG, 100, output)) }
            recycle()
        }
        val document = engine.open(DocumentSource.AppFile(source.absolutePath))
        val thumbs = kotlinx.coroutines.channels.Channel<app.croptop.core.engine.api.LayerThumb>(2)
        val layerId = checkNotNull(document.layers.value.primary)
        val collector = launch {
            document.thumbnails(listOf(layerId), px = 16).collect { thumbs.send(it) }
        }
        try {
            val before = kotlinx.coroutines.withTimeout(5_000) { thumbs.receive() }
            assertEquals(layerId, before.layerId)
            assertEquals(4, before.width)
            assertEquals(2, before.height)
            assertEquals(255, before.rgba[0].toInt() and 0xff)
            assertEquals(0, before.rgba[1].toInt() and 0xff)
            assertEquals(255, before.rgba[2].toInt() and 0xff)
            assertEquals(255, before.rgba[3].toInt() and 0xff)

            assertTrue(
                document.execute(
                    EditCommand.SetLayerProps(layerId, LayerProperties(visible = false, opacity = 0.15f)),
                ) is app.croptop.core.engine.api.CommandResult.Accepted,
            )
            val hidden = kotlinx.coroutines.withTimeout(5_000) { thumbs.receive() }
            assertArrayEquals("A layer thumbnail shows its pixels when the layer is hidden", before.rgba, hidden.rgba)

            val editStartedAt = System.nanoTime()
            val moved = document.translateLayers(setOf(layerId), 1f, 0f)
            assertTrue(moved is app.croptop.core.engine.api.CommandResult.Accepted)
            // A preceding history/tree update may already have queued an equivalent thumbnail.
            // Wait for the translated pixels, while keeping the latency bound on the actual update.
            val after = kotlinx.coroutines.withTimeout(200) {
                while (true) {
                    val thumbnail = thumbs.receive()
                    if ((thumbnail.rgba[3].toInt() and 0xff) == 0) return@withTimeout thumbnail
                }
                error("Unreachable")
            }
            val thumbnailLatencyMs = (System.nanoTime() - editStartedAt) / 1_000_000
            assertTrue("Thumbnail update took ${thumbnailLatencyMs}ms", thumbnailLatencyMs <= 200)
            assertEquals(layerId, after.layerId)
            assertEquals(0, after.rgba[3].toInt() and 0xff)
            val translatedPixel = 4 * 1
            assertEquals(255, after.rgba[translatedPixel].toInt() and 0xff)
            assertEquals(255, after.rgba[translatedPixel + 3].toInt() and 0xff)
        } finally {
            collector.cancel()
            document.close()
            engine.close()
            source.delete()
        }
    }

    @Test
    fun viewportPreviewMipLevelBoundsLargeImageDimensions() {
        assertEquals(2, viewportPreviewMipLevel(8_000, 4_000))
        assertEquals(0, viewportPreviewMipLevel(2_048, 1_024))
        assertEquals(1, viewportPreviewMipLevel(2_049, 1_025))
        assertEquals(4, viewportPreviewMipLevel(8_000, 4_000, 512))
        assertEquals(0, viewportPreviewMipLevel(400, 300, 512))
    }

    @Test
    fun viewportSamplingUsesNearestOnlyAboveOneHundredPercentAndGridNeedsFullResolutionPreview() {
        assertFalse(viewportUsesNearestNeighbor(1f))
        assertTrue(viewportUsesNearestNeighbor(1.01f))
        assertFalse(viewportShowsPixelGrid(7.99f, 640, 420, 640, 420))
        assertTrue(viewportShowsPixelGrid(8f, 640, 420, 640, 420))
        assertFalse(viewportShowsPixelGrid(8f, 8_000, 4_000, 2_000, 1_000))
    }

    @Test
    fun viewportCompositePreviewSupportsCoarseAndRefinedMipDimensions() {
        val engine = NativeEngine()
        val store = engine.createTileStore(1_024, 512, 0)
        try {
            assertTrue(store != 0L)
            val layer = NativeRenderLayer(store, 1, blendMode = 0, opacity = 255, fillOpacity = 255, visible = true)
            val coarse = checkNotNull(engine.renderCompositePreview(1_024, 512, listOf(layer), 512))
            assertEquals(512, coarse.width)
            assertEquals(256, coarse.height)
            coarse.recycle()

            val refined = checkNotNull(engine.renderCompositePreview(1_024, 512, listOf(layer), 2_048))
            assertEquals(1_024, refined.width)
            assertEquals(512, refined.height)
            refined.recycle()
        } finally {
            if (store != 0L) engine.destroyTileStore(store)
            engine.close()
        }
    }

    @Test
    fun vulkanBlendComputeDispatchMatchesCpuReferenceWhenComputeIsAvailable() {
        val engine = NativeEngine()
        try {
            val result = engine.runBlendComputeSmokeTest()
            assumeTrue("Emulator has no Vulkan compute device", result != 0)
            assertEquals("Vulkan blend compute output differs from the CPU reference", 2, result)
        } finally {
            engine.close()
        }
    }

    @Test
    fun nativeTileStoreClonePreservesPixelSnapshotAfterCopyOnWrite() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = NativeEngine(context)
        val original = engine.createTileStore(2, 2, 0)
        var snapshot = 0L
        fun png(color: Int): ByteArray = ByteArrayOutputStream().use { output ->
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply {
                eraseColor(color)
                check(compress(Bitmap.CompressFormat.PNG, 100, output))
                recycle()
            }
            output.toByteArray()
        }
        fun decodedPixel(store: Long): Int {
            val output = ByteArrayOutputStream()
            assertTrue(engine.encodePngToStream(store, output, 6, null, null) > 0)
            val bitmap = checkNotNull(BitmapFactory.decodeByteArray(output.toByteArray(), 0, output.size()))
            val color = bitmap.getPixel(0, 0)
            bitmap.recycle()
            return color
        }

        try {
            assertTrue(original != 0L)
            val red = png(android.graphics.Color.RED)
            assertEquals(null, engine.decodeToTileStore(red, red.size, original, 1))
            snapshot = engine.cloneTileStore(original)
            assertTrue(snapshot != 0L)
            val green = png(android.graphics.Color.GREEN)
            assertEquals(null, engine.decodeToTileStore(green, green.size, snapshot, 1))

            assertEquals(android.graphics.Color.RED, decodedPixel(original))
            assertEquals(android.graphics.Color.GREEN, decodedPixel(snapshot))
        } finally {
            if (snapshot != 0L) engine.destroyTileStore(snapshot)
            if (original != 0L) engine.destroyTileStore(original)
            engine.close()
        }
    }

    @Test
    fun namedSnapshotCanBeRenamedDeletedAndRestoredPixelExactly() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = context.cacheDir.resolve("croptop-history-snapshot.png")
        val target = context.cacheDir.resolve("croptop-history-snapshot-export.png")
        fun png(color: Int): ByteArray = ByteArrayOutputStream().use { output ->
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply {
                eraseColor(color)
                check(compress(Bitmap.CompressFormat.PNG, 100, output))
                recycle()
            }
            output.toByteArray()
        }
        source.writeBytes(png(android.graphics.Color.RED))
        val engine = NativeEngine(context)
        try {
            val document = runBlocking { engine.open(DocumentSource.AppFile(source.absolutePath)) } as NativeDocument
            try {
                assertTrue(document.createSnapshot("Before edit") is app.croptop.core.engine.api.CommandResult.Accepted)
                assertEquals("Before edit", document.history.value.snapshots.single().name)
                val green = png(android.graphics.Color.GREEN)
                document.loadRaster(green, green.size, 1)
                assertTrue(
                    document.execute(EditCommand.AddLayer(LayerId(987_654_322L), "Discarded branch", LayerKind.PIXEL))
                        is app.croptop.core.engine.api.CommandResult.Accepted,
                )
                assertTrue(document.undo() is app.croptop.core.engine.api.CommandResult.Accepted)
                assertTrue(
                    document.execute(EditCommand.AddLayer(LayerId(987_654_323L), "New branch", LayerKind.PIXEL))
                        is app.croptop.core.engine.api.CommandResult.Accepted,
                )
                assertTrue(document.renameSnapshot(0, "Original") is app.croptop.core.engine.api.CommandResult.Accepted)
                assertEquals("Original", document.history.value.snapshots.single().name)
                assertTrue(document.jumpToSnapshot(0) is app.croptop.core.engine.api.CommandResult.Accepted)
                assertEquals(1, document.layers.value.layers.size)
                val result = runBlocking {
                    document.export(ExportTarget.AppFile(target.absolutePath), ExportOptions(format = ExportFormat.PNG))
                }
                assertTrue(result.bytesWritten > 0)
                val restored = checkNotNull(BitmapFactory.decodeFile(target.absolutePath))
                try {
                    assertEquals(android.graphics.Color.RED, restored.getPixel(0, 0))
                } finally {
                    restored.recycle()
                }
                assertTrue(document.deleteSnapshot(0) is app.croptop.core.engine.api.CommandResult.Accepted)
                assertTrue(document.history.value.snapshots.isEmpty())
            } finally {
                document.close()
            }
        } finally {
            engine.close()
            source.delete()
            target.delete()
        }
    }

    @Test
    fun emptyHundredMegapixelDocumentAllocatesNoPixelTiles() {
        System.gc()
        val beforeBytes = usedHeapBytes()
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec(name = "Sparse", size = DocSize(10_000, 10_000)),
        ) as NativeDocument

        try {
            assertEquals(0L, document.residentPixelBytes)
            System.gc()
            val allocatedBytes = usedHeapBytes() - beforeBytes
            org.junit.Assert.assertTrue(
                "100 MP empty document allocated $allocatedBytes bytes; expected less than 5 MiB",
                allocatedBytes < 5L * 1024 * 1024,
            )
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun historyRetainsFiveHundredStepsForTwentyFourMegapixelDocument() {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec(name = "History stress", size = DocSize(6_000, 4_000)),
        ) as NativeDocument
        val layerId = LayerId(987_654_399L)
        try {
            assertTrue(
                document.execute(EditCommand.AddLayer(layerId, "Stress", LayerKind.PIXEL))
                    is app.croptop.core.engine.api.CommandResult.Accepted,
            )
            repeat(500) { step ->
                val opacity = (step + 1) / 501f
                assertTrue(
                    "History command $step was rejected",
                    document.execute(EditCommand.SetLayerProps(layerId, LayerProperties(opacity = opacity)))
                        is app.croptop.core.engine.api.CommandResult.Accepted,
                )
            }
            assertEquals(502, document.history.value.entries.size)
            assertEquals(0L, document.residentPixelBytes)
            repeat(500) {
                assertTrue(document.undo() is app.croptop.core.engine.api.CommandResult.Accepted)
            }
            assertEquals(1, document.history.value.current)
            repeat(500) {
                assertTrue(document.redo() is app.croptop.core.engine.api.CommandResult.Accepted)
            }
            assertEquals(501, document.history.value.current)
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun historySpillsAndRestoresFiveHundredPixelEditsOnTwentyFourMegapixels() {
        val engine = NativeEngine()
        engine.setMemoryBudget(4L * 1024 * 1024)
        val document = engine.newDocument(
            NewDocumentSpec(
                name = "Pixel history spill stress",
                size = DocSize(6_000, 4_000),
                background = BackgroundContents.TRANSPARENT,
            ),
        ) as NativeDocument
        val points = (0 until 500).map { step ->
            val column = step % 25
            val row = step / 25
            DocPoint(8.5f + column * 9f, 8.5f + row * 12f)
        }
        val pixelSamples = points.flatMap { point ->
            (-1..1).flatMap { dy -> (-1..1).map { dx -> DocPoint(point.x + dx, point.y + dy) } }
        }
        try {
            val layer = checkNotNull(document.layers.value.primary)
            points.forEachIndexed { step, point ->
                val color = RgbaColor(
                    ((step % 251) + 1) / 252f,
                    (((step * 37) % 251) + 1) / 252f,
                    (((step * 91) % 251) + 1) / 252f,
                )
                val stroke =
                    document.beginStroke(
                        StrokeSpec(target = layer, color = color, diameter = 2f, hardness = 1f),
                    )
                stroke.add(floatArrayOf(point.x, point.y, 1f, 0f, 0f, 0f, step / 60f))
                assertTrue(
                    "Pixel edit $step was rejected",
                    stroke.end() is app.croptop.core.engine.api.CommandResult.Accepted,
                )
            }

            val painted = pixelSamples.map { document.sample(it, SampleMode.ACTIVE_LAYER).color }
            val paintedHash = pixelHash(painted)
            assertEquals(501, document.history.value.entries.size)
            repeat(500) {
                assertTrue(document.undo() is app.croptop.core.engine.api.CommandResult.Accepted)
            }
            pixelSamples.forEach { point ->
                assertEquals(0f, document.sample(point, SampleMode.ACTIVE_LAYER).color.a, 0f)
            }

            repeat(500) {
                assertTrue(document.redo() is app.croptop.core.engine.api.CommandResult.Accepted)
            }
            val redone = pixelSamples.map { document.sample(it, SampleMode.ACTIVE_LAYER).color }
            assertEquals(painted, redone)
            assertEquals(paintedHash, pixelHash(redone))
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun activeLayerSelectionIsViewStateAndMoveHitTestUsesTopmostEditablePixelLayer() {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec(name = "Move", size = DocSize(64, 48), background = app.croptop.core.model.BackgroundContents.TRANSPARENT),
        )
        val lower = document.layers.value.layers.single().id
        val upper = LayerId(987_654_398L)
        try {
            assertTrue(document.execute(EditCommand.AddLayer(upper, "Upper", LayerKind.PIXEL)) is app.croptop.core.engine.api.CommandResult.Accepted)
            val historyBeforeSelection = document.history.value
            val dirtyBeforeSelection = document.info.value.dirty

            assertTrue(document.setActiveLayers(setOf(lower), lower) is app.croptop.core.engine.api.CommandResult.Accepted)
            assertEquals(setOf(lower), document.layers.value.active)
            assertEquals(lower, document.layers.value.primary)
            assertEquals(historyBeforeSelection, document.history.value)
            assertEquals(dirtyBeforeSelection, document.info.value.dirty)
            assertTrue(document.setActiveLayers(setOf(upper), lower) is app.croptop.core.engine.api.CommandResult.Rejected)
            assertTrue(document.setActiveLayers(setOf(LayerId(Long.MAX_VALUE)), null) is app.croptop.core.engine.api.CommandResult.Rejected)

            assertEquals(upper, document.layerAt(DocPoint(10f, 10f)))
            assertEquals(null, document.layerAt(DocPoint(-1f, 10f)))
            assertEquals(null, document.layerAt(DocPoint(Float.NaN, 10f)))
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun movingGroupSkipsNonRasterAdjustmentAndFillChildren() {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec(name = "Mixed group move", size = DocSize(64, 48), background = app.croptop.core.model.BackgroundContents.TRANSPARENT),
        )
        val group = LayerId(987_654_390L)
        val adjustment = LayerId(987_654_391L)
        val fill = LayerId(987_654_392L)
        val pixels = LayerId(987_654_393L)
        try {
            assertTrue(document.execute(EditCommand.AddLayer(group, "Group", LayerKind.GROUP)) is app.croptop.core.engine.api.CommandResult.Accepted)
            assertTrue(
                document.execute(EditCommand.AddLayer(adjustment, "Curves", LayerKind.ADJUSTMENT, parentId = group)) is
                    app.croptop.core.engine.api.CommandResult.Accepted,
            )
            assertTrue(
                document.execute(EditCommand.AddLayer(fill, "Fill", LayerKind.FILL, parentId = group)) is
                    app.croptop.core.engine.api.CommandResult.Accepted,
            )
            assertTrue(
                document.execute(EditCommand.AddLayer(pixels, "Raster", LayerKind.PIXEL, parentId = group)) is
                    app.croptop.core.engine.api.CommandResult.Accepted,
            )
            val historyBefore = document.history.value.entries.size
            assertTrue(
                "a non-raster child must not reject a group move",
                document.translateLayers(setOf(group), 4f, -3f) is app.croptop.core.engine.api.CommandResult.Accepted,
            )
            assertEquals(historyBefore + 1, document.history.value.entries.size)
            assertEquals(LayerKind.ADJUSTMENT, document.layers.value.find(adjustment)?.kind)
            assertEquals(LayerKind.FILL, document.layers.value.find(fill)?.kind)
            assertEquals(group, document.layers.value.parentOf(pixels)?.id)

            val emptyGroup = LayerId(987_654_394L)
            val emptyAdjustment = LayerId(987_654_395L)
            val emptyFill = LayerId(987_654_396L)
            assertTrue(
                document.execute(EditCommand.AddLayer(emptyGroup, "Effects", LayerKind.GROUP)) is app.croptop.core.engine.api.CommandResult.Accepted,
            )
            assertTrue(
                document.execute(EditCommand.AddLayer(emptyAdjustment, "Levels", LayerKind.ADJUSTMENT, parentId = emptyGroup)) is
                    app.croptop.core.engine.api.CommandResult.Accepted,
            )
            assertTrue(
                document.execute(EditCommand.AddLayer(emptyFill, "Solid Fill", LayerKind.FILL, parentId = emptyGroup)) is
                    app.croptop.core.engine.api.CommandResult.Accepted,
            )
            val beforeNoOp = document.history.value.entries.size
            assertTrue(document.translateLayers(setOf(emptyGroup), 4f, -3f) is app.croptop.core.engine.api.CommandResult.Accepted)
            assertEquals("a group with no raster descendants adds no empty history step", beforeNoOp, document.history.value.entries.size)

            val direct = document.translateLayers(setOf(adjustment), 4f, -3f)
            assertTrue(direct is app.croptop.core.engine.api.CommandResult.Rejected)
            assertTrue((direct as app.croptop.core.engine.api.CommandResult.Rejected).message.contains("adjustment layer and has no pixels"))
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun nativeLayerCommandsMoveAndDeleteNestedGroupsWithUndo() {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec(
                name = "Nested layer commands",
                size = DocSize(64, 48),
                background = app.croptop.core.model.BackgroundContents.TRANSPARENT,
            ),
        )
        val original = document.layers.value.layers.single().id
        val group = LayerId(987_654_410L)
        val child = LayerId(987_654_411L)
        val spare = LayerId(987_654_412L)
        try {
            assertTrue(
                document.execute(EditCommand.AddLayer(group, "Group", LayerKind.GROUP, index = 0)) is
                    app.croptop.core.engine.api.CommandResult.Accepted,
            )
            assertTrue(
                document.execute(EditCommand.AddLayer(child, "Child", LayerKind.PIXEL, parentId = group)) is
                    app.croptop.core.engine.api.CommandResult.Accepted,
            )
            assertTrue(
                document.execute(EditCommand.AddLayer(spare, "Spare", LayerKind.PIXEL, index = 2)) is
                    app.croptop.core.engine.api.CommandResult.Accepted,
            )

            assertTrue(
                document.execute(EditCommand.MoveLayer(original, parentId = group, index = 1)) is
                    app.croptop.core.engine.api.CommandResult.Accepted,
            )
            assertEquals(group, document.layers.value.parentOf(original)?.id)
            val cycle = document.execute(EditCommand.MoveLayer(group, parentId = group, index = 0))
            assertTrue(cycle is app.croptop.core.engine.api.CommandResult.Rejected)
            assertEquals(group, document.layers.value.parentOf(original)?.id)

            assertTrue(
                document.execute(EditCommand.MoveLayer(child, index = 1)) is
                    app.croptop.core.engine.api.CommandResult.Accepted,
            )
            assertEquals(null, document.layers.value.parentOf(child))
            assertTrue(document.undo() is app.croptop.core.engine.api.CommandResult.Accepted)
            assertEquals(group, document.layers.value.parentOf(child)?.id)

            assertTrue(document.setActiveLayers(setOf(child), child) is app.croptop.core.engine.api.CommandResult.Accepted)
            assertTrue(document.fill(FillSource.Solid(RgbaColor(0.2f, 0.4f, 0.9f))) is app.croptop.core.engine.api.CommandResult.Accepted)
            val childPixels = document.sample(DocPoint(24f, 20f), SampleMode.ACTIVE_LAYER).color
            assertTrue("nested child has blue pixels before deletion", childPixels.b > 0.85f && childPixels.a > 0.95f)

            assertTrue(
                document.execute(EditCommand.DeleteLayer(group)) is
                    app.croptop.core.engine.api.CommandResult.Accepted,
            )
            assertEquals(null, document.layers.value.find(group))
            assertEquals(null, document.layers.value.find(original))
            assertEquals(null, document.layers.value.find(child))
            assertTrue(document.undo() is app.croptop.core.engine.api.CommandResult.Accepted)
            assertEquals(group, document.layers.value.parentOf(original)?.id)
            assertEquals(group, document.layers.value.parentOf(child)?.id)
            assertTrue(document.layers.value.find(spare) != null)
            val restoredPixels = document.sample(DocPoint(24f, 20f), SampleMode.ACTIVE_LAYER).color
            assertEquals("group undo restores child pixel blue", childPixels.b, restoredPixels.b, 0.001f)
            assertEquals("group undo restores child pixel alpha", childPixels.a, restoredPixels.a, 0.001f)

            val duplicate = LayerId(987_654_413L)
            assertTrue(document.layerViaCopy(duplicate) is app.croptop.core.engine.api.CommandResult.Accepted)
            assertEquals(group, document.layers.value.parentOf(duplicate)?.id)
            val duplicatePixels = document.sample(DocPoint(24f, 20f), SampleMode.ACTIVE_LAYER).color
            assertEquals("nested duplicate preserves blue pixels", restoredPixels.b, duplicatePixels.b, 0.001f)
            assertTrue(document.undo() is app.croptop.core.engine.api.CommandResult.Accepted)
            assertEquals(null, document.layers.value.find(duplicate))
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun translatingImportedLayerMovesPixelsAndUndoRestoresThem() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = java.io.File(context.cacheDir, "move-source.png")
        val movedFile = java.io.File(context.cacheDir, "move-result.png")
        val undoneFile = java.io.File(context.cacheDir, "move-undone.png")
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.TRANSPARENT)
        bitmap.setPixel(2, 3, android.graphics.Color.RED)
        source.outputStream().use { output -> assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) }
        bitmap.recycle()
        val engine = NativeEngine(context)
        try {
            val document = runBlocking { engine.open(DocumentSource.AppFile(source.absolutePath)) }
            try {
                val layer = document.layers.value.primary ?: error("Imported raster has no primary layer")
                assertTrue(document.translateLayers(setOf(layer), 1.5f, 0f) is app.croptop.core.engine.api.CommandResult.Accepted)
                runBlocking { document.export(ExportTarget.AppFile(movedFile.absolutePath), ExportOptions(format = ExportFormat.PNG)) }
                val moved = checkNotNull(BitmapFactory.decodeFile(movedFile.absolutePath))
                try {
                    assertEquals(android.graphics.Color.TRANSPARENT, moved.getPixel(2, 3))
                    assertEquals(128, android.graphics.Color.alpha(moved.getPixel(3, 3)))
                    assertEquals(128, android.graphics.Color.alpha(moved.getPixel(4, 3)))
                    assertEquals(255, android.graphics.Color.red(moved.getPixel(3, 3)))
                    assertEquals(255, android.graphics.Color.red(moved.getPixel(4, 3)))
                } finally {
                    moved.recycle()
                }
                assertTrue(document.undo() is app.croptop.core.engine.api.CommandResult.Accepted)
                runBlocking { document.export(ExportTarget.AppFile(undoneFile.absolutePath), ExportOptions(format = ExportFormat.PNG)) }
                val undone = checkNotNull(BitmapFactory.decodeFile(undoneFile.absolutePath))
                try {
                    assertEquals(android.graphics.Color.RED, undone.getPixel(2, 3))
                    assertEquals(android.graphics.Color.TRANSPARENT, undone.getPixel(3, 3))
                } finally {
                    undone.recycle()
                }
            } finally {
                document.close()
            }
        } finally {
            engine.close()
            source.delete()
            movedFile.delete()
            undoneFile.delete()
        }
    }

    @Test
    fun emptyTwoHundredMegapixelDocumentStaysBelowOneGigabyte() {
        System.gc()
        val beforeBytes = usedHeapBytes()
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec(name = "Large sparse", size = DocSize(20_000, 10_000)),
        ) as NativeDocument

        try {
            assertEquals(0L, document.residentPixelBytes)
            System.gc()
            val allocatedBytes = usedHeapBytes() - beforeBytes
            org.junit.Assert.assertTrue(
                "200 MP empty document allocated $allocatedBytes bytes; expected less than 1 GiB",
                allocatedBytes < 1024L * 1024 * 1024,
            )
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    @Suppress("DEPRECATION")
    fun applicationMemoryTrimReleasesNativeCaches() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = NativeEngine(context)
        val document = engine.newDocument(
            NewDocumentSpec(name = "Trim", size = DocSize(256, 256)),
        )
        try {
            engine.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun appPrivateJpegPngWebpGifAndBmpOpenIntoNativeTiles() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply {
            setPixel(0, 0, android.graphics.Color.RED)
            setPixel(1, 0, android.graphics.Color.GREEN)
            setPixel(0, 1, android.graphics.Color.BLUE)
            setPixel(1, 1, android.graphics.Color.TRANSPARENT)
        }
        val encodedImages = listOf(
            "png" to
                bitmap.let { image ->
                    java.io.ByteArrayOutputStream().use { out ->
                        check(image.compress(Bitmap.CompressFormat.PNG, 100, out))
                        out.toByteArray()
                    }
                },
            "jpg" to
                bitmap.let { image ->
                    java.io.ByteArrayOutputStream().use { out ->
                        check(image.compress(Bitmap.CompressFormat.JPEG, 95, out))
                        out.toByteArray()
                    }
                },
            "webp" to
                bitmap.let { image ->
                    java.io.ByteArrayOutputStream().use { out ->
                        @Suppress("DEPRECATION")
                        check(image.compress(Bitmap.CompressFormat.WEBP, 95, out))
                        out.toByteArray()
                    }
                },
            "gif" to android.util.Base64.decode("R0lGODdhAgACAIEAAP//AAD/AP8AAAAA/ywAAAAAAgACAAAIBwAFBBgAICAAOw==", android.util.Base64.DEFAULT),
            "bmp" to bmp2x2(),
        )
        bitmap.recycle()

        encodedImages.forEach { (extension, encoded) ->
            val file = context.cacheDir.resolve("croptop-open-smoke.$extension")
            file.writeBytes(encoded)
            val engine = NativeEngine(context)
            try {
                val document = runBlocking { engine.open(DocumentSource.AppFile(file.absolutePath)) } as NativeDocument
                try {
                    assertEquals("croptop-open-smoke.$extension", document.info.value.name)
                    assertEquals(DocSize(2, 2), document.info.value.size)
                    org.junit.Assert.assertTrue("$extension pixels were not written to the native tile store", document.residentPixelBytes > 0)
                } finally {
                    document.close()
                }
            } finally {
                engine.close()
                file.delete()
            }
        }
    }

    @Test
    fun contentUriOpensThroughAndroidContentResolverIntoNativeTiles() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resolver = context.contentResolver
        val displayName = "croptop-saf-open-${System.nanoTime()}.png"
        val uri = checkNotNull(
            resolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CropTopTests")
                },
            ),
        )
        val bitmap = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.CYAN)
        }
        resolver.openOutputStream(uri, "w").use { output ->
            checkNotNull(output)
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        bitmap.recycle()

        val engine = NativeEngine(context)
        try {
            val document = runBlocking { engine.open(DocumentSource.ContentUri(uri.toString())) } as NativeDocument
            try {
                assertEquals(DocSize(3, 2), document.info.value.size)
                org.junit.Assert.assertTrue("ContentUri pixels were not written to native tiles", document.residentPixelBytes > 0)
            } finally {
                document.close()
            }
        } finally {
            engine.close()
            resolver.delete(uri, null, null)
        }
    }

    @Test
    fun jpegAndPngExifOrientationSetsDocumentDimensionsAndMetadata() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bitmap = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888).apply {
            setPixel(0, 0, android.graphics.Color.RED)
            setPixel(1, 0, android.graphics.Color.GREEN)
            setPixel(2, 0, android.graphics.Color.BLUE)
            setPixel(0, 1, android.graphics.Color.YELLOW)
            setPixel(1, 1, android.graphics.Color.CYAN)
            setPixel(2, 1, android.graphics.Color.MAGENTA)
        }
        val png = java.io.ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            withExifOrientation(output.toByteArray(), 6)
        }
        val jpeg = java.io.ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output))
            withJpegExifOrientation(output.toByteArray(), 6)
        }
        bitmap.recycle()
        listOf("png" to png, "jpg" to jpeg).forEach { (extension, encoded) ->
            val file = context.cacheDir.resolve("croptop-orientation.$extension").apply { writeBytes(encoded) }
            val engine = NativeEngine(context)
            try {
                val document = runBlocking { engine.open(DocumentSource.AppFile(file.absolutePath)) } as NativeDocument
                try {
                    assertEquals(DocSize(2, 3), document.info.value.size)
                    assertEquals(6, document.importedRasterMetadata?.exifOrientation)
                    org.junit.Assert.assertNotNull(document.importedRasterMetadata?.exifBytes)
                    org.junit.Assert.assertTrue(
                        "$extension oriented pixels did not reach native tiles",
                        document.residentPixelBytes > 0,
                    )
                } finally {
                    document.close()
                }
            } finally {
                engine.close()
                file.delete()
            }
        }
    }

    @Test
    fun importedImageViewportPreviewTracksSingleLayerMove() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = context.cacheDir.resolve("croptop-viewport-move.png")
        val bitmap = Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.TRANSPARENT)
            for (y in 0 until height) {
                for (x in 1..2) setPixel(x, y, android.graphics.Color.RED)
            }
        }
        source.outputStream().use { output -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) }
        bitmap.recycle()
        val engine = NativeEngine(context)
        try {
            val document = runBlocking { engine.open(DocumentSource.AppFile(source.absolutePath)) } as NativeDocument
            try {
                assertTrue(document.rendersViewport)
                assertEquals(android.graphics.Color.RED, checkNotNull(document.viewportPreview).getPixel(2, 1))
                // The compositor consumes editable tile stores, not import metadata or the decoded preview.
                document.importedRasterMetadata = null
                val layerId = document.layers.value.layers.single().id
                assertTrue(document.translateLayers(setOf(layerId), 4f, 0f) is app.croptop.core.engine.api.CommandResult.Accepted)
                awaitCompositePreview(document)
                assertEquals(android.graphics.Color.TRANSPARENT, checkNotNull(document.viewportPreview).getPixel(2, 1))
                assertEquals(android.graphics.Color.RED, checkNotNull(document.viewportPreview).getPixel(6, 1))
                assertTrue(document.undo() is app.croptop.core.engine.api.CommandResult.Accepted)
                awaitCompositePreview(document)
                assertEquals(android.graphics.Color.RED, checkNotNull(document.viewportPreview).getPixel(2, 1))
                val undoPreview = checkNotNull(document.viewportPreview)
                assertEquals(
                    "undo preview row=${(0 until undoPreview.width).map { undoPreview.getPixel(it, 1) }.joinToString()}",
                    android.graphics.Color.TRANSPARENT,
                    undoPreview.getPixel(6, 1),
                )
                assertTrue(document.redo() is app.croptop.core.engine.api.CommandResult.Accepted)
                awaitCompositePreview(document)
                assertEquals(android.graphics.Color.TRANSPARENT, checkNotNull(document.viewportPreview).getPixel(2, 1))
                assertEquals(android.graphics.Color.RED, checkNotNull(document.viewportPreview).getPixel(6, 1))
            } finally {
                document.close()
            }
        } finally {
            engine.close()
            source.delete()
        }
    }

    @Test
    fun nativeShapeLayerRendersUndoRedoAndPersistsGeometryAcrossSaveOpen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = context.cacheDir.resolve("shape-layer-roundtrip.croptop")
        val engine = NativeEngine(context)
        val document = engine.newDocument(
            NewDocumentSpec(name = "Shapes", size = DocSize(16, 16), background = BackgroundContents.TRANSPARENT),
        ) as NativeDocument
        val path = app.croptop.core.model.VectorPathDraft(
            app.croptop.core.model.VectorPathId(1),
            "Red rectangle",
            listOf(
                app.croptop.core.model.VectorSubpath(
                    listOf(
                        app.croptop.core.model.PathAnchor(app.croptop.core.model.PathAnchorId(1), DocPoint(2f, 2f)),
                        app.croptop.core.model.PathAnchor(app.croptop.core.model.PathAnchorId(2), DocPoint(10f, 2f)),
                        app.croptop.core.model.PathAnchor(app.croptop.core.model.PathAnchorId(3), DocPoint(10f, 10f)),
                        app.croptop.core.model.PathAnchor(app.croptop.core.model.PathAnchorId(4), DocPoint(2f, 10f)),
                    ),
                    closed = true,
                ),
            ),
            style = app.croptop.core.model.VectorPathStyle(
                fillColor = RgbaColor(1f, 0f, 0f, 1f),
                strokeColor = null,
            ),
        )
        try {
            val accepted = document.createShapeLayer(LayerId(200), "Rectangle", listOf(path)) as app.croptop.core.engine.api.CommandResult.Accepted
            assertEquals(
                EngineEvent.Applied(accepted.sequence, "New Shape Layer"),
                runBlocking { document.events.first { it is EngineEvent.Applied && it.sequence == accepted.sequence } },
            )
            awaitCompositePreview(document)
            assertEquals(
                EngineEvent.Rendered(accepted.sequence),
                runBlocking { withTimeout(5_000) { document.events.first { it is EngineEvent.Rendered && it.sequence >= accepted.sequence } } },
            )
            assertEquals(android.graphics.Color.RED, checkNotNull(document.viewportPreview).getPixel(5, 5))
            assertTrue(document.undo() is app.croptop.core.engine.api.CommandResult.Accepted)
            awaitCompositePreview(document)
            assertEquals(android.graphics.Color.TRANSPARENT, checkNotNull(document.viewportPreview).getPixel(5, 5))
            assertTrue(document.redo() is app.croptop.core.engine.api.CommandResult.Accepted)
            awaitCompositePreview(document)
            assertEquals(android.graphics.Color.RED, checkNotNull(document.viewportPreview).getPixel(5, 5))

            runBlocking { document.save(SaveTarget.AppFile(file.absolutePath)) }
            val reopened = runBlocking { engine.open(DocumentSource.AppFile(file.absolutePath)) } as NativeDocument
            try {
                assertEquals(listOf(path), reopened.layers.value.flatten().first { it.kind == LayerKind.SHAPE }.vectorPaths)
                awaitCompositePreview(reopened)
                assertEquals(android.graphics.Color.RED, checkNotNull(reopened.viewportPreview).getPixel(5, 5))
            } finally {
                reopened.close()
            }
        } finally {
            document.close()
            engine.close()
            file.delete()
        }
    }

    @Test
    fun blankDocumentBuildsCompositePreviewWhenViewportAttaches() {
        val engine = NativeEngine(ApplicationProvider.getApplicationContext())
        val document = runBlocking {
            engine.newDocument(
                app.croptop.core.model.NewDocumentSpec(
                    name = "Blank viewport",
                    size = DocSize(8, 4),
                    background = app.croptop.core.model.BackgroundContents.TRANSPARENT,
                ),
            ) as NativeDocument
        }
        val texture = android.graphics.SurfaceTexture(0).apply { setDefaultBufferSize(32, 16) }
        val surface = android.view.Surface(texture)
        var viewport: app.croptop.core.engine.api.Viewport? = null
        try {
            viewport = document.attachViewport(surface, app.croptop.core.model.ViewportState(viewWidth = 32, viewHeight = 16))
            awaitCompositePreview(document)
            assertEquals(android.graphics.Color.TRANSPARENT, checkNotNull(document.viewportPreview).getPixel(2, 1))
            viewport.setOverlays(
                app.croptop.core.engine.api.OverlayState(
                    guides = listOf(
                        app.croptop.core.model.Guide(app.croptop.core.model.GuideAxis.HORIZONTAL, 2f),
                        app.croptop.core.model.Guide(app.croptop.core.model.GuideAxis.VERTICAL, 4f),
                    ),
                ),
            )
            val layer = checkNotNull(document.layers.value.primary)
            val stroke = document.beginStroke(
                StrokeSpec(
                    target = layer,
                    color = RgbaColor(1f, 0f, 0f, 1f),
                    diameter = 3f,
                    hardness = 1f,
                ),
            )
            stroke.add(floatArrayOf(2f, 2f, 1f, 0f, 0f, 0f, 0f))
            assertTrue(stroke.end() is app.croptop.core.engine.api.CommandResult.Accepted)
            runBlocking { kotlinx.coroutines.withTimeout(5_000L) { document.awaitViewportPreview() } }
            assertTrue("committed preview is ready after the attached Surface posts its frame", document.hasCompositeViewportPreview)
        } finally {
            viewport?.detach()
            document.close()
            engine.close()
            surface.release()
            texture.release()
        }
    }

    @Test
    fun virtualBackgroundRendersSamplesAndIsRestoredByEraserUndoAndRedo() {
        val engine = NativeEngine(ApplicationProvider.getApplicationContext())
        fun open(background: BackgroundContents, name: String): Pair<NativeDocument, Pair<android.graphics.SurfaceTexture, android.view.Surface>> {
            val document = engine.newDocument(NewDocumentSpec(name = name, size = DocSize(16, 16), background = background)) as NativeDocument
            val texture = android.graphics.SurfaceTexture(0).apply { setDefaultBufferSize(32, 32) }
            val surface = android.view.Surface(texture)
            document.attachViewport(surface, ViewportState(viewWidth = 32, viewHeight = 32))
            runBlocking { kotlinx.coroutines.withTimeout(5_000L) { document.awaitViewportPreview() } }
            return document to (texture to surface)
        }
        val white = open(BackgroundContents.WHITE, "Virtual white")
        val black = open(BackgroundContents.BLACK, "Virtual black")
        val transparent = open(BackgroundContents.TRANSPARENT, "Virtual transparent")
        try {
            assertEquals("virtual canvas background must not allocate full-document pixel tiles", 0L, white.first.residentPixelBytes)
            assertEquals(android.graphics.Color.WHITE, checkNotNull(white.first.viewportPreview).getPixel(4, 4))
            assertEquals(android.graphics.Color.BLACK, checkNotNull(black.first.viewportPreview).getPixel(4, 4))
            assertEquals(android.graphics.Color.TRANSPARENT, checkNotNull(transparent.first.viewportPreview).getPixel(4, 4))
            assertEquals(RgbaColor.White, white.first.sample(DocPoint(4f, 4f), SampleMode.MERGED).color)
            assertEquals(RgbaColor.Black, black.first.sample(DocPoint(4f, 4f), SampleMode.MERGED).color)
            assertEquals(RgbaColor.Transparent, transparent.first.sample(DocPoint(4f, 4f), SampleMode.MERGED).color)

            val document = white.first
            val layer = checkNotNull(document.layers.value.primary)
            val baseline = document.history.value.current
            val paint = document.beginStroke(
                StrokeSpec(target = layer, color = RgbaColor(0f, 0f, 1f), diameter = 8f, hardness = 1f),
            )
            paint.add(floatArrayOf(8f, 8f, 1f, 0f, 0f, 0f, 0f))
            assertTrue("default Background must accept the first brush stroke", paint.end() is app.croptop.core.engine.api.CommandResult.Accepted)
            assertEquals(RgbaColor(0f, 0f, 1f), document.sample(DocPoint(8f, 8f), SampleMode.MERGED).color)

            val erase = document.beginStroke(
                StrokeSpec(target = layer, color = RgbaColor.Black, diameter = 8f, hardness = 1f, erase = true),
            )
            erase.add(floatArrayOf(8f, 8f, 1f, 0f, 0f, 0f, 0f))
            assertTrue("eraser should restore the virtual white background", erase.end() is app.croptop.core.engine.api.CommandResult.Accepted)
            assertEquals(RgbaColor.White, document.sample(DocPoint(8f, 8f), SampleMode.MERGED).color)
            assertTrue(document.undo() is app.croptop.core.engine.api.CommandResult.Accepted)
            assertEquals(RgbaColor(0f, 0f, 1f), document.sample(DocPoint(8f, 8f), SampleMode.MERGED).color)
            val exportCases = listOf(
                Triple(document, android.graphics.Color.WHITE, "white"),
                Triple(black.first, android.graphics.Color.BLACK, "black"),
                Triple(transparent.first, android.graphics.Color.TRANSPARENT, "transparent"),
            )
            exportCases.forEach { (exportDocument, expectedPixel, backgroundName) ->
                listOf(ExportFormat.PNG, ExportFormat.JPEG, ExportFormat.WEBP).forEach { format ->
                    val file = java.io.File(
                        ApplicationProvider.getApplicationContext<Context>().cacheDir,
                        "virtual-background-$backgroundName-${format.name.lowercase()}.${format.name.lowercase()}",
                    )
                    try {
                        runBlocking {
                            exportDocument.export(ExportTarget.AppFile(file.absolutePath), ExportOptions(format = format, quality = 100))
                        }
                        val exported = checkNotNull(BitmapFactory.decodeFile(file.absolutePath))
                        try {
                            val pixel = exported.getPixel(1, 1)
                            val expected = if (format == ExportFormat.JPEG && expectedPixel == android.graphics.Color.TRANSPARENT) {
                                android.graphics.Color.WHITE
                            } else {
                                expectedPixel
                            }
                            if (format == ExportFormat.PNG) {
                                assertEquals("$format must preserve the $backgroundName canvas background", expected, pixel)
                            } else {
                                for (channel in 0..2) {
                                    assertTrue(
                                        "$format $backgroundName channel $channel was ${android.graphics.Color.red(
                                            pixel,
                                        )},${android.graphics.Color.green(pixel)},${android.graphics.Color.blue(pixel)}",
                                        kotlin.math.abs(android.graphics.Color.red(pixel) - android.graphics.Color.red(expected)) <= 8 &&
                                            kotlin.math.abs(android.graphics.Color.green(pixel) - android.graphics.Color.green(expected)) <= 8 &&
                                            kotlin.math.abs(android.graphics.Color.blue(pixel) - android.graphics.Color.blue(expected)) <= 8,
                                    )
                                }
                                if (format == ExportFormat.WEBP) {
                                    assertEquals(
                                        "WebP must preserve canvas alpha",
                                        android.graphics.Color.alpha(expected),
                                        android.graphics.Color.alpha(pixel),
                                    )
                                }
                            }
                            if (format == ExportFormat.PNG && backgroundName == "white") {
                                assertEquals("PNG export keeps painted pixels", android.graphics.Color.BLUE, exported.getPixel(8, 8))
                            }
                        } finally {
                            exported.recycle()
                        }
                    } finally {
                        file.delete()
                    }
                }
            }
            assertTrue(document.redo() is app.croptop.core.engine.api.CommandResult.Accepted)
            assertEquals(RgbaColor.White, document.sample(DocPoint(8f, 8f), SampleMode.MERGED).color)
            assertEquals(baseline + 2, document.history.value.current)
            runBlocking { kotlinx.coroutines.withTimeout(5_000L) { document.awaitViewportPreview() } }
            assertEquals(android.graphics.Color.WHITE, checkNotNull(document.viewportPreview).getPixel(8, 8))
        } finally {
            listOf(white, black, transparent).forEach { (document, handles) ->
                document.close()
                handles.second.release()
                handles.first.release()
            }
            engine.close()
        }
    }

    private fun awaitCompositePreview(document: NativeDocument) {
        val deadline = android.os.SystemClock.uptimeMillis() + 5_000L
        while (!document.hasCompositeViewportPreview && android.os.SystemClock.uptimeMillis() < deadline) {
            android.os.SystemClock.sleep(5L)
        }
        assertTrue("composite preview did not finish", document.hasCompositeViewportPreview)
    }

    @Test
    fun importedPreviewFollowsSourceLayerVisibilityAndOpacity() {
        val engine = NativeEngine()
        val document = engine.newDocument(
            NewDocumentSpec(name = "Preview presentation", size = DocSize(8, 8)),
        ) as NativeDocument
        try {
            val source = checkNotNull(document.layers.value.primary)
            assertEquals(PreviewPresentation(visible = true, opacity = 1f), document.layers.value.importedSourcePresentation(source))

            document.execute(EditCommand.SetLayerProps(source, LayerProperties(opacity = 0.4f)))
            assertEquals(PreviewPresentation(visible = true, opacity = 0.4f), document.layers.value.importedSourcePresentation(source))

            document.execute(EditCommand.SetLayerProps(source, LayerProperties(visible = false)))
            assertEquals(PreviewPresentation(visible = false, opacity = 0.4f), document.layers.value.importedSourcePresentation(source))
        } finally {
            document.close()
            engine.close()
        }
    }

    @Test
    fun importedPngExportsToAppFileAndRoundTripsPixels() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resolver = context.contentResolver
        val source = context.cacheDir.resolve("croptop-export-source.png")
        val target = context.cacheDir.resolve("croptop-export-result.png")
        val jpegTarget = context.cacheDir.resolve("croptop-export-result.jpg")
        target.writeBytes(byteArrayOf(1, 2, 3, 4))
        val uri = checkNotNull(
            resolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "croptop-export-${System.nanoTime()}.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CropTopTests")
                },
            ),
        )
        val jpegUri = checkNotNull(
            resolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "croptop-export-${System.nanoTime()}.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CropTopTests")
                },
            ),
        )
        val bitmap = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888).apply {
            setPixel(0, 0, android.graphics.Color.RED)
            setPixel(1, 0, android.graphics.Color.GREEN)
            setPixel(2, 0, android.graphics.Color.BLUE)
            setPixel(0, 1, android.graphics.Color.YELLOW)
            setPixel(1, 1, android.graphics.Color.CYAN)
            setPixel(2, 1, android.graphics.Color.MAGENTA)
        }
        source.outputStream().use { output -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) }
        bitmap.recycle()
        val engine = NativeEngine(context)
        try {
            val document = runBlocking { engine.open(DocumentSource.AppFile(source.absolutePath)) } as NativeDocument
            try {
                val result = runBlocking {
                    document.export(
                        ExportTarget.AppFile(target.absolutePath),
                        ExportOptions(format = ExportFormat.PNG),
                    )
                }
                assertEquals(DocSize(3, 2), DocSize(result.width, result.height))
                org.junit.Assert.assertTrue("PNG export wrote no bytes", result.bytesWritten > 0)
                val decoded = checkNotNull(BitmapFactory.decodeFile(target.absolutePath))
                try {
                    assertEquals(3, decoded.width)
                    assertEquals(2, decoded.height)
                    assertEquals(android.graphics.Color.RED, decoded.getPixel(0, 0))
                    assertEquals(android.graphics.Color.MAGENTA, decoded.getPixel(2, 1))
                } finally {
                    decoded.recycle()
                }
                assertTrue(
                    document.execute(EditCommand.AddLayer(LayerId(987_654_321L), "History test", LayerKind.PIXEL))
                        is app.croptop.core.engine.api.CommandResult.Accepted,
                )
                assertTrue(document.undo() is app.croptop.core.engine.api.CommandResult.Accepted)
                val restored = runBlocking {
                    document.export(ExportTarget.AppFile(target.absolutePath), ExportOptions(format = ExportFormat.PNG))
                }
                assertTrue("Undo lost the imported pixels", restored.bytesWritten > 0)
                val restoredBitmap = checkNotNull(BitmapFactory.decodeFile(target.absolutePath))
                try {
                    assertEquals(android.graphics.Color.RED, restoredBitmap.getPixel(0, 0))
                    assertEquals(android.graphics.Color.GREEN, restoredBitmap.getPixel(1, 0))
                    assertEquals(android.graphics.Color.BLUE, restoredBitmap.getPixel(2, 0))
                    assertEquals(android.graphics.Color.YELLOW, restoredBitmap.getPixel(0, 1))
                    assertEquals(android.graphics.Color.CYAN, restoredBitmap.getPixel(1, 1))
                    assertEquals(android.graphics.Color.MAGENTA, restoredBitmap.getPixel(2, 1))
                } finally {
                    restoredBitmap.recycle()
                }
                val jpegResult = runBlocking {
                    document.export(
                        ExportTarget.AppFile(jpegTarget.absolutePath),
                        ExportOptions(format = ExportFormat.JPEG, quality = 88),
                    )
                }
                assertEquals(3, jpegResult.width)
                assertEquals(2, jpegResult.height)
                assertTrue("JPEG export wrote no bytes", jpegResult.bytesWritten > 0)
                val jpegBytes = jpegTarget.readBytes()
                assertTrue(
                    "JPEG export is missing SOI marker",
                    jpegBytes.size >= 2 &&
                        jpegBytes[0] == 0xff.toByte() && jpegBytes[1] == 0xd8.toByte(),
                )
                val decodedJpeg = checkNotNull(BitmapFactory.decodeFile(jpegTarget.absolutePath))
                try {
                    assertEquals(3, decodedJpeg.width)
                    assertEquals(2, decodedJpeg.height)
                } finally {
                    decodedJpeg.recycle()
                }
                val jpegUriResult = runBlocking {
                    document.export(ExportTarget.ContentUri(jpegUri.toString()), ExportOptions(format = ExportFormat.JPEG))
                }
                assertEquals(3, jpegUriResult.width)
                assertTrue("SAF JPEG export wrote no bytes", jpegUriResult.bytesWritten > 0)
                val contentUriJpeg = resolver.openInputStream(jpegUri).use { input ->
                    checkNotNull(BitmapFactory.decodeStream(checkNotNull(input)))
                }
                try {
                    assertEquals(3, contentUriJpeg.width)
                    assertEquals(2, contentUriJpeg.height)
                } finally {
                    contentUriJpeg.recycle()
                }
                val contentUriResult = runBlocking {
                    document.export(ExportTarget.ContentUri(uri.toString()), ExportOptions(format = ExportFormat.PNG))
                }
                assertEquals(3, contentUriResult.width)
                assertEquals(2, contentUriResult.height)
                assertTrue("SAF PNG export wrote no bytes", contentUriResult.bytesWritten > 0)
                val contentUriBitmap = resolver.openInputStream(uri).use { input ->
                    checkNotNull(BitmapFactory.decodeStream(checkNotNull(input)))
                }
                try {
                    assertEquals(android.graphics.Color.GREEN, contentUriBitmap.getPixel(1, 0))
                } finally {
                    contentUriBitmap.recycle()
                }
            } finally {
                document.close()
            }
        } finally {
            engine.close()
            source.delete()
            target.delete()
            jpegTarget.delete()
            resolver.delete(uri, null, null)
            resolver.delete(jpegUri, null, null)
        }
    }

    @Test
    fun rasterExportPreservesIccAndNormalizesExifAfterApplyingOrientation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = context.cacheDir.resolve("croptop-metadata-source.png")
        val target = context.cacheDir.resolve("croptop-metadata-export.png")
        val strippedTarget = context.cacheDir.resolve("croptop-metadata-stripped.png")
        val jpegTarget = context.cacheDir.resolve("croptop-metadata-export.jpg")
        val strippedJpegTarget = context.cacheDir.resolve("croptop-metadata-stripped.jpg")
        val webpTarget = context.cacheDir.resolve("croptop-metadata-export.webp")
        val strippedWebpTarget = context.cacheDir.resolve("croptop-metadata-stripped.webp")
        val bitmap = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888).apply {
            setPixel(0, 0, android.graphics.Color.RED)
            setPixel(1, 0, android.graphics.Color.GREEN)
            setPixel(2, 0, android.graphics.Color.BLUE)
            setPixel(0, 1, android.graphics.Color.YELLOW)
            setPixel(1, 1, android.graphics.Color.CYAN)
            setPixel(2, 1, android.graphics.Color.MAGENTA)
        }
        val profile = ByteArray(132).apply {
            this[3] = 132.toByte()
            this[16] = 'R'.code.toByte()
            this[17] = 'G'.code.toByte()
            this[18] = 'B'.code.toByte()
            this[20] = 'X'.code.toByte()
            this[21] = 'Y'.code.toByte()
            this[22] = 'Z'.code.toByte()
            this[36] = 'a'.code.toByte()
            this[37] = 'c'.code.toByte()
            this[38] = 's'.code.toByte()
            this[39] = 'p'.code.toByte()
        }
        val encoded = ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            withPngIccProfile(withExifOrientation(output.toByteArray(), 6), profile)
        }
        bitmap.recycle()
        source.writeBytes(encoded)
        val engine = NativeEngine(context)
        try {
            val document = runBlocking { engine.open(DocumentSource.AppFile(source.absolutePath)) } as NativeDocument
            try {
                assertEquals(DocSize(2, 3), document.info.value.size)
                val exportResult = runBlocking {
                    document.export(ExportTarget.AppFile(target.absolutePath), ExportOptions(format = ExportFormat.PNG))
                }
                assertTrue(exportResult.warnings.any { "ICC profile was preserved" in it })
                val exported = RasterMetadataReader.read(target.readBytes(), format = ImageFormat.PNG)
                assertEquals(1, exported.exifOrientation)
                assertEquals(DocSize(2, 3), DocSize(exportResult.width, exportResult.height))
                org.junit.Assert.assertArrayEquals(profile, exported.iccProfile)
                val jpegResult = runBlocking {
                    document.export(ExportTarget.AppFile(jpegTarget.absolutePath), ExportOptions(format = ExportFormat.JPEG))
                }
                assertEquals(DocSize(2, 3), DocSize(jpegResult.width, jpegResult.height))
                val jpegMetadata = RasterMetadataReader.read(jpegTarget.readBytes(), format = ImageFormat.JPEG)
                assertEquals(1, jpegMetadata.exifOrientation)
                org.junit.Assert.assertArrayEquals(profile, jpegMetadata.iccProfile)
                val webpResult = runBlocking {
                    document.export(ExportTarget.AppFile(webpTarget.absolutePath), ExportOptions(format = ExportFormat.WEBP, quality = 95))
                }
                assertTrue("WebP export wrote no bytes", webpResult.bytesWritten > 0)
                val webpMetadata = RasterMetadataReader.read(webpTarget.readBytes(), format = ImageFormat.WEBP)
                assertEquals(1, webpMetadata.exifOrientation)
                org.junit.Assert.assertArrayEquals(profile, webpMetadata.iccProfile)
                val reopenedWebp = runBlocking { engine.open(DocumentSource.AppFile(webpTarget.absolutePath)) } as NativeDocument
                try {
                    assertEquals(DocSize(2, 3), reopenedWebp.info.value.size)
                    org.junit.Assert.assertTrue(reopenedWebp.residentPixelBytes > 0)
                } finally {
                    reopenedWebp.close()
                }
                runBlocking {
                    document.export(
                        ExportTarget.AppFile(strippedWebpTarget.absolutePath),
                        ExportOptions(format = ExportFormat.WEBP, stripMetadata = true),
                    )
                }
                val strippedWebp = RasterMetadataReader.read(strippedWebpTarget.readBytes(), format = ImageFormat.WEBP)
                assertEquals(null, strippedWebp.exifOrientation)
                assertEquals(null, strippedWebp.iccProfile)
                runBlocking {
                    document.export(
                        ExportTarget.AppFile(strippedJpegTarget.absolutePath),
                        ExportOptions(format = ExportFormat.JPEG, stripMetadata = true),
                    )
                }
                val strippedJpeg = RasterMetadataReader.read(strippedJpegTarget.readBytes(), format = ImageFormat.JPEG)
                assertEquals(null, strippedJpeg.exifOrientation)
                assertEquals(null, strippedJpeg.iccProfile)
                runBlocking {
                    document.export(
                        ExportTarget.AppFile(strippedTarget.absolutePath),
                        ExportOptions(format = ExportFormat.PNG, stripMetadata = true),
                    )
                }
                val stripped = RasterMetadataReader.read(strippedTarget.readBytes(), format = ImageFormat.PNG)
                assertEquals(null, stripped.exifOrientation)
                assertEquals(null, stripped.iccProfile)
            } finally {
                document.close()
            }
        } finally {
            engine.close()
            source.delete()
            target.delete()
            strippedTarget.delete()
            jpegTarget.delete()
            strippedJpegTarget.delete()
            webpTarget.delete()
            strippedWebpTarget.delete()
        }
    }

    private fun withExifOrientation(png: ByteArray, orientation: Int): ByteArray {
        val tiff = exifTiff(orientation)
        val type = "eXIf".encodeToByteArray()
        val crc = CRC32().apply {
            update(type)
            update(tiff)
        }.value.toInt()
        val chunk = ByteBuffer.allocate(12 + tiff.size).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(tiff.size)
            put(type)
            put(tiff)
            putInt(crc)
        }.array()
        val afterIhdr = 8 + 25 // PNG signature plus complete IHDR chunk
        return png.copyOfRange(0, afterIhdr) + chunk + png.copyOfRange(afterIhdr, png.size)
    }

    private fun withPngIccProfile(png: ByteArray, profile: ByteArray): ByteArray {
        val compressed = ByteArrayOutputStream().use { output ->
            DeflaterOutputStream(output).use { it.write(profile) }
            output.toByteArray()
        }
        val data = "CropTop Test".encodeToByteArray() + byteArrayOf(0, 0) + compressed
        val type = "iCCP".encodeToByteArray()
        val crc = CRC32().apply {
            update(type)
            update(data)
        }.value.toInt()
        val chunk = ByteBuffer.allocate(12 + data.size).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(data.size)
            put(type)
            put(data)
            putInt(crc)
        }.array()
        val afterIhdr = 8 + 25
        return png.copyOfRange(0, afterIhdr) + chunk + png.copyOfRange(afterIhdr, png.size)
    }

    private fun withJpegExifOrientation(jpeg: ByteArray, orientation: Int): ByteArray {
        val payload = "Exif\u0000\u0000".encodeToByteArray() + exifTiff(orientation)
        val length = payload.size + 2
        val segment = byteArrayOf(
            0xff.toByte(),
            0xe1.toByte(),
            (length shr 8).toByte(),
            length.toByte(),
        ) + payload
        return jpeg.copyOfRange(0, 2) + segment + jpeg.copyOfRange(2, jpeg.size)
    }

    private fun exifTiff(orientation: Int) = ByteArray(26).apply {
        this[0] = 'I'.code.toByte()
        this[1] = 'I'.code.toByte()
        this[2] = 42
        this[4] = 8
        this[8] = 1
        this[10] = 0x12
        this[11] = 0x01
        this[12] = 3
        this[14] = 1
        this[18] = orientation.toByte()
    }

    private fun bmp2x2(): ByteArray = ByteBuffer.allocate(70).order(ByteOrder.LITTLE_ENDIAN).apply {
        put('B'.code.toByte()).put('M'.code.toByte())
        putInt(70).putInt(0).putInt(54)
        putInt(40).putInt(2).putInt(2).putShort(1).putShort(24)
        putInt(0).putInt(16).putInt(0).putInt(0).putInt(0).putInt(0)
        // BMP stores rows bottom-up. Each 2-pixel RGB row has two padding bytes.
        put(0.toByte()).put(0.toByte()).put(255.toByte()).put(0.toByte()).put(255.toByte()).put(0.toByte()).put(0.toByte()).put(0.toByte())
        put(255.toByte()).put(0.toByte()).put(0.toByte()).put(255.toByte()).put(255.toByte()).put(255.toByte()).put(0.toByte()).put(0.toByte())
    }.array()

    private fun usedHeapBytes(): Long {
        val runtime = Runtime.getRuntime()
        return Debug.getNativeHeapAllocatedSize() + runtime.totalMemory() - runtime.freeMemory()
    }

    private fun pixelHash(pixels: List<RgbaColor>): Long {
        val bytes = ByteArray(16)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val crc = CRC32()
        pixels.forEach { color ->
            buffer.clear()
            buffer.putFloat(color.r).putFloat(color.g).putFloat(color.b).putFloat(color.a)
            crc.update(bytes, 0, bytes.size)
        }
        return crc.value
    }
}
